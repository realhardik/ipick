package app.ipick.voicemail.adb

import android.content.Context
import android.os.Build
import android.util.Log
import app.ipick.voicemail.capture.StartWithoutWifi
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private class PairingManager(context: Context) : AbsAdbConnectionManager() {

    private val identity = AdbIdentity.getOrCreate(context)

    init {
        setApi(Build.VERSION.SDK_INT)
        // The library's default is to wait forever - in connect, holding its
        // lock, which every other call (isConnected too) then waits on. A peer
        // that accepts and never answers stalled every revive after it.
        setTimeout(10, TimeUnit.SECONDS)
    }

    override fun getPrivateKey(): PrivateKey = identity.privateKey

    override fun getCertificate(): Certificate = identity.certificate

    /** Shown on the phone's "paired devices" list in Wireless debugging. */
    override fun getDeviceName(): String = "IPick"
}


object AdbTransport {

    private const val TAG = "IPick"

    /** adbd is on this phone. There is no other host this could ever be. */
    const val LOOPBACK = "127.0.0.1"

    const val LEGACY_TCP_PORT = 5555

    /** `exec:` rather than `shell:` so adbd allocates no PTY. See ShellSession. */
    private const val SHELL_SERVICE = "exec:sh"

    @Volatile
    private var manager: AbsAdbConnectionManager? = null

    /** The one reused shell, and the lock that stops two commands interleaving
     *  on it. A shared stream with a sentinel protocol is only correct while
     *  exactly one caller is writing to it. Volatile: a connect drops it
     *  (forgetShell) outside the lock that exec reads it under. */
    @Volatile
    private var shell: ShellSession? = null
    private val mutex = Mutex()
    @Volatile private var connection: VerifiedAdbConnection? = null

    private fun authenticated() = connection?.isConnected == true

    private fun manager(context: Context): AbsAdbConnectionManager =
        manager ?: synchronized(this) {
            manager ?: PairingManager(context.applicationContext).also { manager = it }
        }

    /**
     * The last answer isConnected gave, without asking again - for the main
     * thread, which must not wait on the library's lock: a connect holds it
     * for as long as it takes.
     */
    @Volatile
    var lastKnownConnected: Boolean = false
        private set

    @Volatile
    var connectedPort: Int = 0
        private set

    val isConnected: Boolean
        get() = try {
            authenticated().also { lastKnownConnected = it }
        } catch (t: Throwable) {
            lastKnownConnected = false
            false
        }

    /**
     * Run the SPAKE2 pairing exchange. On success adbd permanently stores our
     * public key, and every later connect() needs no code.
     *
     * The code and the pairing port are both short-lived: they exist only while
     * the "Pair device with pairing code" dialog is open, and the port is not
     * the same as the connect port.
     */
    suspend fun pair(context: Context, host: String, port: Int, code: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(host == LOOPBACK && port in 1..65535) { "Only on-device ADB pairing is supported" }
                Log.i(TAG, "pair: $host:$port")
                val ok = manager(context).pair(host, port, code)
                check(ok) { "adbd rejected the pairing code" }
                Log.i(TAG, "pair: accepted")
                Unit // Log.i returns an Int; without this the block is Result<Int>
            }
        }


    suspend fun connect(context: Context, host: String, port: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    require(host == LOOPBACK && port in 1..65535) { "Only on-device ADB connections are supported" }
                    if (!authenticated()) connectVerified(context, port,
                        allowLegacy = port == LEGACY_TCP_PORT && StartWithoutWifi.enabled(context))
                }
            }
        }

    suspend fun connectWireless(context: Context, port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                require(port in 1..65535)
                connectVerified(context, port)
                lastConnect = ConnectOutcome.CONNECTED
            }
        }
    }

    suspend fun autoConnect(context: Context, timeoutMs: Long = 5_000): Result<Unit> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                lastConnect = ConnectOutcome.NONE
                runCatching {
                    if (authenticated()) {
                        lastConnect = ConnectOutcome.CONNECTED
                        return@runCatching
                    }
                    val reported = app.ipick.voicemail.capture.SystemProperty.read("service.adb.tls.port")
                        ?.toIntOrNull()?.takeIf { it in 1..65535 }
                    var wirelessFailure: Throwable? = null
                    suspend fun tryWireless(port: Int): Boolean {
                        return runCatching { connectVerified(context, port) }.onFailure {
                            wirelessFailure = it
                            Log.w(TAG, "ADB endpoint verification failed", it)
                        }.isSuccess
                    }
                    if (reported != null && tryWireless(reported)) {
                        lastConnect = ConnectOutcome.CONNECTED
                        return@runCatching
                    }
                    val discovered = discoverPort(context, AdbMdns.SERVICE_TYPE_TLS_CONNECT, timeoutMs)
                    if (discovered != null && discovered != reported) {
                        if (tryWireless(discovered)) {
                            lastConnect = ConnectOutcome.CONNECTED
                            return@runCatching
                        }
                    }
                    lastConnect = if (wirelessFailure == null) ConnectOutcome.NOT_FOUND else ConnectOutcome.REFUSED
                    if (!StartWithoutWifi.ready(context)) {
                        throw wirelessFailure ?: java.io.IOException("IPick could not find the Wireless debugging port.")
                    }
                    connectVerified(context, LEGACY_TCP_PORT, allowLegacy = true)
                    lastConnect = ConnectOutcome.CONNECTED
                }.onSuccess { lastWirelessError = null }.onFailure { lastWirelessError = it.localizedMessage }
            }
        }

    @Volatile
    var lastWirelessError: String? = null
        private set

    private suspend fun connectVerified(context: Context, port: Int, allowLegacy: Boolean = false) = coroutineScope {
        lastKnownConnected = false
        connectedPort = 0
        connection?.close()
        connection = null
        forgetShell()
        val candidate = VerifiedAdbConnection()
        val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
        val watchdog = launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(20_000)
            candidate.close()
        }
        try {
            candidate.connect(context, port, allowLegacy)
            check(android.os.SystemClock.elapsedRealtime() < deadline && candidate.isConnected) {
                "ADB connection ended during verification"
            }
            connection = candidate
            connectedPort = port
            lastKnownConnected = true
            Log.i(TAG, "ADB endpoint verified")
        } catch (t: Throwable) {
            candidate.close()
            throw t
        } finally {
            watchdog.cancel()
        }
    }

    /**
     * The shell kept from a connection before this one goes with it. A new
     * connection is made only when the last one has died - adbd restarted,
     * stopped with USB debugging - and the kept shell is a stream of the dead
     * one: the first command after the reconnect wrote to it and failed
     * "Stream closed". Measured on the Honor, 2026-10-27: the recorder's
     * start after Turn all debugging off and on failed at copying the jar,
     * and the next try, with the shell then dropped, started it.
     */
    private fun forgetShell() {
        runCatching { shell?.close() }
        shell = null
    }

    suspend fun restartAdbd(context: Context, service: String, timeoutMs: Long = 5_000): Result<String> =
        withContext(Dispatchers.IO) {
            val mgr = connection
            if (mgr == null || !mgr.isConnected) {
                return@withContext Result.failure(IllegalStateException("ADB is not connected"))
            }
            mutex.withLock {
                if (service != "usb:" && !(service == "tcpip:$LEGACY_TCP_PORT" && StartWithoutWifi.enabled(context)))
                    return@withLock Result.failure(SecurityException("Offline startup is disabled"))
                if (connection !== mgr || !mgr.isConnected)
                    return@withLock Result.failure(IllegalStateException("ADB connection is not verified"))
                forgetShell()
                // A stream that never answers is let go by closing the whole
                // connection, as exec's watchdog does; it is about to die anyway.
                val watchdog = launch {
                    kotlinx.coroutines.delay(timeoutMs)
                    runCatching { mgr.close() }
                }
                try {
                    runCatching {
                        Log.i(TAG, "restartAdbd: $service")
                        mgr.openStream(service).openInputStream().bufferedReader().readLine().orEmpty().trim()
                    }
                } finally {
                    watchdog.cancel()
                }
            }
        }

    /**
     * How the last autoConnect went, for the recorder-down alert (see
     * RecorderHealth): an adbd that advertises itself and then turns us away
     * is telling us something different from one that is not there at all.
     * NONE until an attempt this process has made says otherwise.
     */
    enum class ConnectOutcome { NONE, CONNECTED, NOT_FOUND, REFUSED }

    @Volatile
    var lastConnect: ConnectOutcome = ConnectOutcome.NONE
        private set

    /** A revive starts a fresh reading, so one that never got as far as
     *  connecting does not inherit the last one's. */
    fun forgetLastConnect() {
        lastConnect = ConnectOutcome.NONE
    }

    /**
     * Browse mDNS for one of adbd's advertised services and return its port.
     *
     * [AdbMdns.SERVICE_TYPE_TLS_PAIRING] is advertised only while the pairing
     * dialog is on screen; [AdbMdns.SERVICE_TYPE_TLS_CONNECT] whenever Wireless
     * debugging is on AND a network interface exists to advertise on.
     */
    suspend fun discoverPort(
        context: Context,
        serviceType: String,
        timeoutMs: Long = 5_000,
    ): Int? = withContext(Dispatchers.IO) {
        val port = AtomicInteger(-1)
        val found = CountDownLatch(1)

        val mdns = AdbMdns(context.applicationContext, serviceType) { _: InetAddress?, p: Int ->
            if (p > 0 && port.compareAndSet(-1, p)) found.countDown()
        }
        mdns.start()
        try {
            if (!found.await(timeoutMs, TimeUnit.MILLISECONDS)) return@withContext null
        } finally {
            mdns.stop()
        }
        port.get().takeIf { it > 0 }
    }

    suspend fun exec(command: String, timeoutMs: Long = 15_000): Result<String> =
        withContext(Dispatchers.IO) {
            val mgr = connection
            if (mgr == null || !mgr.isConnected) {
                return@withContext Result.failure(IllegalStateException("ADB is not connected"))
            }

            mutex.withLock {
                runCatching {
                    check(connection === mgr && mgr.isConnected) { "ADB connection is not verified" }
                    // Watching from before the stream is opened: opening one
                    // can hang too, and then only closing the whole
                    // connection lets it go.
                    var session: ShellSession? = shell
                    val watchdog = launch {
                        kotlinx.coroutines.delay(timeoutMs)
                        Log.w(TAG, "exec: '${command.substringBefore(' ')} ...' timed out after ${timeoutMs}ms")
                        session?.let { runCatching { it.close() } } ?: runCatching { mgr.close() }
                        shell = null
                    }
                    session = session ?: ShellSession(mgr.openStream(SHELL_SERVICE)).also { shell = it }
                    try {
                        session!!.run(command)
                    } catch (t: Throwable) {
                        // A desynchronised session stays desynchronised, and a
                        // half-read one would mis-attribute the next command's
                        // output. Drop it; the next call opens a fresh one.
                        runCatching { session?.close() }
                        shell = null
                        throw t
                    } finally {
                        watchdog.cancel()
                    }
                }
            }
        }


    private class ShellSession(private val stream: VerifiedAdbConnection.Stream) {
        private val marker = "__ipick_" + java.util.UUID.randomUUID().toString().take(12) + "__"
        private val out = stream.openOutputStream()
        private val reader = stream.openInputStream().bufferedReader()

        init {
            // Turn the terminal echo off ONCE, outside the command protocol.
            //
            // adbd hands back a PTY even for `exec:sh`, and a PTY echoes back
            // whatever is written to it. Dropping those echoed lines afterwards
            // works only while an echoed line IS a line: a long command wraps
            // across several, and then the filter misses it and the fragments
            // land in the output. A `pm grant ...` line is long enough to do it.
            //
            // An earlier attempt ran `stty -echo` as a normal command and
            // desynchronised the session, because its own echo arrives under
            // the old setting while the reader is already expecting the new
            // one. Writing it raw and draining whatever comes back for a moment
            // sidesteps that entirely - nothing is being matched, so nothing
            // can fail to match.
            runCatching {
                out.write("stty -echo 2>/dev/null; PS1=''\n".toByteArray(Charsets.UTF_8))
                out.flush()
                val deadline = System.currentTimeMillis() + SETTLE_MS
                while (System.currentTimeMillis() < deadline) {
                    if (reader.ready()) reader.read() else Thread.sleep(20)
                }
            }
        }

        fun run(command: String): String {
            val begin = "$marker-BEGIN"
            val end = "$marker-END"

            // ONE line, not three, and that is the whole trick.
            //
            // adbd gives back a PTY even for `exec:sh`, and a PTY echoes each
            // input LINE back immediately before the shell runs it. With the
            // markers on separate lines the echo interleaves with the output:
            //
            //   echo hello          <- echo of line 2
            //   hello               <- what line 2 printed
            //   echo <marker>-END   <- echo of line 3
            //
            // and no amount of line filtering is safe, because a long command
            // wraps across several lines and the fragments look like results.
            // `stty -echo` does not stick here either.
            //
            // Sent as a single line, ALL the echo happens before ANY output, so
            // discarding everything up to the first exact BEGIN removes it
            // whatever shape it arrived in. The echoed text always contains the
            // word "echo", so it can never be equal to the marker the shell
            // prints when it runs the line.
            //
            // Commands must therefore be single-line; use ';' rather than
            // newlines, which is what every caller here already does.
            val oneLine = command.replace('\n', ';')

            // The command goes in a SUBSHELL, and that is not decoration.
            //
            // Joining with semicolons alone produces `cmd & ; echo END` for any
            // command that ends in `&`, and `& ;` is a syntax error in sh. The
            // shell dies, the end marker never arrives, and the caller sees a
            // timeout rather than an error - which is exactly how the daemon
            // launch broke: it is the one command here that backgrounds itself.
            //
            // `( cmd & )` is valid, and so is `( cmd )`, so wrapping handles
            // both shapes without the caller having to think about it.
            out.write("echo $begin; ( $oneLine ) ; echo $end\n".toByteArray(Charsets.UTF_8))
            out.flush()

            while (true) {
                val line = reader.readLine() ?: throw java.io.IOException("shell stream closed")
                if (line.trimEnd() == begin) break
            }

            val sb = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: throw java.io.IOException("shell stream closed")
                if (line.trimEnd() == end) break
                sb.appendLine(line)
            }
            return sb.toString().trimEnd()
        }

        fun close() {
            runCatching { out.close() }
            runCatching { if (!stream.isClosed) stream.close() }
        }

        private companion object {
            /** Long enough for the shell to apply stty and stop echoing. */
            const val SETTLE_MS = 400L
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        mutex.withLock {
            lastKnownConnected = false
            connectedPort = 0
            connection?.close()
            connection = null
            forgetShell()
            Unit
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock {
            lastKnownConnected = false
            connectedPort = 0
            connection?.close()
            connection = null
            forgetShell()
            runCatching { manager?.disconnect() }
            manager = null
            Unit
        }
    }
}

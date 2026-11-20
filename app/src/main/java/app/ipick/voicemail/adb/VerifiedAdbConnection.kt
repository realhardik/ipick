package app.ipick.voicemail.adb

import android.content.Context
import app.ipick.voicemail.capture.StartWithoutWifi
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

internal class VerifiedAdbConnection : Closeable {
    private val tcp = Socket()
    @Volatile private var tls: SSLSocket? = null
    @Volatile private var closed = false
    @Volatile private var verified = false
    @Volatile private var activeProof: AdbEndpointProof.Challenge? = null
    private lateinit var input: InputStream
    private var output: OutputStream? = null
    private val writes = Any()
    private val nextId = AtomicInteger()
    private val streams = ConcurrentHashMap<Int, Stream>()
    private var maximum = MAX_PAYLOAD
    private var protocol = VERSION
    private var negotiated = false
    @Volatile private var legacyContext: Context? = null
    val isConnected get() = verified && !closed && legacyContext?.let { StartWithoutWifi.enabled(it) } != false

    fun connect(context: Context, port: Int, allowLegacy: Boolean) {
        var stage = "loading the saved pairing"
        try {
            val identity = AdbIdentity.getOrCreate(context)
            stage = "opening the phone’s debugging port"
            tcp.connect(InetSocketAddress(AdbTransport.LOOPBACK, port), 10_000)
            tcp.soTimeout = 10_000
            tcp.tcpNoDelay = true
            input = tcp.getInputStream()
            output = tcp.getOutputStream()
            send(CNXN, VERSION, MAX_PAYLOAD, "host::\u0000".toByteArray())
            val offer = receive(4096)
            stage = "authenticating the paired connection"
            if (offer.command == AUTH && allowLegacy && port == StartWithoutWifi.PORT && StartWithoutWifi.enabled(context) && StartWithoutWifi.ready(context)) {
                legacyContext = context.applicationContext
                answerLegacyChallenge(identity, offer)
            } else {
                upgradeToTls(port, identity, offer)
            }
            val connected = receive(MAX_PAYLOAD)
            if (connected.command != CNXN || connected.arg0 !in 0x01000000..VERSION || connected.arg1 !in 4096..MAX_PAYLOAD)
                throw SecurityException("Invalid ADB connection response")
            protocol = connected.arg0
            negotiated = true
            maximum = connected.arg1
            tcp.soTimeout = 0
            tls?.soTimeout = 0
            Thread(::readLoop, "ipick-adb-reader").apply { isDaemon = true; start() }
            stage = "verifying Android’s shell identity"
            AdbEndpointProof.begin().use { proof ->
                activeProof = proof
                try {
                    if (closed) throw IOException("ADB connection closed")
                    val command = "shell:/system/bin/content call --uri content://${context.packageName}.bootstrap " +
                        "--method verify-adb --arg ${proof.nonce}"
                    open(command, verification = true).use {
                        if (!proof.await()) throw SecurityException("ADB endpoint could not prove its Android shell identity")
                    }
                } finally {
                    activeProof = null
                }
            }
            if (closed) throw IOException("ADB connection ended during verification")
            verified = true
        } catch (t: Throwable) {
            close()
            if (t !is Exception) throw t
            throw IOException("Connection failed while $stage (${t.localizedMessage ?: t.javaClass.simpleName}).", t)
        }
    }

    private fun answerLegacyChallenge(identity: AdbIdentity, offer: Packet) {
        if (offer.arg0 != 1 || offer.arg1 != 0 || offer.body.size != 20)
            throw SecurityException("Invalid legacy ADB key challenge")
        val digest = org.bouncycastle.asn1.x509.DigestInfo(
            org.bouncycastle.asn1.x509.AlgorithmIdentifier(org.bouncycastle.asn1.oiw.OIWObjectIdentifiers.idSHA1,
                org.bouncycastle.asn1.DERNull.INSTANCE), offer.body)
        val signature = java.security.Signature.getInstance("NONEwithRSA").run {
            initSign(identity.privateKey)
            update(digest.encoded)
            sign()
        }
        send(AUTH, 2, 0, signature)
    }

    private fun upgradeToTls(port: Int, identity: AdbIdentity, upgrade: Packet) {
        if (upgrade.command != STLS || upgrade.arg0 != TLS_VERSION || upgrade.arg1 != 0 || upgrade.body.isNotEmpty())
            throw SecurityException("ADB endpoint does not support authenticated TLS; legacy ADB is refused")
        send(STLS, TLS_VERSION, 0)
        val sslContext = SSLContext.getInstance("TLSv1.3", org.conscrypt.Conscrypt.newProvider())
        sslContext.init(arrayOf(ClientKey(identity)), arrayOf(ProvisionalTrust()), null)
        val secure = sslContext.socketFactory.createSocket(tcp, AdbTransport.LOOPBACK, port, true) as SSLSocket
        tls = secure
        if (closed) throw IOException("ADB connection closed")
        secure.useClientMode = true
        secure.enabledProtocols = arrayOf("TLSv1.3")
        secure.soTimeout = 10_000
        secure.startHandshake()
        input = secure.inputStream
        output = secure.outputStream
    }

    fun openStream(destination: String): Stream = open(destination, verification = false)

    private fun open(destination: String, verification: Boolean): Stream {
        if (closed || (!verification && !isConnected)) throw SecurityException("ADB endpoint is not verified")
        val body = (destination + '\u0000').toByteArray(Charsets.UTF_8)
        require(body.size <= maximum && '\u0000' !in destination)
        val id = nextId.incrementAndGet()
        check(id > 0)
        val stream = Stream(id)
        streams[id] = stream
        try {
            send(OPEN, id, 0, body)
            stream.awaitReady(opening = true)
            return stream
        } catch (t: Throwable) {
            stream.close()
            throw t
        }
    }

    private fun readLoop() {
        try {
            while (!closed) {
                val packet = receive(maximum)
                if (packet.command != OKAY && packet.command != WRTE && packet.command != CLSE)
                    throw SecurityException("Unexpected ADB command after TLS connection")
                val stream = streams[packet.arg1] ?: continue
                when (packet.command) {
                    OKAY -> {
                        if (packet.body.isNotEmpty()) throw IOException("Invalid ADB acknowledgement")
                        stream.ready(packet.arg0)
                    }
                    WRTE -> {
                        if (stream.receive(packet.arg0, packet.body)) send(OKAY, stream.id, packet.arg0)
                    }
                    CLSE -> {
                        stream.finish(packet.arg0)
                        streams.remove(stream.id, stream)
                        send(CLSE, stream.id, packet.arg0)
                    }
                }
            }
        } catch (_: Exception) {
            close()
        }
    }

    private data class Packet(val command: Int, val arg0: Int, val arg1: Int, val body: ByteArray)

    private fun receive(limit: Int): Packet {
        val source = DataInputStream(input)
        val bytes = ByteArray(24).also(source::readFully)
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val command = header.int
        val arg0 = header.int
        val arg1 = header.int
        val size = header.int
        val checksum = header.int
        val magic = header.int
        if (magic != command.inv() || size !in 0..limit) throw IOException("Invalid ADB frame")
        val body = ByteArray(size).also(source::readFully)
        if (protocol < VERSION && checksum != body.sumOf { it.toInt() and 255 }) throw IOException("Invalid ADB checksum")
        return Packet(command, arg0, arg1, body)
    }

    private fun send(command: Int, arg0: Int, arg1: Int, body: ByteArray = byteArrayOf()) = synchronized(writes) {
        if (closed) throw IOException("ADB connection closed")
        val destination = output ?: throw IOException("ADB connection is unavailable")
        val checksum = if (!negotiated || protocol < VERSION) body.sumOf { it.toInt() and 255 } else 0
        destination.write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1).putInt(body.size).putInt(checksum).putInt(command.inv()).array())
        destination.write(body)
        destination.flush()
    }

    override fun close() {
        closed = true
        verified = false
        activeProof?.close()
        runCatching { tcp.close() }
        runCatching { tls?.close() }
        streams.values.forEach { it.abort() }
        streams.clear()
    }

    inner class Stream internal constructor(val id: Int) : Closeable {
        private val state = java.lang.Object()
        private val queued = ArrayDeque<ByteArray>()
        private var remoteId = 0
        private var canWrite = false
        @Volatile private var ended = false
        val isClosed get() = ended

        internal fun awaitReady(opening: Boolean = false) = synchronized(state) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            while ((if (opening) remoteId == 0 else !canWrite) && !ended && !closed) {
                val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                if (remaining <= 0) throw IOException("ADB stream timed out")
                state.wait(remaining)
            }
            if (closed || (ended && (!opening || remoteId == 0))) throw IOException("ADB stream closed")
        }

        internal fun ready(peer: Int) = synchronized(state) {
            if (ended) return@synchronized
            if (peer <= 0 || (remoteId != 0 && remoteId != peer) || canWrite) throw IOException("Invalid ADB stream acknowledgement")
            remoteId = peer
            canWrite = true
            state.notifyAll()
        }

        internal fun receive(peer: Int, bytes: ByteArray): Boolean = synchronized(state) {
            if (ended) return@synchronized false
            if (peer != remoteId || peer <= 0) throw IOException("Invalid ADB stream")
            if (bytes.isNotEmpty()) {
                if (queued.size >= 4) throw IOException("ADB stream exceeded its buffer limit")
                queued.addLast(bytes)
                state.notifyAll()
            }
            true
        }

        internal fun finish(peer: Int) = synchronized(state) {
            if (peer != 0 && remoteId != 0 && remoteId != peer) throw IOException("Invalid ADB stream close")
            ended = true
            state.notifyAll()
        }

        internal fun abort() = synchronized(state) {
            ended = true
            state.notifyAll()
        }

        private val reader = object : InputStream() {
            private var bytes = byteArrayOf()
            private var position = 0
            override fun read(): Int {
                val single = ByteArray(1)
                return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 255
            }
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                require(offset >= 0 && length >= 0 && offset <= target.size - length)
                if (length == 0) return 0
                if (position == bytes.size) {
                    synchronized(state) {
                        while (queued.isEmpty() && !ended && !closed) state.wait()
                        if (queued.isEmpty()) return -1
                        bytes = queued.removeFirst()
                        position = 0
                    }
                }
                val count = minOf(length, bytes.size - position)
                bytes.copyInto(target, offset, position, position + count)
                position += count
                return count
            }
            override fun available(): Int = synchronized(state) { bytes.size - position + queued.sumOf { it.size } }
            override fun close() = this@Stream.close()
        }

        private val writer = object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                var at = offset
                val end = offset + length
                while (at < end) {
                    awaitReady()
                    val peer = synchronized(state) {
                        if (ended || closed) throw IOException("ADB stream closed")
                        canWrite = false
                        remoteId
                    }
                    val count = minOf(maximum, end - at)
                    send(WRTE, id, peer, bytes.copyOfRange(at, at + count))
                    at += count
                }
            }
            override fun close() = this@Stream.close()
        }

        fun openInputStream(): InputStream = reader
        fun openOutputStream(): OutputStream = writer

        override fun close() {
            val peer = synchronized(state) {
                if (ended) return
                ended = true
                queued.clear()
                state.notifyAll()
                remoteId
            }
            streams.remove(id, this)
            if (!closed) runCatching { send(CLSE, id, peer) }
        }
    }

    private class ClientKey(private val identity: AdbIdentity) : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf("ipick")
        override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = "ipick"
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        override fun getCertificateChain(alias: String?) = if (alias == "ipick") arrayOf(identity.certificate as X509Certificate) else null
        override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == "ipick") identity.privateKey else null
    }

    private class ProvisionalTrust : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw java.security.cert.CertificateException("Client trust is unsupported")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty() || chain.size > 5) throw java.security.cert.CertificateException("Invalid ADB certificate chain")
            chain[0].checkValidity()
        }
        override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
    }

    private companion object {
        const val MAX_PAYLOAD = 1024 * 1024
        const val VERSION = 0x01000001
        const val TLS_VERSION = 0x01000000
        const val AUTH = 0x48545541
        const val CNXN = 0x4e584e43
        const val STLS = 0x534c5453
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val WRTE = 0x45545257
        const val CLSE = 0x45534c43
    }
}

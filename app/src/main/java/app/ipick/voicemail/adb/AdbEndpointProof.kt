package app.ipick.voicemail.adb

import android.os.Binder
import android.os.SystemClock
import java.io.Closeable
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object AdbEndpointProof {
    private val random = SecureRandom()
    private var pending: Challenge? = null

    class Challenge internal constructor(val nonce: String) : Closeable {
        internal val deadline = SystemClock.elapsedRealtime() + 10_000
        internal val signal = CountDownLatch(1)
        @Volatile internal var confirmed = false

        fun await(): Boolean {
            val remaining = deadline - SystemClock.elapsedRealtime()
            return remaining > 0 && signal.await(remaining, TimeUnit.MILLISECONDS) && confirmed
        }

        override fun close() = synchronized(AdbEndpointProof) {
            if (pending === this) pending = null
            signal.countDown()
        }
    }

    @Synchronized
    fun begin(): Challenge {
        check(pending == null) { "An ADB verification is already in progress" }
        return Challenge(ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) })
            .also { pending = it }
    }

    @Synchronized
    fun confirm(nonce: String?) {
        if (Binder.getCallingUid() != 2000) throw SecurityException("Shell identity required")
        val challenge = pending ?: throw SecurityException("No ADB verification is pending")
        if (nonce != challenge.nonce || SystemClock.elapsedRealtime() >= challenge.deadline || challenge.confirmed)
            throw SecurityException("Invalid ADB verification")
        challenge.confirmed = true
        challenge.signal.countDown()
    }
}

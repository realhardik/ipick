package app.ipick.voicemail.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object SecretStorage {
    private const val ALIAS = "ipick.private.credentials.v1"

    private fun file(c: Context, name: String) = AtomicFile(File(c.noBackupFilesDir, "$name.sealed"))

    @Synchronized
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Credential encryption key is unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    fun exists(c: Context, name: String): Boolean {
        val base = file(c, name).baseFile
        return base.exists() || File(base.path + ".bak").exists()
    }

    fun read(c: Context, name: String): ByteArray {
        val bytes = file(c, name).readFully()
        check(bytes.size in 29..16_384 && bytes[0] == 1.toByte()) { "Invalid stored credential" }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            updateAAD(name.toByteArray(Charsets.UTF_8))
            doFinal(bytes, 13, bytes.size - 13)
        }
    }

    fun write(c: Context, name: String, secret: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(true)) }
        check(cipher.iv.size == 12)
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(secret)
        val target = file(c, name)
        val out = target.startWrite()
        try {
            out.write(bytes)
            target.finishWrite(out)
        } catch (t: Throwable) {
            target.failWrite(out)
            throw t
        }
    }

    fun delete(c: Context, name: String) = file(c, name).delete()
}

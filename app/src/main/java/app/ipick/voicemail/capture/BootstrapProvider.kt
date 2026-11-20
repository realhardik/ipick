package app.ipick.voicemail.capture

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

class BootstrapProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (Binder.getCallingUid() != 2000) throw SecurityException("Shell access required")
        return when (method) {
            "bootstrap" -> Bundle().apply { putString("token", CaptureDaemon.token(requireNotNull(context))) }
            "verify-adb" -> {
                app.ipick.voicemail.adb.AdbEndpointProof.confirm(arg)
                Bundle()
            }
            else -> throw SecurityException("Unsupported operation")
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw SecurityException("Unsupported operation")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Unsupported operation")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Unsupported operation")
}

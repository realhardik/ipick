package app.ipick.voicemail.response

import android.content.Context
import androidx.core.content.edit
import app.ipick.voicemail.Prefs
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

data class StatusState(val status: Availability, val custom: String, val mode: ResponseMode,
    val lastFailure: String?,
    val idle: String = Availability.IDLE.defaultReply,
    val dnd: String = Availability.DND.defaultReply) {
    fun replyFor(value: Availability) = when (value) {
        Availability.IDLE -> idle
        Availability.DND -> dnd
        Availability.CUSTOM -> custom
    }
    val reply get() = replyFor(status)
}

object StatusRepository {
    const val PREFIX = "response."
    private const val MODE = PREFIX + "mode"
    private const val EPOCH = PREFIX + "generation"
    const val LAST_FAILURE = PREFIX + "failure"
    const val RECENT = "last_sms_call_ids"

    fun autoText(c: Context) = Prefs.of(c).getBoolean(MODE, false)
    fun generation(c: Context) = Prefs.of(c).getLong(EPOCH, 0)
    fun setAutoText(c: Context, on: Boolean) {
        if (on != autoText(c)) Prefs.of(c).edit {
            if (on && read(c).status == Availability.CUSTOM && read(c).custom.isBlank())
                putString(PREFIX + "status", Availability.IDLE.name)
            putBoolean(MODE, on)
            putLong(EPOCH, generation(c) + 1)
            remove(LAST_FAILURE)
        }
    }

    fun read(c: Context): StatusState {
        val p = Prefs.of(c)
        val status = Availability.entries.firstOrNull { it.name == p.getString(PREFIX + "status", null) } ?: Availability.IDLE
        return StatusState(status, p.getString(PREFIX + "custom", "").orEmpty().ifBlank { defaultReply(Availability.CUSTOM) },
            if (autoText(c)) ResponseMode.AUTO_TEXT else ResponseMode.VOICEMAIL,
            p.getString(LAST_FAILURE, null),
            p.getString(PREFIX + "sms.IDLE", null) ?: Availability.IDLE.defaultReply,
            p.getString(PREFIX + "sms.DND", null) ?: Availability.DND.defaultReply)
    }

    fun choose(c: Context, status: Availability) {
        if (status != Availability.CUSTOM || !autoText(c) || read(c).custom.isNotBlank())
            Prefs.of(c).edit { putString(PREFIX + "status", status.name) }
    }

    fun defaultReply(status: Availability) = status.defaultReply.ifBlank { "I can’t take your call right now. Please send me a text." }

    fun saveReply(c: Context, status: Availability, text: String) {
        require(text.isNotBlank() && text.length <= 160)
        val key = if (status == Availability.CUSTOM) PREFIX + "custom" else PREFIX + "sms." + status.name
        Prefs.of(c).edit { putString(key, text.trim()) }
    }

    fun saveCustom(c: Context, text: String) {
        require(text.isNotBlank() && text.length <= 160)
        Prefs.of(c).edit { putString(PREFIX + "custom", text.trim()); putString(PREFIX + "status", Availability.CUSTOM.name) }
    }

    fun watch(c: Context) = callbackFlow {
        val p = Prefs.of(c)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith(PREFIX)) trySend(read(c))
        }
        p.registerOnSharedPreferenceChangeListener(listener)
        trySend(read(c))
        awaitClose { p.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    // Claim before dispatch. A service rebind or process restart must never submit the same SMS twice.
    @Synchronized fun claim(c: Context, callId: String): Boolean {
        val p = Prefs.of(c)
        val ids = p.getString(RECENT, "").orEmpty().split('|').filter { it.isNotEmpty() }
        return callId !in ids && p.edit().putString(RECENT, (ids.takeLast(31) + callId).joinToString("|")).commit()
    }

    fun failure(c: Context, message: String?) = Prefs.of(c).edit { putString(LAST_FAILURE, message) }
}

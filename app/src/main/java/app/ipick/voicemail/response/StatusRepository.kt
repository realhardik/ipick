package app.ipick.voicemail.response

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import app.ipick.voicemail.Prefs
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

data class StatusState(val status: Availability, val custom: String, val mode: ResponseMode,
    val lastFailure: String?,
    val idle: String = Availability.IDLE.defaultReply,
    val dnd: String = Availability.DND.defaultReply,
    val normalStatus: Availability = Availability.IDLE) {
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
    internal const val EPOCH = PREFIX + "generation"
    const val LAST_FAILURE = PREFIX + "failure"
    const val RECENT = "last_sms_call_ids"

    fun autoText(c: Context) = Prefs.of(c).getBoolean(MODE, false)
    fun smsEnabled(c: Context) = app.ipick.voicemail.capture.RecorderSwitch.isOn(c) && autoText(c)
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

    fun phoneDnd(c: Context): Boolean {
        val manager = c.getSystemService(android.app.NotificationManager::class.java)
        return when (manager.currentInterruptionFilter) {
            android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            android.app.NotificationManager.INTERRUPTION_FILTER_NONE,
            android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS -> true
            else -> false
        }
    }

    fun read(c: Context): StatusState {
        val p = Prefs.of(c)
        val normal = if (p.getString(PREFIX + "status", null) == Availability.CUSTOM.name) Availability.CUSTOM else Availability.IDLE
        val status = if (phoneDnd(c)) Availability.DND else normal
        return StatusState(status, p.getString(PREFIX + "custom", "").orEmpty().ifBlank { defaultReply(Availability.CUSTOM) },
            if (autoText(c)) ResponseMode.AUTO_TEXT else ResponseMode.VOICEMAIL,
            p.getString(LAST_FAILURE, null),
            p.getString(PREFIX + "sms.IDLE", null) ?: Availability.IDLE.defaultReply,
            p.getString(PREFIX + "sms.DND", null) ?: Availability.DND.defaultReply, normal)
    }

    fun choose(c: Context, status: Availability) {
        if (status == Availability.DND) return
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

    fun watch(c: Context, ringing: Boolean = false) = callbackFlow {
        val p = Prefs.of(c)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith(PREFIX)) trySend(read(c))
        }
        p.registerOnSharedPreferenceChangeListener(listener)
        trySend(read(c))
        val changes = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(read(c)) }
        }
        val observing = runCatching {
            c.contentResolver.registerContentObserver(android.provider.Settings.Global.getUriFor("zen_mode"), false, changes)
        }.isSuccess
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { trySend(read(c)) }
        }
        val registered = runCatching {
            ContextCompat.registerReceiver(c, receiver, IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED)
        }.isSuccess
        val poll = launch {
            var previous = phoneDnd(c)
            trySend(read(c))
            while (true) {
                kotlinx.coroutines.delay(if (ringing) 1_000L else 30_000L)
                val current = phoneDnd(c)
                if (current != previous) trySend(read(c))
                previous = current
            }
        }
        awaitClose {
            poll.cancel()
            if (registered) c.unregisterReceiver(receiver)
            if (observing) c.contentResolver.unregisterContentObserver(changes)
            p.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }.distinctUntilChanged()

    fun failure(c: Context, message: String?) = Prefs.of(c).edit { putString(LAST_FAILURE, message) }
}

package app.ipick.voicemail.capture

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.content.edit
import app.ipick.voicemail.Prefs
import app.ipick.voicemail.calls.CallTracking
import app.ipick.voicemail.response.StatusRepository
import app.ipick.voicemail.calls.MissedCalls
import app.ipick.voicemail.ui.RecorderTile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object RecorderSwitch {

    private const val KEY = "response.enabled"

    fun migrate(context: Context) {
        val prefs = Prefs.of(context)
        if (prefs.contains(KEY)) return
        val enabled = prefs.getBoolean("recorder_enabled", true) && CallTracking.enabled(context) &&
            (StatusRepository.autoText(context) || prefs.getBoolean("answer_on", false))
        prefs.edit { putBoolean(KEY, enabled); remove("recorder_enabled"); remove("answer_on") }
    }

    fun isOn(context: Context) = Prefs.of(context).getBoolean(KEY, false)

    fun set(context: Context, on: Boolean) {
        val wasOn = isOn(context)
        if (wasOn == on) return
        if (on && !CallTracking.enabled(context)) CallTracking.setEnabled(context, true)
        Prefs.of(context).edit {
            putBoolean(KEY, on)
            putLong(StatusRepository.EPOCH, StatusRepository.generation(context) + 1)
        }
        MissedCalls.coverageChanged(context)
        // A stop while the switch was off cost no call: from here on only
        // newer ones count, however the switch was flipped (app or tile).
        if (on) BackgroundKill.recordingSwitchedOn(context)
        // The Quick Settings tile shows this switch; ask it to look again, so
        // a change made in the app is not contradicted the next time the
        // panel is pulled down.
        runCatching { TileService.requestListeningState(context, ComponentName(context, RecorderTile::class.java)) }
    }

    fun observe(context: Context, onChange: (Boolean) -> Unit): () -> Unit =
        Prefs.watch(context, KEY) { onChange(isOn(context)) }

    /** A tile can be unbound before the helper receives the switch change. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val turning = Mutex()

    fun turnInBackground(context: Context, on: Boolean) {
        val app = context.applicationContext
        set(app, on)
        background.launch {
            runCatching { turn(app, on) }
                .onFailure { Log.w("IPick", "could not turn recording ${if (on) "on" else "off"}", it) }
        }
    }

    /** Apply the latest switch value while keeping the paired helper available. */
    suspend fun turn(context: Context, on: Boolean) = turning.withLock {
        if (isOn(context) != on) return@withLock
        if (on) {
            // What the last off switched off, back first: the revive below
            // stands down while it is still off (DebugSwitchesOff).
            DebugSwitchesOff.switchBackOn(context)
            // The user's own On: not held to a routine revive's limits.
            if (!StatusRepository.autoText(context)) CaptureDaemon.revive(context, request = CaptureDaemon.Request.USER)
            RecorderKeepAlive.schedule(context)
            NetworkRevive.arm(context)
            CaptureDaemon.pushMode(context)
        } else {
            CaptureDaemon.setAnswering(context)
            CaptureDaemon.setDaemonMode(context, CaptureDaemon.MODE_OFF)
            CallMonitorService.stop(context)
        }
    }
}

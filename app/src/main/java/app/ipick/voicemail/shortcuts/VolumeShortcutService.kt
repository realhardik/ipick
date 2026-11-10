package app.ipick.voicemail.shortcuts

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import app.ipick.voicemail.Prefs

/** Observes buttons only; never reads window content or consumes normal key events. */
class VolumeShortcutService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val hold = VolumeHold()
    private val trigger = Runnable {
        if (Prefs.of(this).getBoolean(ENABLED, false))
            hold.fire(IncomingVoicemail.currentToken(), SystemClock.uptimeMillis())?.let(IncomingVoicemail::send)
    }

    override fun onServiceConnected() {
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!Prefs.of(this).getBoolean(ENABLED, false)) { reset(); return false }
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP) {
            // Do not repurpose the two-volume-key accessibility chord.
            if (event.action == KeyEvent.ACTION_DOWN) reset()
            return false
        }
        if (event.action == KeyEvent.ACTION_UP || event.isCanceled) {
            hold.up(event.deviceId)
            handler.removeCallbacks(trigger)
        } else if (event.action == KeyEvent.ACTION_DOWN &&
            hold.down(IncomingVoicemail.currentToken(), event.eventTime, event.deviceId, event.repeatCount)) {
            handler.postAtTime(trigger, event.eventTime + VolumeHold.HOLD_MS)
        }
        // Pass both down and up through: short presses keep the dialer's normal behavior.
        return false
    }

    private fun reset() { handler.removeCallbacks(trigger); hold.cancel() }
    override fun onInterrupt() = reset()
    override fun onDestroy() { reset(); super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    companion object { const val ENABLED = "shortcut.hold_volume_up" }
}

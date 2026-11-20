package app.ipick.voicemail.response

import android.content.Context
import android.telecom.Call
import android.telecom.TelecomManager
import app.ipick.voicemail.Prefs
import java.security.MessageDigest

internal object DndCalls {
    const val REPEATS = "response.allow_third_call"
    private const val HISTORY = "dnd_recent_calls"
    private const val WINDOW_MS = 15 * 60_000L

    fun repeatsEnabled(c: Context) = Prefs.of(c).getBoolean(REPEATS, true)

    @Synchronized
    fun allows(c: Context, details: Call.Details): Boolean {
        if (!StatusRepository.phoneDnd(c) || details.callDirection != Call.Details.DIRECTION_INCOMING ||
            details.handlePresentation != TelecomManager.PRESENTATION_ALLOWED) return false
        if (CallExceptions.matches(c, details.handle, CallExceptions.DND_KEY)) return true
        if (!repeatsEnabled(c)) return false
        val number = CallExceptions.normalized(c, details.handle) ?: return false
        val digest = MessageDigest.getInstance("SHA-256").digest(number.toByteArray())
        val caller = android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP)
        val current = details.creationTimeMillis
        val now = System.currentTimeMillis()
        if (current !in (now - WINDOW_MS)..now) return false
        val p = Prefs.of(c)
        val previous = p.getStringSet(HISTORY, emptySet()).orEmpty()
        val entry = "$current:$caller"
        val recent = previous.filter { (it.substringBefore(':').toLongOrNull() ?: 0L) in (now - WINDOW_MS)..now }.toMutableSet()
        recent.add(entry)
        if (recent != previous) p.edit().putStringSet(HISTORY, recent.sortedDescending().take(256).toSet()).apply()
        return recent.count { it.substringAfter(':') == caller && it.substringBefore(':').toLong() <= current } >= 3
    }

    fun setRepeats(c: Context, enabled: Boolean) {
        Prefs.of(c).edit().putBoolean(REPEATS, enabled).remove(HISTORY).apply()
    }
}

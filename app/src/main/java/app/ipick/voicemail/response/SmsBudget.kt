package app.ipick.voicemail.response

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import app.ipick.voicemail.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal object SmsBudget {
    private const val KEY = "sms_budget_v1"
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR
    private const val COOLDOWN = 10 * 60_000L

    @Synchronized
    fun reserve(c: Context, callId: String, number: String, segments: Int): String? {
        val prefs = Prefs.of(c)
        val recent = prefs.getString(StatusRepository.RECENT, "").orEmpty().split('|').filter { it.isNotEmpty() }
        if (callId in recent) return "This call already received an automatic reply."
        if (segments !in 1..3) return "The automatic reply is too long. Shorten the text and save it again."
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val boot = Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT, -1)
        val recipient = MessageDigest.getInstance("SHA-256").digest(number.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val kept = JSONArray()
        var hourly = 0
        var daily = 0
        var coolingDown = false
        try {
            val events = JSONArray(prefs.getString(KEY, "[]"))
            if (events.length() > 30) return "Automatic texts are paused because the sending history could not be read."
            for (i in 0 until events.length()) {
                val event = events.getJSONObject(i)
                val age = if (boot >= 0 && event.getInt("boot") == boot) elapsed - event.getLong("elapsed")
                    else now - event.getLong("time")
                val count = event.getInt("segments")
                if (count !in 1..3 || age < 0) return "Automatic texts are paused until the phone clock catches up with the last reply."
                if (age < DAY) {
                    kept.put(event)
                    daily += count
                    if (age < HOUR) hourly += count
                    if (age < COOLDOWN && event.getString("recipient") == recipient) coolingDown = true
                }
            }
        } catch (_: RuntimeException) {
            return "Automatic texts are paused because the sending history could not be read."
        } catch (_: org.json.JSONException) {
            return "Automatic texts are paused because the sending history could not be read."
        }
        if (coolingDown) return "This caller already received a reply in the last 10 minutes."
        if (hourly + segments > 10) return "Automatic texts are paused: the limit is 10 SMS parts per hour."
        if (daily + segments > 30) return "Automatic texts are paused: the limit is 30 SMS parts per 24 hours."
        kept.put(JSONObject().put("time", now).put("elapsed", elapsed).put("boot", boot)
            .put("recipient", recipient).put("segments", segments))
        return if (prefs.edit().putString(KEY, kept.toString())
                .putString(StatusRepository.RECENT, (recent.takeLast(31) + callId).joinToString("|")).commit()) null
            else "The SMS sending limit could not be saved. No reply was sent."
    }
}

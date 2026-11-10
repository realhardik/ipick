package app.ipick.voicemail.answering

import android.content.Context
import app.ipick.voicemail.Prefs
import app.ipick.voicemail.response.Availability

/** Profile and editable scripts are separate from the saved audio callers hear. */
object GreetingPreferences {
    fun name(c: Context) = Prefs.of(c).getString("profile.name", "").orEmpty()
    fun setName(c: Context, value: String) {
        Prefs.of(c).edit().putString("profile.name", value.trim().take(50)).apply()
    }

    fun defaultText(c: Context, status: Availability): String {
        val name = name(c)
        val intro = if (name.isBlank()) "" else "You’ve reached $name. "
        return when (status) {
            Availability.IDLE -> intro + "I can’t take your call right now. Please leave a message after the tone."
            Availability.DND -> intro + "I’m unavailable at the moment. Please leave a message and I’ll get back to you."
            Availability.CUSTOM -> intro + "Please leave your name and a message after the tone. I’ll get back to you when I can."
        }
    }

    fun text(c: Context, status: Availability): String {
        val prefs = Prefs.of(c)
        prefs.getString("greeting.text.${status.name}", null)?.let { return it }
        // 0.4 stored a name in DND's input, but a full script in Custom's input.
        val old = prefs.getString("voice.input.${status.name}", "").orEmpty()
        return when {
            status == Availability.DND && old.isNotBlank() ->
                "You’ve reached ${old.trim()}. I can’t come to the phone right now. Please leave a message after the tone."
            status == Availability.CUSTOM && old.isNotBlank() -> old
            else -> defaultText(c, status)
        }
    }

    fun setText(c: Context, status: Availability, value: String) {
        Prefs.of(c).edit().putString("greeting.text.${status.name}", value.take(260)).apply()
    }
}

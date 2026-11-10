package app.ipick.voicemail.response

enum class Availability(val title: String, val defaultReply: String) {
    IDLE("Idle", "I'm away from my phone."),
    DND("DND", "I'm busy right now. What's up?"),
    CUSTOM("Custom", "");
}

enum class ResponseMode { VOICEMAIL, AUTO_TEXT }

object ResponsePolicy {
    fun destination(raw: String?): String? = raw?.replace(Regex("[ ()-]"), "")
        ?.takeIf { it.matches(Regex("\\+?[0-9]{7,15}")) }
}

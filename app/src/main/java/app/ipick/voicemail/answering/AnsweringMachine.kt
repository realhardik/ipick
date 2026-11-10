package app.ipick.voicemail.answering

import app.ipick.voicemail.response.StatusRepository
import android.content.Context
import androidx.core.content.edit
import app.ipick.voicemail.Prefs

object AnsweringMachine {

    private const val KEY_ON = "answer_on"
    private const val KEY_DELAY = "answer_delay_s"
    private const val KEY_DND_SHORT = "answer_dnd_short"
    private const val KEY_LONGEST = "answer_longest_s"
    private const val KEY_SIM = "answer_sim"
    private const val KEY_ROUTE = "answer_route"
    private const val KEY_SCREEN_OFF = "answer_screen_off"

    val DELAYS = listOf(5, 10, 15, 20, 25, 30)
    const val DEFAULT_DELAY = 20

    /** About two rings: Do Not Disturb would silence the call anyway. */
    const val DND_DELAY = 5

    fun effectiveDelay(configured: Int, shortenForDnd: Boolean, silenced: Boolean): Int =
        if (shortenForDnd && silenced) minOf(DND_DELAY, configured) else configured

    /** The longest message, in seconds; [NO_LIMIT] runs until the caller
     *  hangs up, or has been silent half a minute (the recorder's
     *  AnswerMachine.SILENCE_HANGUP_MS). */
    const val NO_LIMIT = 0
    const val SILENCE_HANGUP_SECONDS = 30
    val LONGEST = listOf(30, 60, 120, NO_LIMIT)
    const val DEFAULT_LONGEST = 60

    /** Which SIM's calls it answers, with two: the default SIM for calls
     *  until the user picks; "both"; or one SIM's phone-account id. */
    const val SIM_DEFAULT = "default"
    const val SIM_BOTH = "both"

    fun enabled(context: Context): Boolean = !StatusRepository.autoText(context) && Prefs.of(context).getBoolean(KEY_ON, false)

    fun setEnabled(context: Context, on: Boolean) {
        Prefs.of(context).edit { putBoolean(KEY_ON, on) }
    }

    fun delay(context: Context): Int =
        Prefs.of(context).getInt(KEY_DELAY, DEFAULT_DELAY).takeIf { it in DELAYS } ?: DEFAULT_DELAY

    /** The app's DND status responds immediately in either response mode. */
    fun responseDelay(context: Context): Int =
        if (StatusRepository.read(context).status == app.ipick.voicemail.response.Availability.DND) 0 else delay(context)

    fun setDelay(context: Context, seconds: Int) {
        require(seconds in DELAYS)
        Prefs.of(context).edit { putInt(KEY_DELAY, seconds) }
    }

    /** With Do Not Disturb silencing the call: after about two rings (true,
     *  the default), or the same delay as always. */
    fun dndShort(context: Context): Boolean = Prefs.of(context).getBoolean(KEY_DND_SHORT, true)

    fun setDndShort(context: Context, short: Boolean) {
        Prefs.of(context).edit { putBoolean(KEY_DND_SHORT, short) }
    }

    fun longest(context: Context): Int =
        Prefs.of(context).getInt(KEY_LONGEST, DEFAULT_LONGEST).takeIf { it in LONGEST } ?: DEFAULT_LONGEST

    fun setLongest(context: Context, seconds: Int) {
        require(seconds in LONGEST)
        Prefs.of(context).edit { putInt(KEY_LONGEST, seconds) }
    }

    fun screenOff(context: Context): Boolean = Prefs.of(context).getBoolean(KEY_SCREEN_OFF, false)

    fun setScreenOff(context: Context, on: Boolean) {
        Prefs.of(context).edit { putBoolean(KEY_SCREEN_OFF, on) }
    }

    fun sim(context: Context): String = Prefs.of(context).getString(KEY_SIM, SIM_DEFAULT) ?: SIM_DEFAULT

    fun setSim(context: Context, sim: String) {
        Prefs.of(context).edit { putString(KEY_SIM, sim) }
    }

    fun route(context: Context): String = Prefs.of(context).getString(KEY_ROUTE, ROUTE_MUSIC) ?: ROUTE_MUSIC

    fun setRoute(context: Context, route: String) {
        require(route == ROUTE_MUSIC || route == ROUTE_SCREENING)
        Prefs.of(context).edit { putString(KEY_ROUTE, route) }
    }

    const val ROUTE_MUSIC = "music"
    const val ROUTE_SCREENING = "screening"

    fun wire(context: Context): String = if (StatusRepository.autoText(context)) "text" else wire(
        on = enabled(context) && Greeting.present(context),
        longest = longest(context),
        route = route(context),
        screenOff = screenOff(context),
    )

    internal fun wire(on: Boolean, longest: Int, route: String, screenOff: Boolean = false): String =
        "${if (on) 1 else 0},$longest,$route" + if (screenOff) ",1" else ""

    /** What is known about a ringing call and the phone, for [decide]. */
    data class Ringing(
        val enabled: Boolean,
        val greeting: Boolean,
        val recorderOn: Boolean,
        val trackingOn: Boolean,
        /** Other calls Telecom has besides this one: call waiting. */
        val otherCalls: Int,
        val roaming: Boolean,
        /** The rules on whose calls to keep would keep this caller: true,
         *  false, or null when the caller could not be identified. */
        val rulesKeep: Boolean?,
        /** Do Not Disturb would silence this call. */
        val dndSilences: Boolean,
        val delay: Int,
        val dndShort: Boolean,
        /** The call is on a SIM this answers on. */
        val simAllowed: Boolean,
        /** Another app is in a call - a messenger, VoIP, a meeting - which
         *  answering would take the user out of, and the mute silence. The
         *  recorder asks again, seeing more than this app can. */
        val otherAppCall: Boolean = false,
        /** The call is marked as an emergency service calling back. */
        val emergencyCallback: Boolean = false,
        /** The user called an emergency number within a day (EmergencyPause). */
        val emergencyPaused: Boolean = false,
    )

    /** The go-ahead to send, or why there is none. */
    sealed interface Decision {
        data class Answer(val delaySeconds: Int) : Decision
        data class No(val why: String) : Decision
    }

    /**
     * Answer a ringing call, and after how long? Pure, for the tests. Every
     * "no" is the safe side: the call rings on, as if IPick were not there.
     */
    fun decide(r: Ringing): Decision = when {
        !r.enabled -> Decision.No("switched off")
        !r.greeting -> Decision.No("no greeting recorded")
        !r.recorderOn -> Decision.No("recording is switched off")
        !r.trackingOn -> Decision.No("exact call tracking is off")
        r.otherCalls > 0 -> Decision.No("another call is up")
        r.otherAppCall -> Decision.No("a messenger or meeting call is up")
        r.emergencyCallback -> Decision.No("an emergency service may be calling back")
        r.emergencyPaused -> Decision.No("paused after an emergency call")
        r.roaming -> Decision.No("roaming")
        !r.simAllowed -> Decision.No("not a SIM it answers on")
        r.rulesKeep == null -> Decision.No("the caller could not be identified")
        !r.rulesKeep -> Decision.No("whose calls to keep leaves this caller out")
        else -> Decision.Answer(effectiveDelay(r.delay, r.dndShort, r.dndSilences))
    }
}

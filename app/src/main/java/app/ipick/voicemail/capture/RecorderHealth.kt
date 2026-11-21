package app.ipick.voicemail.capture

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import app.ipick.voicemail.DeepLink
import app.ipick.voicemail.MainActivity
import app.ipick.voicemail.Prefs
import app.ipick.voicemail.adb.AdbTransport
import app.ipick.voicemail.calls.AfterCall

internal object RecorderHealth {

    private const val TAG = "IPick"
    private const val NOTIFICATION_ID = 34
    private const val KEY_DOWN_SINCE = "recorder_down_since"
    private const val KEY_ALERTED = "recorder_down_alerted"
    private const val KEY_REFUSED = "adb_refused_in_a_row"
    private const val KEY_CAUSE = "recorder_down_cause"

    /** Two refusals in a row from an adbd that is plainly there: the pairing
     *  is the likeliest reason, not a hiccup. */
    internal const val REFUSALS_FOR_PAIRING = 2

    enum class Problem { NEEDS_WIFI, NETWORK_NOT_ALLOWED, CANNOT_CONNECT, CANNOT_START }

    /** Called with the outcome of every revive - [connect], what its own
     *  connect found (CaptureDaemon.revive). Cheap; never throws. */
    fun onRevive(context: Context, alive: Boolean, connect: AdbTransport.ConnectOutcome) = runCatching {
        val prefs = Prefs.of(context)
        if (alive || !RecorderSwitch.isOn(context) || !CaptureDaemon.isSetUp(context)) {
            clear(context)
            return@runCatching
        }
        val refused = when (connect) {
            AdbTransport.ConnectOutcome.REFUSED -> prefs.getInt(KEY_REFUSED, 0) + 1
            AdbTransport.ConnectOutcome.NONE -> prefs.getInt(KEY_REFUSED, 0)
            else -> 0
        }
        prefs.edit { putInt(KEY_REFUSED, refused) }
        val unadvertised = connect == AdbTransport.ConnectOutcome.NOT_FOUND &&
            notAllowed(
                refusedHere = NetworkRevive.wirelessRefusedHere(context),
                switchOn = GlobalSettings.isOn(context, GlobalSettings.ADB_WIFI_ENABLED),
                adbd = AdbdState.read(),
                switchedOnHere = NetworkRevive.switchedOnHere(context),
            )
        val problem = diagnose(NetworkRevive.onWifi(context), refused, unadvertised)
        report(context, problem)
    }.onFailure { Log.w(TAG, "health: could not judge the recorder", it) }

    @Synchronized
    private fun report(context: Context, problem: Problem) {
        if (!RecorderSwitch.isOn(context) || !CaptureDaemon.isSetUp(context)) return
        val prefs = Prefs.of(context)
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        val alert = "$boot/${problem.name}"
        prefs.edit { putString(KEY_CAUSE, problem.name) }
        if (prefs.getString(KEY_ALERTED, null) != alert && post(context, problem)) {
            prefs.edit { putString(KEY_ALERTED, alert) }
        }
    }

    /**
     * Nothing advertised, and this Wi-Fi not allowed the reason: Android
     * refused it here, or the switch is on and adbd runs. With adbd's state
     * unreadable, only once a revive has switched it off and on here - the
     * one try that would have started an adbd not running (CaptureDaemon's
     * cycleAfterNotFound). Never with adbd stopped: no Wi-Fi question
     * explains that, and a revive that switched it off and on and still
     * found it stopped has no name for why.
     */
    internal fun notAllowed(
        refusedHere: Boolean,
        switchOn: Boolean,
        adbd: AdbdState.State,
        switchedOnHere: Boolean,
    ): Boolean = when {
        refusedHere -> true
        !switchOn -> false
        else -> when (adbd) {
            AdbdState.State.RUNNING -> true
            AdbdState.State.STOPPED -> false
            AdbdState.State.UNKNOWN -> switchedOnHere
        }
    }

    /**
     * unadvertised: on Wi-Fi, the revive found no advertisement to connect
     * to, and [notAllowed].
     */
    internal fun diagnose(onWifi: Boolean, refusedInARow: Int, unadvertised: Boolean = false): Problem = when {
        !onWifi -> Problem.NEEDS_WIFI
        refusedInARow >= REFUSALS_FOR_PAIRING -> Problem.CANNOT_CONNECT
        unadvertised -> Problem.NETWORK_NOT_ALLOWED
        else -> Problem.CANNOT_START
    }

    /** Why the recorder is down, from the last revive that failed; null while
     *  it is up, switched off, or not set up. */
    fun cause(context: Context): Problem? {
        val prefs = Prefs.of(context)
        return prefs.getString(KEY_CAUSE, null)?.let { name -> Problem.entries.firstOrNull { it.name == name } }
    }

    private fun post(context: Context, problem: Problem): Boolean {
        val (text, button) = when (problem) {
            Problem.NEEDS_WIFI ->
                "Connect to Wi-Fi so IPick can restart voicemail." to null
            Problem.NETWORK_NOT_ALLOWED ->
                "Allow wireless debugging on this Wi-Fi to reconnect voicemail." to
                    ("Allow this Wi-Fi" to DeepLink.intent(context, DeepLink.Kind.ALLOW_WIFI))
            Problem.CANNOT_CONNECT ->
                "Check the wireless debugging pairing to reconnect voicemail." to
                    ("Pair again" to DeepLink.intent(context, DeepLink.Kind.PAIR_AGAIN))
            Problem.CANNOT_START ->
                "Open IPick to check the voicemail connection." to null
        }
        val open = PendingIntent.getActivity(
            context, 3401,
            button?.second ?: Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = AfterCall.problem(context, "Voicemail helper offline",
            "IPick is on. $text SMS replies work independently of the helper.", open, button?.first, collapsed = text)
            .setOnlyAlertOnce(true)
            .build()
        return AfterCall.notifyProblem(context, NOTIFICATION_ID, notification, "health")
    }

    @Synchronized
    fun clear(context: Context) {
        val prefs = Prefs.of(context)
        if (!prefs.contains(KEY_CAUSE) && !prefs.contains(KEY_ALERTED) &&
            !prefs.contains(KEY_DOWN_SINCE) && !prefs.contains(KEY_REFUSED)) return
        prefs.edit {
            remove(KEY_REFUSED)
            remove(KEY_DOWN_SINCE)
            remove(KEY_ALERTED)
            remove(KEY_CAUSE)
        }
        AfterCall.cancelProblem(context, NOTIFICATION_ID)
    }
}

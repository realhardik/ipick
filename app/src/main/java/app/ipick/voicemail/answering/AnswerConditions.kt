package app.ipick.voicemail.answering

import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.edit
import app.ipick.voicemail.Prefs
import app.ipick.voicemail.capture.CaptureDaemon

/**
 * Two things about a ringing call the answering machine's decision needs from
 * the phone: whether Do Not Disturb would silence it, and whether it is on a
 * SIM the machine answers on.
 */
internal object AnswerConditions {

    private const val KEY_DEFAULT_SIM = "answer_default_sim"
    private const val KEY_SIM_COUNT = "answer_sim_count"

    /**
     * Whether a call on phone account [account] is one to answer, by the
     * user's "Answer on" choice. With one SIM - or an account Telecom did not
     * name - always: the choice only exists with two.
     */
    fun simAllowed(context: Context, account: String?): Boolean {
        if (account == null || simCount(context) < 2) return true
        return when (val sim = AnsweringMachine.sim(context)) {
            AnsweringMachine.SIM_BOTH -> true
            AnsweringMachine.SIM_DEFAULT -> defaultSim(context)?.let { it == account } ?: true
            else -> sim == account
        }
    }

    /** What the recorder said about the SIMs last time anyone asked - the
     *  settings screen does, each time it opens, and the call tracker at
     *  each ring it decides on. */
    fun remember(context: Context, sims: CaptureDaemon.Sims) {
        Prefs.of(context).edit {
            putString(KEY_DEFAULT_SIM, sims.defaultId)
            putInt(KEY_SIM_COUNT, sims.all.size)
        }
    }

    fun defaultSim(context: Context): String? = Prefs.of(context).getString(KEY_DEFAULT_SIM, null)

    fun simCount(context: Context): Int = Prefs.of(context).getInt(KEY_SIM_COUNT, 1)
}

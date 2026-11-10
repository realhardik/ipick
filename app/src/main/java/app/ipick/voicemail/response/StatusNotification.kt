package app.ipick.voicemail.response

import android.app.*
import android.content.*
import app.ipick.voicemail.DeepLink
import app.ipick.voicemail.Prefs
import app.ipick.voicemail.answering.AnsweringMachine
import app.ipick.voicemail.capture.RecorderSwitch
import app.ipick.voicemail.ui.Setup

object StatusNotification {
    private const val ID = 4702
    private const val CHANNEL = "ipick.status"
    private val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    private var watcher: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun start(context: Context) {
        val app = context.applicationContext
        watcher = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith(StatusRepository.PREFIX) || key in setOf("answer_delay_s","answer_on","setup_complete","recorder_enabled"))
                post(app)
        }.also { Prefs.of(app).registerOnSharedPreferenceChangeListener(it) }
        post(app)
    }

    fun post(c: Context) {
        val manager = c.getSystemService(NotificationManager::class.java)
        if (!Setup.isComplete(c) || !manager.areNotificationsEnabled()) return
        val s = StatusRepository.read(c)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Current status", NotificationManager.IMPORTANCE_LOW))
        fun open(editor: Boolean) = PendingIntent.getActivity(c, if (editor) 4704 else 4703,
            DeepLink.intent(c, if (editor) DeepLink.Kind.CUSTOM_REPLY else DeepLink.Kind.STATUS), flags)
        val delay = AnsweringMachine.responseDelay(c)
        val description = when {
            !RecorderSwitch.isOn(c) -> "Call response paused"
            StatusRepository.autoText(c) && !AutoText.allowed(c) -> "Auto-text needs phone and SMS permissions"
            StatusRepository.autoText(c) -> if (delay == 0) "Auto-text immediately" else "Auto-text after $delay seconds"
            AnsweringMachine.enabled(c) -> if (delay == 0) "Voicemail immediately" else "Voicemail after $delay seconds"
            else -> "Voicemail is off"
        }
        val b = Notification.Builder(c, CHANNEL).setSmallIcon(app.ipick.voicemail.R.drawable.ic_tile)
            .setContentTitle("Current status · ${s.status.title}").setContentText(description)
            .setContentIntent(open(false)).setOnlyAlertOnce(true).setOngoing(true)
        for (status in Availability.entries) {
            val action = if (s.mode == ResponseMode.AUTO_TEXT && status == Availability.CUSTOM && s.custom.isBlank()) open(true)
                else PendingIntent.getBroadcast(c, status.ordinal,
                    Intent(c, StatusAction::class.java).setAction(status.name), flags)
            b.addAction(Notification.Action.Builder(null, status.title, action).build())
        }
        manager.notify(ID, b.build())
    }
}

class StatusAction : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Availability.entries.firstOrNull { it.name == intent.action }?.let { StatusRepository.choose(context, it) }
    }
}

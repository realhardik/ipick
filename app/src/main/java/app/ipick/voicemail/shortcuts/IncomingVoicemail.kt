package app.ipick.voicemail.shortcuts

import android.app.*
import android.content.*
import app.ipick.voicemail.R

/** Main-thread registry shared by the key shortcut and the immutable notification action. */
object IncomingVoicemail {
    private const val ID = 4710
    private const val CHANNEL = "ipick.incoming-action"
    private var token: String? = null
    private var action: (() -> Unit)? = null
    fun currentToken() = token

    fun show(c: Context, id: String, send: () -> Unit) {
        token = id
        action = send
        val manager = c.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Incoming call actions", NotificationManager.IMPORTANCE_HIGH)
            .apply { setSound(null, null); enableVibration(false) })
        if (!manager.areNotificationsEnabled()) return
        val intent = Intent(c, SendToVoicemailReceiver::class.java).setAction(id)
        val pending = PendingIntent.getBroadcast(c, 4710, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Incoming call")
            .setContentText("Send this call to your voicemail greeting.")
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Send to voicemail", pending).build())
            .build()
        manager.notify(ID, notification)
    }

    fun clear(c: Context, id: String?) {
        if (id == null || id != token) return
        token = null
        action = null
        c.getSystemService(NotificationManager::class.java).cancel(ID)
    }

    fun send(id: String) {
        if (id != token) return
        action?.invoke()
    }
}

class SendToVoicemailReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.action?.let(IncomingVoicemail::send)
    }
}

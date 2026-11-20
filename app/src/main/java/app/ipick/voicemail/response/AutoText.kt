package app.ipick.voicemail.response

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.telecom.Call
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import app.ipick.voicemail.answering.AnswerConditions
import app.ipick.voicemail.answering.EmergencyPause
import app.ipick.voicemail.calls.CallTracking

object AutoText {
    private val receipts = SmsSendReceipt<Call>()
    private val submitted = mutableSetOf<Call>()
    fun wasSubmitted(call: Call) = call in submitted

    private const val KEY = "reply_key"
    private const val PART = "part_index"

    fun forget(call: Call) { receipts.forget(call); submitted.remove(call) }

    fun sentResult(c: Context, intent: Intent, code: Int) {
        val success = code == Activity.RESULT_OK
        if (!success) StatusRepository.failure(c, "SMS failed on the mobile network (code $code).")
        val key = intent.getStringExtra(KEY) ?: return
        val generation = StatusRepository.generation(c)
        val call = receipts.result(key, intent.getIntExtra(PART, -1), success, generation) ?: return
        if (CallExceptions.matches(c, call.details.handle) || DndCalls.allows(c, call.details)) return
        if (call.details.state != Call.STATE_RINGING || !StatusRepository.smsEnabled(c) ||
            !CallTracking.enabled(c)) return
        try {
            call.reject(Call.REJECT_REASON_DECLINED)
        } catch (ex: RuntimeException) {
            StatusRepository.failure(c, "SMS sent, but the call could not be declined.")
        }
    }

    val permissions = arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE)
    fun allowed(c: Context) = permissions.all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Called on Telecom's main thread, with the same Call object that originally rang. */
    fun send(c: Context, call: Call, generation: Long) {
        if (wasSubmitted(call)) return
        val d = call.details
        if (CallExceptions.matches(c, d.handle) || DndCalls.allows(c, d)) return
        if (!StatusRepository.smsEnabled(c) || StatusRepository.generation(c) != generation ||
            !CallTracking.enabled(c) ||
            d.state != Call.STATE_RINGING || d.callDirection != Call.Details.DIRECTION_INCOMING ||
            d.handlePresentation != TelecomManager.PRESENTATION_ALLOWED || d.handle?.scheme != "tel" ||
            !AnswerConditions.simAllowed(c, d.accountHandle?.id) || EmergencyPause.isCallback(d) ||
            EmergencyPause.pausedUntil(c, EmergencyPause.telecomLast(d)) != null) return
        val number = ResponsePolicy.destination(d.handle.schemeSpecificPart) ?: return
        if (EmergencyPause.isEmergencyNumber(c, number)) return
        if (!allowed(c)) { StatusRepository.failure(c, "SMS or phone permission is missing."); return }
        try {
            val sub = c.getSystemService(TelephonyManager::class.java).createForPhoneAccountHandle(d.accountHandle)
                ?: error("No matching mobile line")
            val id = sub.subscriptionId
            if (id < 0 || !sub.isSmsCapable) error("SMS unavailable")
            val message = StatusRepository.read(c).reply
            if (message.isBlank()) return
            val manager = c.getSystemService(SmsManager::class.java).createForSubscriptionId(id)
            val parts = manager.divideMessage(message)
            val callKey = "${id}_${d.creationTimeMillis}"
            if (parts.isEmpty()) return
            val canonical = android.telephony.PhoneNumberUtils.formatNumberToE164(number,
                sub.simCountryIso.uppercase(java.util.Locale.ROOT))
            if (canonical == null) {
                StatusRepository.failure(c, "The caller's number could not be verified for an automatic text.")
                return
            }
            val blocked = SmsBudget.reserve(c, callKey, canonical, parts.size)
            if (blocked != null) {
                StatusRepository.failure(c, blocked)
                return
            }
            val sent = ArrayList(parts.indices.map { index ->
                val intent = Intent(c, SmsResult::class.java).setAction("ipick.sms.$callKey.$index")
                    .putExtra(KEY, callKey).putExtra(PART, index)
                PendingIntent.getBroadcast(c, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
            })
            submitted += call
            receipts.start(callKey, call, generation, parts.size)
            StatusRepository.failure(c, null)
            if (parts.size == 1) manager.sendTextMessage(number, null, message, sent.single(), null)
            else manager.sendMultipartTextMessage(number, null, parts, sent, null)
        } catch (ex: RuntimeException) {
            forget(call)
            StatusRepository.failure(c, "Could not submit the text on the incoming SIM. Check SMS permissions and service.")
        }
    }
}

class SmsResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AutoText.sentResult(context, intent, resultCode)
    }
}

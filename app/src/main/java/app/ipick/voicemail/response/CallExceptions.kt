package app.ipick.voicemail.response

import android.content.Context
import android.net.Uri
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import app.ipick.voicemail.Prefs
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Shared exclusion policy for voicemail, SMS and explicit ringing-call shortcuts. */
object CallExceptions {
    const val KEY = "response.exception_numbers"
    const val DND_KEY = "response.dnd_exception_numbers"
    data class Entry(val number: String, val name: String)

    private fun country(c: Context): String {
        val phone = c.getSystemService(TelephonyManager::class.java)
        return (runCatching { phone.networkCountryIso }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { phone.simCountryIso }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: Locale.getDefault().country).uppercase(Locale.ROOT)
    }

    private fun canonical(raw: String, country: String): String? {
        val input = raw.trim()
        if (!input.matches(Regex("[+0-9(). \\-]+"))) return null
        val number = PhoneNumberUtils.normalizeNumber(input)
        val international = PhoneNumberUtils.formatNumberToE164(number, country)
        return international ?: number.takeIf { it.matches(Regex("\\+[1-9][0-9]{6,14}")) }
    }

    fun read(c: Context, key: String = KEY): List<Entry> = runCatching {
        val array = JSONArray(Prefs.of(c).getString(key, "[]"))
        (0 until array.length()).map { index ->
            val value = array.getJSONObject(index)
            Entry(value.getString("number"), value.optString("name"))
        }
    }.getOrDefault(emptyList())

    @Synchronized fun add(c: Context, number: String, name: String, key: String = KEY) {
        val normalized = canonical(number, country(c))
            ?: throw IllegalArgumentException("Enter a valid phone number with its country code, such as +91…")
        val updated = read(c, key).filterNot { it.number == normalized } + Entry(normalized, name.trim().take(80))
        write(c, key, updated)
    }

    @Synchronized fun remove(c: Context, numbers: Set<String>, key: String) = write(c, key, read(c, key).filterNot { it.number in numbers })

    private fun write(c: Context, key: String, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("number", it.number).put("name", it.name)) }
        Prefs.of(c).edit().putString(key, array.toString()).apply()
    }

    fun normalized(c: Context, handle: Uri?): String? =
        if (handle?.scheme == "tel") canonical(handle.schemeSpecificPart, country(c)) else null

    fun matches(c: Context, handle: Uri?, key: String = KEY): Boolean {
        if (handle?.scheme != "tel") return false
        val entries = read(c, key)
        if (entries.isEmpty()) return false
        val number = canonical(handle.schemeSpecificPart, country(c)) ?: return false
        // Full normalized numbers only: a shared seven-digit suffix is not a match.
        return entries.any { it.number == number }
    }

    fun watch(c: Context, key: String = KEY) = callbackFlow {
        val stop = Prefs.watch(c, key) { trySend(read(c, key)) }
        trySend(read(c, key))
        awaitClose { stop() }
    }.distinctUntilChanged()
}

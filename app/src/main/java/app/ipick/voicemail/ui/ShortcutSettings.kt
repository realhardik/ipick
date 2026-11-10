package app.ipick.voicemail.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.ipick.voicemail.Prefs
import app.ipick.voicemail.shortcuts.VolumeShortcutService

@Composable
internal fun ShortcutSettings() {
    val c = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var enabled by remember { mutableStateOf(Prefs.of(c).getBoolean(VolumeShortcutService.ENABLED, false)) }
    fun readAccessEnabled(): Boolean = c.getSystemService(AccessibilityManager::class.java)
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.let { s -> s.packageName == c.packageName && s.name == VolumeShortcutService::class.java.name } }
    var connected by remember { mutableStateOf(readAccessEnabled()) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) connected = readAccessEnabled()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Text("Call shortcuts", fontWeight = FontWeight.Bold)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Hold Volume Up", Modifier.weight(1f))
        Switch(enabled, { enabled = it; Prefs.of(c).edit().putBoolean(VolumeShortcutService.ENABLED, it).apply() })
    }
    Text("Hold for one second while a single incoming call is ringing to send it to voicemail. Also works as a one-call override when Auto-text is selected.",
        style = MaterialTheme.typography.bodySmall)
    if (enabled) {
        Text(if (connected) "Accessibility service enabled. Button delivery while locked depends on your phone."
            else "Enable Voicemail volume shortcut in Android’s Accessibility settings.")
        TextButton(onClick = {
            c.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }) { Text("Open shortcut access") }
    }
    Text("Normal button events still reach Android, so the ringtone may also be silenced. The incoming-call notification provides the same action.",
        style = MaterialTheme.typography.bodySmall)
}

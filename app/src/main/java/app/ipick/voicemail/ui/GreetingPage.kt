package app.ipick.voicemail.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ipick.voicemail.Prefs
import app.ipick.voicemail.answering.GreetingPreferences
import app.ipick.voicemail.answering.KokoroAudio
import app.ipick.voicemail.response.*
import kotlinx.coroutines.delay

@Composable
internal fun GreetingPage(modifier: Modifier, a: AnswerUi, current: StatusState, vm: MainViewModel) {
    val ctl = vm.answer
    val context = LocalContext.current
    val status = a.editing
    val prefs = remember(context) { Prefs.of(context) }
    val busy = a.generating || a.savingGreeting || a.recordingTake
    var draft by rememberSaveable(status) { mutableStateOf(GreetingPreferences.text(context, status)) }
    val voices = remember { mapOf("af_heart" to "Heart · US", "am_michael" to "Michael · US",
        "bf_emma" to "Emma · UK", "bm_george" to "George · UK") }
    var voice by rememberSaveable(status) { mutableStateOf(prefs.getString("voice.id.${status.name}", "af_heart") ?: "af_heart") }
    var voiceMenu by remember { mutableStateOf(false) }
    var recordMode by rememberSaveable(status) { mutableStateOf(false) }
    var elapsed by remember { mutableIntStateOf(0) }
    LaunchedEffect(a.recordingTake, a.takeStartedAt) {
        while (a.recordingTake) {
            elapsed = ((System.currentTimeMillis() - a.takeStartedAt) / 1000).toInt().coerceIn(0, 30)
            delay(250)
        }
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Greetings", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("What callers hear before leaving a message.")
        Text(if (current.mode == ResponseMode.AUTO_TEXT) "Auto-text is on. Enable voicemail in Settings to use greetings."
            else "Calls currently use your ${current.status.title} greeting.", color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Availability.entries.forEach { value ->
                FilterChip(selected = status == value, onClick = { ctl.edit(value) }, enabled = !busy,
                    label = { Text(value.title) })
            }
        }
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Saved for ${status.title}", style = MaterialTheme.typography.labelLarge)
                Text(a.greetingSource, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("${a.greetingSeconds?.toInt() ?: 0} seconds · Plays offline")
                if (status != current.status) Text("To use this for incoming calls, select ${status.title} in Settings.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = ctl::togglePreview, enabled = !busy) {
                    Text(if (a.playing) "Stop listening" else "Listen to saved greeting")
                }
            }
        }
        a.greetingProblem?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Text("Change this greeting", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!recordMode, { recordMode = false }, { Text("Type a message") }, enabled = !busy)
            FilterChip(recordMode, { recordMode = true }, { Text("Record my voice") }, enabled = !busy)
        }
        if (recordMode) {
            Text(if (a.recordingTake) "Recording · ${elapsed}s / 30s" else "Record up to 30 seconds. Let callers know they can leave a message after the tone.")
            Button(onClick = { if (a.recordingTake) ctl.stopTake() else ctl.startTake() },
                enabled = !a.generating && !a.savingGreeting, modifier = Modifier.fillMaxWidth()) {
                Text(if (a.recordingTake) "Stop and save recording" else if (a.savingGreeting) "Saving…" else "Start recording")
            }
            Text("A saved recording replaces the audio for ${status.title}.", style = MaterialTheme.typography.bodySmall)
        } else {
            OutlinedTextField(value = draft, onValueChange = {
                if (it.length <= 260) { draft = it; GreetingPreferences.setText(context, status, it) }
            }, label = { Text("${status.title} message") }, supportingText = { Text("${draft.length}/260") },
                enabled = !busy, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = {
                draft = GreetingPreferences.defaultText(context, status)
                GreetingPreferences.setText(context, status, draft)
            }, enabled = !busy) { Text("Use default message") }
            Box {
                OutlinedButton(onClick = { voiceMenu = true }, enabled = !busy) { Text("Voice · ${voices[voice] ?: "Heart · US"}") }
                DropdownMenu(voiceMenu, { voiceMenu = false }) {
                    voices.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = {
                        voice = id
                        prefs.edit().putString("voice.id.${status.name}", id).apply()
                        voiceMenu = false
                    }) }
                }
            }
            Text("Editing text won’t change the saved audio until generation succeeds.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { ctl.generate(draft.trim(), voice, draft) }, enabled = !busy && draft.isNotBlank(),
                modifier = Modifier.fillMaxWidth()) {
                Text(if (a.generating) "Generating · up to ${KokoroAudio.TIMEOUT_SECONDS}s" else if (a.savingGreeting) "Saving…" else "Generate and save")
            }
            if (a.generating) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = ctl::cancelGeneration) { Text("Cancel generation") }
            }
            Text("Generation sends this text to a community voice service on Hugging Face. Your saved audio stays on this phone.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun ProfileSettings() {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf(GreetingPreferences.name(context)) }
    Text("Your profile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    OutlinedTextField(value = name, onValueChange = {
        if (it.length <= 50) { name = it; GreetingPreferences.setName(context, it) }
    }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        supportingText = { Text("Used when you choose a default message. Regenerate a greeting to update its audio.") })
}

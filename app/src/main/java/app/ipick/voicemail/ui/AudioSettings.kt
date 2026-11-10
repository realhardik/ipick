package app.ipick.voicemail.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment

@Composable
internal fun AudioSettings(a: AnswerUi, autoText: Boolean, vm: MainViewModel) {
    val ctl = vm.answer
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Turn screen off during voicemail", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
        Switch(checked = a.screenOff, onCheckedChange = ctl::setScreenOff)
    }
    Text("Turns off once the greeting starts. Wake your phone normally to take over. Your usual screen lock still applies.",
        style = MaterialTheme.typography.bodySmall)
        Text("Phone compatibility", fontWeight = FontWeight.Bold)
        Text(a.stageOne?.problem ?: if (a.stageOne?.possible == true)
            "This phone passed the initial checks. A real call verifies the audio route." else "Check this phone’s voicemail audio.")
        TextButton(onClick = ctl::runChecks, enabled = !a.checking) { Text(if (a.checking) "Checking…" else "Check audio support") }
        if (!autoText && a.stageOne?.possible == true && a.greetingSeconds != null) {
            OutlinedButton(onClick = { ctl.startTest(incoming = true) }, enabled = a.test == null && !a.generating && !a.savingGreeting && !a.recordingTake) {
                Text(if (a.test == null) "Test next incoming call" else "Incoming call test · ${a.test}")
            }
            Text("A test answers the next incoming call. Call from a second phone when ready.", style = MaterialTheme.typography.bodySmall)
        }
        a.askHeard?.let { type ->
            Text("Did the caller hear the greeting?")
            Row { TextButton({ ctl.heard(type, true) }) { Text("Yes") }; TextButton({ ctl.heard(type, false) }) { Text("No") } }
        }
        if (a.sims.size > 1) {
            Text("Answer on", fontWeight = FontWeight.Bold)
            (listOf("both" to "Both SIMs", "default" to "Default SIM") + a.sims).forEach { (id, label) ->
                TextButton(onClick = { ctl.setSim(id) }) { Text((if (a.sim == id) "✓  " else "") + label) }
            }
        }
        TextButton(onClick = vm::copyCallReport) { Text("Copy last-call report") }
        Text("While the phone is locked, use Talk to them in the voicemail notification to take over a call.",
            style = MaterialTheme.typography.bodySmall)
        a.results.forEach { (type, verdict) -> Text("$type · ${verdict.name.lowercase()}") }

}

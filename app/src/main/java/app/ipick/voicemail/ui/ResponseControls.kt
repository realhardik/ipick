package app.ipick.voicemail.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ipick.voicemail.answering.AnsweringMachine
import app.ipick.voicemail.response.*

@Composable
internal fun ResponseControls(enabled: Boolean, status: StatusState, a: AnswerUi, vm: MainViewModel) {
    val c = LocalContext.current
    val text = status.mode == ResponseMode.AUTO_TEXT
    var permissionProblem by rememberSaveable { mutableStateOf(false) }
    val request = rememberPermissionRequest {
        permissionProblem = !AutoText.allowed(c)
        if (!permissionProblem) vm.answer.selectAutoText(true)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Enable IPick", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        Switch(checked = enabled, onCheckedChange = vm::setEnabled)
    }
    Text("Respond with", fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!text, { vm.answer.selectAutoText(false) }, { Text("Voicemail") })
        FilterChip(text, {
            if (AutoText.allowed(c)) vm.answer.selectAutoText(true) else request.launch(AutoText.permissions)
        }, { Text("SMS") })
    }
    if (text) Text("Sends a reply from the SIM that received the call, then declines it. Your carrier’s SMS rates apply.",
        style = MaterialTheme.typography.bodySmall)
    if (permissionProblem || (text && !AutoText.allowed(c))) {
        Text("Allow phone and SMS access to send replies.", color = MaterialTheme.colorScheme.error)
    }
    var menu by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { menu = true }) { Text("Idle / Custom ring time · ${a.delay} seconds") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            AnsweringMachine.DELAYS.forEach { seconds ->
                DropdownMenuItem(text = { Text("$seconds seconds") }, onClick = { vm.answer.setDelay(seconds); menu = false })
            }
        }
    }
    HorizontalDivider()
    Text("Status outside DND", fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Availability.IDLE, Availability.CUSTOM).forEach { value ->
            FilterChip(selected = status.normalStatus == value, onClick = {
                if (text && value == Availability.CUSTOM && status.custom.isBlank()) vm.showScreen(Screen.CUSTOM_REPLY)
                else StatusRepository.choose(c, value)
            }, label = { Text(value.title) })
        }
    }
    Text(if (text) "This status chooses your automatic SMS reply." else "Incoming calls use the saved ${status.status.title} greeting.",
        style = MaterialTheme.typography.bodySmall)
    if (text) {
        TextButton(onClick = { vm.showScreen(Screen.ANSWERING_MACHINE) }) { Text("Edit text replies in Greetings") }
        status.lastFailure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    } else {
        if (enabled) a.blocked?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = { vm.showScreen(Screen.ANSWERING_MACHINE) }) { Text("Manage greetings") }
    }
}

@Composable
internal fun StatusPage(modifier: Modifier, status: StatusState, edit: Boolean, vm: MainViewModel) {
    val c = LocalContext.current
    Column(modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = { vm.showScreen(if (edit) Screen.STATUS else Screen.SETTINGS) }) { Text("‹   Back") }
        Text(if (edit) "Custom reply" else "Current status", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        if (edit) {
            LaunchedEffect(Unit) { vm.showScreen(Screen.ANSWERING_MACHINE) }
        } else {
            Text("Your status chooses the voicemail greeting or SMS reply used for missed calls.")
            listOf(Availability.IDLE, Availability.CUSTOM).forEach { value ->
                Row(modifier = Modifier.fillMaxWidth().clickable {
                    if (status.mode == ResponseMode.AUTO_TEXT && value == Availability.CUSTOM && status.custom.isBlank()) vm.showScreen(Screen.CUSTOM_REPLY)
                    else StatusRepository.choose(c, value)
                }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = status.normalStatus == value, onClick = null)
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(value.title, fontWeight = FontWeight.SemiBold)
                        Text(if (status.mode == ResponseMode.VOICEMAIL) "${value.title} voicemail greeting" else status.replyFor(value))
                    }
                }
            }
            if (status.mode == ResponseMode.AUTO_TEXT) TextButton(onClick = { vm.showScreen(Screen.CUSTOM_REPLY) }) { Text("Edit custom reply") }
        }
    }
}

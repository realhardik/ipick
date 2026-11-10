package app.ipick.voicemail.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ipick.voicemail.response.*

@Composable
internal fun TextReplyPage(modifier: Modifier, current: StatusState) {
    val c = LocalContext.current
    var editing by rememberSaveable { mutableStateOf(current.status) }
    var draft by rememberSaveable(editing) { mutableStateOf(current.replyFor(editing)) }
    var saved by rememberSaveable(editing) { mutableStateOf(false) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Text replies", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("The SMS sent when you miss a call. Current status: ${current.status.title}.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Availability.entries.forEach { value ->
                FilterChip(editing == value, { editing = value }, { Text(value.title) })
            }
        }
        OutlinedTextField(draft, { if (it.length <= 160) { draft = it; saved = false } },
            label = { Text("${editing.title} text reply") }, minLines = 3,
            supportingText = { Text("${draft.length}/160") }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { draft = StatusRepository.defaultReply(editing); saved = false }) { Text("Use default message") }
        Button(onClick = { StatusRepository.saveReply(c, editing, draft); saved = true }, enabled = draft.isNotBlank()) {
            Text("Save text reply")
        }
        if (saved) Text("Saved for ${editing.title}.", color = MaterialTheme.colorScheme.primary)
        Text("DND sends immediately; Idle and Custom wait for the ring timer. The reply uses the incoming SIM, and the call is declined once sending succeeds.",
            style = MaterialTheme.typography.bodySmall)
    }
}

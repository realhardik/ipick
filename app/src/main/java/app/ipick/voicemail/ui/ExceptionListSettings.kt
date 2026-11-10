package app.ipick.voicemail.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ipick.voicemail.response.CallExceptions

@Composable
internal fun ExceptionListSettings() {
    val c = LocalContext.current
    val entries by remember(c) { CallExceptions.watch(c) }.collectAsStateWithLifecycle(CallExceptions.read(c))
    var open by rememberSaveable { mutableStateOf(false) }
    Text("Exceptions", fontWeight = FontWeight.Bold)
    Text("These callers ring normally. No voicemail, automatic text, or send-to-voicemail shortcut.",
        style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { open = true }) { Text("Manage exceptions · ${entries.size}") }
    if (open) {
        var number by rememberSaveable { mutableStateOf("") }
        var name by rememberSaveable { mutableStateOf("") }
        var problem by rememberSaveable { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { open = false }, title = { Text("Call exceptions") },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Done") } },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        OutlinedTextField(number, { number = it.take(40); problem = null },
                            label = { Text("Phone number") }, singleLine = true,
                            supportingText = { Text("Include the country code, for example +91.") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                    }
                    item { OutlinedTextField(name, { name = it.take(80) }, label = { Text("Name (optional)") }, singleLine = true) }
                    item {
                        Button(onClick = {
                            runCatching { CallExceptions.add(c, number, name) }.onSuccess {
                                number = ""; name = ""; problem = null
                            }.onFailure { problem = it.message ?: "Couldn’t save this number." }
                        }, enabled = number.isNotBlank()) { Text("Add exception") }
                        problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    if (entries.isEmpty()) item { Text("No exceptions yet.") }
                    items(entries, key = { it.number }) { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                if (entry.name.isNotBlank()) Text(entry.name, fontWeight = FontWeight.Medium)
                                Text(entry.number)
                            }
                            TextButton(onClick = { CallExceptions.remove(c, entry.number) }) { Text("Remove") }
                        }
                        HorizontalDivider()
                    }
                }
            })
    }
}

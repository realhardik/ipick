package app.ipick.voicemail.ui

import android.app.Activity
import android.content.Intent
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ipick.voicemail.R
import app.ipick.voicemail.response.CallExceptions

@Composable
internal fun ExceptionListSettings(listKey: String) {
    val c = LocalContext.current
    val entries by remember(c, listKey) { CallExceptions.watch(c, listKey) }
        .collectAsStateWithLifecycle(CallExceptions.read(c, listKey))
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    var pickedName by rememberSaveable { mutableStateOf("") }
    var pickedNumber by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            runCatching {
                require(uri?.authority == ContactsContract.AUTHORITY)
                c.contentResolver.query(requireNotNull(uri), arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER), null, null, null)?.use {
                    check(it.moveToFirst())
                    pickedName = it.getString(0).orEmpty()
                    pickedNumber = requireNotNull(it.getString(1))
                } ?: error("Contact unavailable")
            }.onFailure { problem = "Couldn’t read that phone number. Choose another contact." }
        }
    }
    fun pick() {
        problem = null
        runCatching { picker.launch(Intent(Intent.ACTION_PICK, Phone.CONTENT_URI)) }
            .onFailure { problem = "No contacts app is available to pick a number." }
    }
    fun toggle(number: String) {
        selected = ArrayList(if (number in selected) selected - number else selected + number)
    }
    Column(Modifier.fillMaxSize()) {
        if (entries.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (selecting) {
                TextButton(onClick = { selected = arrayListOf(); selecting = false }) { Text("Cancel") }
                TextButton(enabled = selected.isNotEmpty(), onClick = {
                    CallExceptions.remove(c, selected.toSet(), listKey)
                    selected = arrayListOf()
                    selecting = false
                }) { Text("Remove (${selected.size})") }
            } else {
                TextButton(onClick = ::pick) { Text("Add contact") }
                TextButton(onClick = { selecting = true }) { Text("Remove") }
            }
        }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (entries.isEmpty()) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(R.drawable.empty_contacts), null, Modifier.size(112.dp))
                Text("No exceptions yet", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = ::pick) { Text("Add a contact") }
            }
        } else LazyColumn {
            items(entries.sortedBy { it.name.ifBlank { it.number }.lowercase() }, key = { it.number }) { entry ->
                Row(Modifier.fillMaxWidth().clickable(enabled = selecting) { toggle(entry.number) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (selecting) Checkbox(entry.number in selected, { toggle(entry.number) })
                    Column {
                        Text(entry.name.ifBlank { entry.number }, style = MaterialTheme.typography.titleMedium)
                        if (entry.name.isNotBlank()) Text(entry.number)
                    }
                }
                HorizontalDivider()
            }
        }
    }
    pickedNumber?.let { number ->
        AlertDialog(onDismissRequest = { pickedNumber = null }, title = { Text(pickedName.ifBlank { "Add contact" }) },
            text = { Text(number) }, confirmButton = {
                TextButton(onClick = {
                    runCatching { CallExceptions.add(c, number, pickedName, listKey) }
                        .onFailure { problem = it.localizedMessage }
                    pickedNumber = null
                }) { Text("Done") }
            }, dismissButton = { TextButton(onClick = { pickedNumber = null }) { Text("Cancel") } })
    }
}

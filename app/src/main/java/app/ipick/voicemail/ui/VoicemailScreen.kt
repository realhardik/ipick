package app.ipick.voicemail.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ipick.voicemail.response.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.ipick.voicemail.R
import app.ipick.voicemail.capture.Recording
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val cobalt = Color(0xff2247ed)
private val paper = Color(0xfffffbf5)
private val ink = Color(0xff151820)
private val pale = Color(0xffe5edff)

@Composable
internal fun VoicemailTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(
        primary = cobalt, background = paper, surface = paper,
        onBackground = ink, onSurface = ink, surfaceContainerHighest = pale,
        primaryContainer = pale, onPrimaryContainer = ink,
    ), content = content)
}

@Composable
internal fun VoicemailScreen(state: UiState, vm: MainViewModel) {
    val answer by vm.answer.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val status by remember(context) { StatusRepository.watch(context) }.collectAsStateWithLifecycle(StatusRepository.read(context))
    val autoText = status.mode == ResponseMode.AUTO_TEXT
    val statusPage = state.screen == Screen.STATUS || state.screen == Screen.CUSTOM_REPLY
    val setup = state.step != SetupStep.READY
    val greeting = state.screen == Screen.ANSWERING_MACHINE
    val settings = state.screen == Screen.SETTINGS
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.notices.collect { snack.showSnackbar(it) } }
    LaunchedEffect(state.step, autoText) { if (!setup && !autoText) vm.answer.opened() }
    LaunchedEffect(state.screen, autoText) {
        if (greeting && !autoText) vm.answer.edit(status.status)
        if (settings) vm.answer.reload()
    }
    BackHandler(enabled = greeting || settings || statusPage) { if (statusPage) vm.back() else vm.showScreen(Screen.HOME) }
    Scaffold(containerColor = paper, snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            if (!setup && !statusPage) NavigationBar(containerColor = paper) {
                NavigationBarItem(selected = !greeting && !settings, onClick = { vm.showScreen(Screen.HOME) },
                    icon = { Text("◉", fontSize = 21.sp) }, label = { Text("Inbox") })
                NavigationBarItem(selected = greeting, onClick = { vm.showScreen(Screen.ANSWERING_MACHINE) },
                    icon = { Text("♪", fontSize = 21.sp) }, label = { Text("Greetings") })
                NavigationBarItem(selected = settings, onClick = vm::showSettings,
                    icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
            }
        }
    ) { insets ->
        val body = Modifier.fillMaxSize().padding(insets).padding(horizontal = 20.dp)
        when {
            setup -> Column(body.verticalScroll(rememberScrollState()).padding(vertical = 18.dp)) {
                SetupScreen(state, vm)
            }
            statusPage -> StatusPage(body, status, state.screen == Screen.CUSTOM_REPLY, vm)
            greeting -> if (autoText) TextReplyPage(body, status) else GreetingPage(body, answer, status, vm)
            settings -> SettingsPages(body, state, answer, status, vm)
            else -> Inbox(body, state, answer, status, vm)
        }
    }
}

private enum class SettingsPage(val title: String) {
    HOME("Settings"), GENERAL("General"), DND("Do Not Disturb"), PHONE("Phone & connection"),
    AUDIO("Voicemail audio"), EXCEPTIONS("Call exceptions"), DND_EXCEPTIONS("DND exceptions")
}

@Composable
private fun SettingsPages(modifier: Modifier, state: UiState, answer: AnswerUi, status: StatusState, vm: MainViewModel) {
    val c = LocalContext.current
    var page by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(SettingsPage.HOME) }
    fun back() {
        page = when (page) {
            SettingsPage.EXCEPTIONS -> SettingsPage.GENERAL
            SettingsPage.DND_EXCEPTIONS -> SettingsPage.DND
            else -> SettingsPage.HOME
        }
    }
    BackHandler(enabled = page != SettingsPage.HOME, onBack = ::back)
    Column(modifier.padding(vertical = 12.dp)) {
        if (page != SettingsPage.HOME) TextButton(onClick = ::back) { Text("‹  Back") }
        Title(page.title)
        Spacer(Modifier.height(12.dp))
        if (page == SettingsPage.EXCEPTIONS || page == SettingsPage.DND_EXCEPTIONS) {
            ExceptionListSettings(if (page == SettingsPage.EXCEPTIONS) CallExceptions.KEY else CallExceptions.DND_KEY)
        } else key(page) {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (page) {
                    SettingsPage.HOME -> listOf(SettingsPage.GENERAL, SettingsPage.DND, SettingsPage.PHONE,
                        SettingsPage.AUDIO).forEach { group ->
                        ListItem(headlineContent = { Text(group.title) }, trailingContent = { Text("›") },
                            modifier = Modifier.clickable { page = group })
                    }
                    SettingsPage.GENERAL -> {
                        ResponseControls(state.enabled, status, answer, vm)
                        ProfileSettings()
                        TextButton(onClick = { page = SettingsPage.EXCEPTIONS }) { Text("Call exceptions  ›") }
                    }
                    SettingsPage.DND -> {
                        Text(if (status.status == Availability.DND) "Phone DND is on" else "Phone DND is off",
                            style = MaterialTheme.typography.titleMedium)
                        Text("When DND is on, IPick starts your selected response immediately.")
                        var repeats by remember { mutableStateOf(DndCalls.repeatsEnabled(c)) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Let the third call through within 15 minutes", Modifier.weight(1f))
                            Switch(repeats, { repeats = it; DndCalls.setRepeats(c, it) })
                        }
                        TextButton(onClick = { page = SettingsPage.DND_EXCEPTIONS }) { Text("DND exceptions  ›") }
                        Text("Your phone’s DND rules control which calls sound.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            runCatching { c.startActivity(android.content.Intent("android.settings.ZEN_MODE_SETTINGS")) }
                                .onFailure { c.startActivity(android.content.Intent(android.provider.Settings.ACTION_SOUND_SETTINGS)) }
                        }) { Text("Open phone DND settings") }
                    }
                    SettingsPage.PHONE -> {
                        OfflineStartupSetting(state, vm::setStartWithoutWifi)
                        if (!state.daemonRunning && state.enabled) OutlinedButton(onClick = vm::openConnectionSetup) { Text("Open connection setup") }
                        val labels = rememberPermissionRequest { vm.refresh() }
                        if (!state.canReadCallLog || !app.ipick.voicemail.calls.CallLogLookup.photosGranted(c)) TextButton(onClick = {
                            labels.launch(Setup.CALLER_PERMISSIONS)
                        }) { Text("Allow caller identification") }
                    }
                    SettingsPage.AUDIO -> AudioSettings(answer, status.mode == ResponseMode.AUTO_TEXT, vm)
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun OfflineStartupSetting(state: UiState, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Start without Wi-Fi", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        Switch(checked = state.startWithoutWifi, onCheckedChange = onChange)
    }
    Text("After each phone restart, connect to Wi-Fi once. Voicemail can then restart on its own while you’re away from Wi-Fi.",
        style = MaterialTheme.typography.bodySmall)
    Text("This opens debugging port 5555 on the networks you join while USB debugging is on. Other devices can request access. This connection is unencrypted.",
        style = MaterialTheme.typography.bodySmall)
    val detail = when {
        state.portCloseFailed && state.portSet && !state.startWithoutWifi -> "Android kept the debugging port open. Restart your phone to close it."
        !state.startWithoutWifi && state.portOurs && state.portSet -> "Waiting for Wireless debugging to close the port."
        state.startWithoutWifi && state.portSet -> "Ready until your phone restarts."
        state.startWithoutWifi && state.portFailed -> "Android could not prepare offline startup this time."
        state.startWithoutWifi -> "Waiting for Wi-Fi and Wireless debugging to prepare offline startup."
        else -> null
    }
    if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall)
}

private fun readiness(state: UiState, a: AnswerUi): String = when {
    !state.enabled -> "IPick is off"
    !state.daemonRunning && !state.onWifi -> "Connect to Wi-Fi to restart voicemail"
    !state.daemonRunning -> "Voicemail helper is offline"
    a.paused != null -> "Paused for emergency callbacks"
    a.blocked != null -> a.blocked.orEmpty()
    else -> "Ready to take messages"
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
}

@Composable
private fun Inbox(modifier: Modifier, state: UiState, a: AnswerUi, status: StatusState, vm: MainViewModel) {
    val today = LocalDate.now()
    val groups = state.recordings.filter { it.message }.groupBy {
        it.startedAtMillis?.let { ms -> Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate() }
    }
    LazyColumn(modifier, contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Title(stringResource(R.string.app_name)) }
                IconButton(onClick = vm::showSettings) { Icon(Icons.Default.Settings, "Settings") }
            }
            Spacer(Modifier.height(10.dp))
            Text(if (state.enabled && status.mode == ResponseMode.AUTO_TEXT) "SMS · ${status.status.title}" else readiness(state, a), color = cobalt, fontWeight = FontWeight.Medium)
            if (!state.daemonRunning && state.enabled && status.mode == ResponseMode.VOICEMAIL) {
                TextButton(onClick = vm::openConnectionSetup) { Text("Open connection setup") }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (groups.isEmpty()) item {
            Column(Modifier.fillMaxWidth().background(pale, RoundedCornerShape(18.dp)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("No messages yet", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Text(if (status.mode == ResponseMode.AUTO_TEXT) "SMS is selected. Saved voicemails stay in this inbox." else if (a.enabled) "Messages from unanswered calls will appear here."
                    else "Turn on Enable IPick in Settings → General to take voicemail messages.")
                if (!state.enabled) TextButton(onClick = vm::showSettings) { Text("Open settings") }
            }
        }
        groups.forEach { (day, messages) ->
            item { Text(when (day) {
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                null -> "Messages"
                else -> day.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
            }, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp)) }
            items(messages, key = { it.name }) { message -> MessageRow(message, state, vm) }
        }
    }
}

@Composable
private fun MessageRow(message: Recording, state: UiState, vm: MainViewModel) {
    val selected = message.name == state.playingName
    val playing = selected && state.playing
    Column(Modifier.fillMaxWidth().background(if (selected) pale else paper, RoundedCornerShape(14.dp))
        .clickable(onClickLabel = if (playing) "Pause message" else "Play message") { vm.play(message) }.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(message.caller ?: message.number ?: "Unknown caller", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text(message.whenLabel, style = MaterialTheme.typography.bodySmall)
            }
            Text(message.durationLabel)
            Text(if (playing) "  Ⅱ" else "  ▶", color = cobalt)
        }
        val levels = state.waveform
        if (selected && !levels.isNullOrEmpty()) Canvas(Modifier.fillMaxWidth().height(32.dp).padding(top = 6.dp)) {
            val gap = size.width / levels.size
            levels.forEachIndexed { i, level ->
                val h = (level.coerceIn(0f,1f) * size.height).coerceAtLeast(2f)
                drawLine(cobalt, Offset(i * gap, (size.height-h)/2), Offset(i * gap, (size.height+h)/2), gap * .6f)
            }
        }
    }
    HorizontalDivider(color = ink.copy(alpha = .12f))
}


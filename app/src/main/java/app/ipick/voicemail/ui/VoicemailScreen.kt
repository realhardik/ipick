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
    LaunchedEffect(state.step) { if (!setup) vm.answer.opened() }
    LaunchedEffect(state.screen) {
        if (greeting) vm.answer.edit(status.status)
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
            settings -> Column(body.verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Title("Settings")
                ProfileSettings()
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ResponseControls(status, answer, vm)
                    }
                }
                HorizontalDivider()
                ExceptionListSettings()
                HorizontalDivider()
                ShortcutSettings()
                HorizontalDivider()
                AudioSettings(answer, autoText, vm)
                if (!state.daemonRunning && state.enabled) OutlinedButton(onClick = vm::startAgain) { Text("Retry connection") }
                val labels = rememberPermissionRequest { vm.refresh() }
                if (!state.canReadCallLog) TextButton(onClick = {
                    labels.launch(arrayOf(android.Manifest.permission.READ_CALL_LOG, android.Manifest.permission.READ_CONTACTS))
                }) { Text("Show caller names in the inbox") }
            }
            else -> Inbox(body, state, answer, status, vm)
        }
    }
}

private fun readiness(state: UiState, a: AnswerUi): String = when {
    !state.enabled -> "Voicemail is off"
    !state.daemonRunning && !state.onWifi -> "Connect to Wi-Fi to restart voicemail"
    !state.daemonRunning -> "Voicemail helper is offline"
    a.paused != null -> "Paused for emergency callbacks"
    !a.enabled -> "Voicemail is off"
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
                Box(Modifier.weight(1f)) { Title("Voicemail") }
                IconButton(onClick = vm::showSettings) { Icon(Icons.Default.Settings, "Settings") }
            }
            Spacer(Modifier.height(10.dp))
            Text(if (status.mode == ResponseMode.AUTO_TEXT) "Auto-text · ${status.status.title}" else readiness(state, a), color = cobalt, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(12.dp))
        }
        if (groups.isEmpty()) item {
            Column(Modifier.fillMaxWidth().background(pale, RoundedCornerShape(18.dp)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("No messages yet", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Text(if (status.mode == ResponseMode.AUTO_TEXT) "Auto-text is selected. Saved voicemails stay in this inbox." else if (a.enabled) "Messages from unanswered calls will appear here."
                    else "Choose a greeting and turn on voicemail to take your first message.")
                if (!a.enabled && status.mode == ResponseMode.VOICEMAIL) Button(onClick = { vm.showScreen(Screen.ANSWERING_MACHINE) }) { Text("Set up greeting") }
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
                Text(message.caller ?: message.number ?: "Voicemail message", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
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


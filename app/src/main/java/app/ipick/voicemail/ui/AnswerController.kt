package app.ipick.voicemail.ui

import app.ipick.voicemail.Prefs
import app.ipick.voicemail.answering.KokoroAudio
import app.ipick.voicemail.response.Availability
import app.ipick.voicemail.response.AutoText
import app.ipick.voicemail.response.StatusRepository
import android.app.Application
import android.media.AudioTrack
import android.util.Log
import app.ipick.voicemail.answering.AnswerConditions
import app.ipick.voicemail.answering.AnswerDiagnostics
import app.ipick.voicemail.answering.AnsweringMachine
import app.ipick.voicemail.calls.CallTracking
import app.ipick.voicemail.answering.EmergencyPause
import app.ipick.voicemail.capture.CaptureDaemon
import app.ipick.voicemail.answering.Greeting
import app.ipick.voicemail.answering.GreetingPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.concurrent.thread

/** What the answering machine's screen shows. */
data class AnswerUi(
    val generating: Boolean = false,
    val savingGreeting: Boolean = false,
    val greetingSource: String = "Bundled default",
    val editing: Availability = Availability.IDLE,
    val enabled: Boolean = false,
    val delay: Int = AnsweringMachine.DEFAULT_DELAY,
    val dndShort: Boolean = true,
    val screenOff: Boolean = false,
    val longest: Int = AnsweringMachine.DEFAULT_LONGEST,
    val sim: String = AnsweringMachine.SIM_DEFAULT,
    /** The phone's SIMs, as the recorder lists them; shown with two or more. */
    val sims: List<Pair<String, String>> = emptyList(),
    val defaultSim: String? = null,
    /** Seconds of greeting, or null with none recorded. */
    val greetingSeconds: Double? = null,
    val recordingTake: Boolean = false,
    val takeStartedAt: Long = 0L,
    val playing: Boolean = false,
    val greetingProblem: String? = null,
    val callTracking: Boolean = true,
    /** The no-call checks: null until run once. */
    val stageOne: AnswerDiagnostics.StageOne? = null,
    val checking: Boolean = false,
    val results: Map<String, AnswerDiagnostics.Verdict> = emptyMap(),
    /** A test call: "armed", "running" or null; and until when it is armed. */
    val test: String? = null,
    val testIncoming: Boolean = true,
    val testUntil: Long = 0L,
    /** The test just finished needs the user's word: did they hear it? */
    val askHeard: String? = null,
    /** The carrier's no-reply forwarding: "off 0", "on 25 *1234", null unknown. */
    val carrier: String? = null,
    /** Standing down after an emergency call until then (EmergencyPause), or null. */
    val pausedUntil: Long? = null,
    val checkingCarrier: Boolean = false,
) {
    /** Why the switch cannot be turned on, or null when it can. */
    val blocked: String?
        get() = when {
            stageOne == null -> "Run the checks below first"
            !stageOne.possible -> stageOne.problem
            !callTracking -> "Needs exact call tracking, in Settings"
            greetingSeconds == null -> "Record a greeting first"
            else -> null
        }

    /** Standing down after an emergency call, and switched on: until when. */
    val paused: Long?
        get() = pausedUntil?.takeIf { enabled }

    /** The carrier's no-reply timer in seconds, when forwarding is on. */
    val carrierSeconds: Int?
        get() = carrier?.split(' ')?.takeIf { it.firstOrNull() == "on" }?.getOrNull(1)?.toIntOrNull()
}

class AnswerController(
    private val app: Application,
    private val scope: CoroutineScope,
    private val say: (String) -> Unit,
    initial: AnswerUi? = null,
    private val quiet: () -> Unit = {},
) {
    private val _state = MutableStateFlow(initial ?: AnswerUi())
    val state: StateFlow<AnswerUi> = _state.asStateFlow()

    private var generation: Job? = null
    private var take: Job? = null
    private var preview: AudioTrack? = null
    private var previewJob: Job? = null
    private var testWatch: Job? = null
    /** Tests seed the state; nothing is then read from disk over it. */
    private val seeded = initial != null

    /** Read everything back from the preferences. Cheap: no recorder calls. */
    fun reload() {
        if (seeded) return
        _state.update {
            it.copy(
                enabled = AnsweringMachine.enabled(app),
                delay = AnsweringMachine.delay(app),
                dndShort = AnsweringMachine.dndShort(app),
                screenOff = AnsweringMachine.screenOff(app),
                longest = AnsweringMachine.longest(app),
                sim = AnsweringMachine.sim(app),
                defaultSim = AnswerConditions.defaultSim(app),
                greetingSeconds = Greeting.seconds(app, it.editing),
                greetingSource = Greeting.source(app, it.editing),
                callTracking = CallTracking.enabled(app),
                pausedUntil = EmergencyPause.pausedUntil(app),
                stageOne = AnswerDiagnostics.stageOne(app),
                results = AnswerDiagnostics.results(app),
            )
        }
    }

    /** The screen opened: the checks, if never run, and the SIMs. */
    fun opened() {
        if (seeded) return
        reload()
        if (_state.value.stageOne == null) runChecks()
        scope.launch {
            CaptureDaemon.sims(app)?.let { sims ->
                AnswerConditions.remember(app, sims)
                _state.update { it.copy(sims = sims.all, defaultSim = sims.defaultId) }
            }
        }
    }

    private fun push() {
        scope.launch {
            if (!CaptureDaemon.setAnswering(app)) {
                say("the recorder will pick up the answering machine when it next restarts")
            }
        }
    }

    /** Answer again before the day after an emergency call is out - the
     *  user's choice, made after the screen's warning. */
    fun resumeAfterEmergency() {
        EmergencyPause.resume(app)
        _state.update { it.copy(pausedUntil = null) }
    }

    private var switchingMode: Job? = null

    fun selectAutoText(on: Boolean) {
        if (switchingMode?.isActive == true || on == StatusRepository.autoText(app)) return
        if (on && !AutoText.allowed(app)) return
        switchingMode = scope.launch {
            // Disarm any pending answer (including diagnostics) before allowing SMS.
            if (on && !CaptureDaemon.setAnswering(app, "text")) {
                say("Reconnect the voicemail helper to activate Auto-text.")
                return@launch
            }
            StatusRepository.setAutoText(app, on)
            reload()
            if (!on) push()
        }
    }

    fun setEnabled(on: Boolean) {
        if (StatusRepository.autoText(app)) return
        if (on && _state.value.blocked != null) return
        AnsweringMachine.setEnabled(app, on)
        _state.update { it.copy(enabled = on) }
        push()
    }

    fun setDelay(seconds: Int) {
        AnsweringMachine.setDelay(app, seconds)
        _state.update { it.copy(delay = seconds) }
    }

    fun setDndShort(short: Boolean) {
        AnsweringMachine.setDndShort(app, short)
        _state.update { it.copy(dndShort = short) }
    }

    fun setScreenOff(on: Boolean) {
        AnsweringMachine.setScreenOff(app, on)
        _state.update { it.copy(screenOff = on) }
        push()
    }

    fun setLongest(seconds: Int) {
        AnsweringMachine.setLongest(app, seconds)
        _state.update { it.copy(longest = seconds) }
        push()
    }

    fun setSim(sim: String) {
        AnsweringMachine.setSim(app, sim)
        _state.update { it.copy(sim = sim) }
    }

    fun edit(status: Availability) {
        if (_state.value.generating || _state.value.savingGreeting || take?.isActive == true) return
        stopPreview()
        _state.update { it.copy(editing = status, greetingSeconds = Greeting.seconds(app, status), greetingSource = Greeting.source(app, status), greetingProblem = null) }
    }

    fun cancelGeneration() {
        if (_state.value.generating) generation?.cancel()
    }

    private suspend fun saved(status: Availability) {
        _state.update { it.copy(greetingSeconds = Greeting.seconds(app, status),
            greetingSource = Greeting.source(app, status), greetingProblem = null) }
        // Save is successful independently of the helper connection. Report the distinction.
        val active = Greeting.active(app)
        if (status == active) {
            val synced = try { CaptureDaemon.syncGreeting(app) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { false }
            if (!synced) _state.update { it.copy(greetingProblem =
                "Saved on this phone. The helper couldn’t be updated yet; reconnect it before your next call.") }
            say("Saved ${Greeting.source(app, status).lowercase()} for ${status.title}")
        } else {
            say("Saved for ${status.title}. Calls currently use ${active.title}; change your status in Settings to use this greeting.")
        }
    }

    fun generate(text: String, voice: String, draft: String = text) {
        if (_state.value.generating || _state.value.savingGreeting || take?.isActive == true || text.isBlank() || text.length > 260) return
        val status = _state.value.editing
        stopPreview()
        _state.update { it.copy(generating = true, greetingProblem = null) }
        generation = scope.launch {
            var committed = false
            try {
                val body = org.json.JSONObject().put("model", "kokoro").put("input", text)
                    .put("voice", voice).put("response_format", "wav").put("stream", false).toString()
                val pcm = withTimeout(KokoroAudio.TIMEOUT_SECONDS * 1000L) {
                    suspendCancellableCoroutine<ByteArray> { continuation ->
                        val request = KokoroAudio.Request()
                        continuation.invokeOnCancellation {
                            thread(name = "greeting-cancel", isDaemon = true) { request.cancel() }
                        }
                        thread(name = "greeting-download", isDaemon = true) {
                            continuation.resumeWith(runCatching { request.execute(body) })
                        }
                    }
                }
                // A timed-out/cancelled request can never reach the file write.
                _state.update { it.copy(generating = false, savingGreeting = true) }
                withContext(Dispatchers.IO) { Greeting.save(app, pcm, status, "Generated voice") }
                committed = true
                GreetingPreferences.setText(app, status, draft)
                Prefs.of(app).edit().putString("voice.id." + status.name, voice).apply()
                saved(status)
            } catch (e: TimeoutCancellationException) {
                _state.update { it.copy(greetingProblem =
                    "The voice service didn’t respond within ${KokoroAudio.TIMEOUT_SECONDS} seconds. No new greeting was saved. Try again or record your own.") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.update { it.copy(greetingProblem = "Generation cancelled. Your saved greeting is unchanged.") }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(greetingProblem = if (committed) "Audio saved on this phone, but the helper could not be updated. Reconnect it before your next call."
                    else "Couldn’t generate a greeting. Your saved audio is unchanged. Try again or record your own.") }
                Log.w("AnswerController", "Greeting generation failed", e)
            } finally {
                _state.update { it.copy(generating = false, savingGreeting = false) }
            }
        }
    }

    fun startTake() {
        if (_state.value.generating || _state.value.savingGreeting || take?.isActive == true) return
        stopPreview()
        quiet()
        _state.update { it.copy(savingGreeting = true, greetingProblem = null) }
        take = scope.launch {
            if (!CaptureDaemon.startTake(app)) {
                _state.update { it.copy(savingGreeting = false) }
                _state.update { it.copy(greetingProblem = "The recorder could not start recording. Is it running?") }
                return@launch
            }
            _state.update { it.copy(savingGreeting = false, recordingTake = true, takeStartedAt = System.currentTimeMillis(), greetingProblem = null) }
            // The recorder stops by itself at thirty seconds; so does this.
            delay(Greeting.MAX_SECONDS * 1000L + 500)
            if (_state.value.recordingTake) stopTake()
        }
    }

    fun stopTake() {
        if (!_state.value.recordingTake) return
        _state.update { it.copy(recordingTake = false, savingGreeting = true) }
        // The take's thirty-second wait goes with it, or Record again would
        // be refused until then. Record stays refused while this fetches:
        // a new take would overwrite the file being read.
        take?.cancel()
        val status = _state.value.editing
        take = scope.launch {
            try {
                check(CaptureDaemon.stopTake(app) != null) { "The helper couldn’t finish recording." }
                val pcm = CaptureDaemon.fetchTake(app)
                val processed = withContext(Dispatchers.Default) { pcm?.let(Greeting::process) }
                when (processed) {
                    is Greeting.Take.Usable -> {
                        withContext(Dispatchers.IO) { Greeting.save(app, processed.pcm, status, "Your recording") }
                        saved(status)
                    }
                    Greeting.Take.Silent, null -> _state.update {
                        it.copy(greetingProblem = "Nothing was heard. Your saved greeting is unchanged. Hold the phone as for a call, and speak.")
                    }
                    Greeting.Take.TooShort -> _state.update {
                        it.copy(greetingProblem = "That was too short. Your saved greeting is unchanged. Try again.")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(greetingProblem = "Couldn’t save your recording. Your previous greeting is unchanged.") }
                Log.w("AnswerController", "Saving greeting recording failed", e)
            } finally {
                _state.update { it.copy(savingGreeting = false) }
            }
        }
    }

    fun togglePreview() {
        if (_state.value.generating || _state.value.savingGreeting || _state.value.recordingTake) return
        if (_state.value.playing) {
            stopPreview()
            return
        }
        quiet()
        previewJob?.cancel()
        previewJob = scope.launch {
            val status = _state.value.editing
            val pcm = withContext(Dispatchers.IO) { Greeting.read(app, status) }
            if (_state.value.editing != status) return@launch
            val track = Greeting.preview(pcm) ?: return@launch
            preview = track
            _state.update { it.copy(playing = true) }
            delay((pcm.size / 2.0 / Greeting.RATE * 1000).toLong() + 300)
            if (preview === track) stopPreview()
        }
    }

    private fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        preview?.let { runCatching { it.stop(); it.release() } }
        preview = null
        _state.update { it.copy(playing = false) }
    }

    fun deleteGreeting() {
        stopPreview()
        Greeting.delete(app)
        AnsweringMachine.setEnabled(app, false)
        _state.update { it.copy(greetingSeconds = null, enabled = false) }
        scope.launch {
            CaptureDaemon.syncGreeting(app)
            CaptureDaemon.setAnswering(app)
        }
    }

    /** Stage one: the no-call checks, on the recorder. */
    fun runChecks() {
        if (_state.value.checking) return
        _state.update { it.copy(checking = true) }
        scope.launch {
            val fields = CaptureDaemon.stageOne(app)
            if (fields == null) {
                say("the answering machine's checks need the recorder running")
            } else {
                AnswerDiagnostics.rememberStageOne(app, fields)
                val stage = AnswerDiagnostics.stageOne(fields)
                AnswerDiagnostics.routeAfterChecks(stage, AnsweringMachine.route(app))?.let {
                    AnsweringMachine.setRoute(app, it)
                    push()
                }
                _state.update { it.copy(stageOne = stage) }
            }
            _state.update { it.copy(checking = false) }
        }
    }

    /**
     * Stage two: a test call. Incoming - the next call in three minutes is
     * answered, whoever makes it; or outgoing - the recorder dials [number],
     * a line that answers by itself. The recorder plays the check tone and
     * the greeting, and measures.
     */
    fun startTest(incoming: Boolean, number: String = "") {
        if (StatusRepository.autoText(app) || _state.value.generating || _state.value.savingGreeting || _state.value.recordingTake) return
        // A test answers whoever calls in its three minutes: never in the day
        // after an emergency call, when that may be them calling back. Nor is
        // an emergency number ever called for one (the screen says so too).
        if (incoming && EmergencyPause.pausedUntil(app) != null) {
            say("no test call for a day after an emergency call - unless you answer again now")
            return
        }
        if (!incoming && EmergencyPause.isEmergencyNumber(app, number)) {
            say("an emergency number is never called for a test")
            return
        }
        scope.launch {
            // The test plays the greeting the recorder has, on the route the
            // recorder has: both brought up to date first.
            if (!CaptureDaemon.syncGreeting(app)) { say("Could not load the current greeting"); return@launch }
            CaptureDaemon.setAnswering(app)
            val reply = CaptureDaemon.armTest(app, incoming, number)
            if (reply != "TEST armed") {
                say(
                    when (reply) {
                        "TEST bad-number" -> "that number cannot be dialled"
                        "TEST emergency" -> "an emergency number is never called for a test"
                        "TEST no-greeting" -> "the recorder does not have your greeting yet - try again in a moment"
                        "TEST busy" -> "the last test call is still going on"
                        else -> "the recorder could not arm a test call"
                    },
                )
                return@launch
            }
            // The recorder's own windows: three minutes for a caller, and
            // fifteen seconds for the call it dials to go out.
            val window = if (incoming) 180_000L else 15_000L
            _state.update {
                it.copy(test = "armed", testIncoming = incoming, testUntil = System.currentTimeMillis() + window, askHeard = null)
            }
            watchTest(window)
        }
    }

    private fun watchTest(window: Long) {
        testWatch?.cancel()
        testWatch = scope.launch {
            val until = System.currentTimeMillis() + window + 120_000L
            while (System.currentTimeMillis() < until) {
                delay(2_000)
                val result = CaptureDaemon.testResult(app) ?: continue
                when (result["state"]) {
                    "running" -> _state.update { it.copy(test = "running") }
                    "done" -> {
                        finishTest(result)
                        return@launch
                    }
                }
                // A call that rang in the window's last moment is answered
                // three seconds on, and is "running" once its audio is up.
                if (result["state"] == "armed" && System.currentTimeMillis() > _state.value.testUntil + ANSWER_GRACE_MS) break
            }
            _state.update { it.copy(test = null) }
            say("no test call came")
        }
    }

    private fun finishTest(result: Map<String, String>) {
        val type = AnswerDiagnostics.callType(result["voice"]?.toIntOrNull() ?: -1)
        val verdict = AnswerDiagnostics.verdict(result)
        Log.i(TAG, "answer: test on $type -> $verdict ($result)")
        AnswerDiagnostics.record(app, type, verdict)
        if (verdict == AnswerDiagnostics.Verdict.FAILS) {
            // A microphone that would not mute is this phone's, whichever way
            // the greeting goes: no reason to try the other.
            if (result["mute"] == "0") {
                say("the microphone did not stay muted, so the caller would hear the room")
            } else {
                tryTheOtherRoute(type)
            }
        }
        _state.update {
            it.copy(
                test = null,
                results = AnswerDiagnostics.results(app),
                // Only someone who called can say. An outgoing test reached a
                // machine; its verdict is the measurements'.
                askHeard = if (it.testIncoming && verdict != AnswerDiagnostics.Verdict.FAILS) type else null,
            )
        }
    }

    /** Stage three: what the caller heard. */
    fun heard(type: String, heard: Boolean) {
        AnswerDiagnostics.record(app, type, if (heard) AnswerDiagnostics.Verdict.WORKS else AnswerDiagnostics.Verdict.FAILS)
        // Unheard is as failed as measured wrong.
        if (!heard) tryTheOtherRoute(type)
        _state.update { it.copy(askHeard = null, results = AnswerDiagnostics.results(app)) }
    }

    /** The route just failed on a [type] of call: the other one next time,
     *  where AnswerDiagnostics.routeAfterFailure says so. */
    private fun tryTheOtherRoute(type: String) {
        val other = AnswerDiagnostics.routeAfterFailure(
            AnsweringMachine.route(app), _state.value.stageOne, AnswerDiagnostics.results(app), type,
        ) ?: return
        AnsweringMachine.setRoute(app, other)
        push()
        say("the greeting did not reach the call; the next test tries the other way")
    }

    fun dismissHeard() = _state.update { it.copy(askHeard = null) }

    /** The carrier's own voicemail timer - slow: the network is asked. */
    fun checkCarrier() {
        if (_state.value.checkingCarrier) return
        _state.update { it.copy(checkingCarrier = true) }
        scope.launch {
            val answer = CaptureDaemon.forwarding(app)
            _state.update { it.copy(checkingCarrier = false, carrier = answer ?: "error") }
        }
    }

    /** Keep the carrier's voicemail, after 30 s - on the user's tap only. */
    fun carrierWaitLonger() {
        if (_state.value.checkingCarrier) return
        _state.update { it.copy(checkingCarrier = true) }
        scope.launch {
            val answer = CaptureDaemon.setForwarding(app, 30)
            _state.update {
                it.copy(checkingCarrier = false, carrier = answer?.removePrefix("set ")?.removePrefix("unchanged ") ?: "error")
            }
            if (answer?.startsWith("set ") != true) say("the carrier did not take the change: $answer")
        }
    }

    fun copyReport() {
        val text = AnswerDiagnostics.report(
            AnswerDiagnostics.rawStageOne(app), AnswerDiagnostics.results(app), AnsweringMachine.route(app),
            appVersion(app),
        )
        reportToClipboard(app, "IPick answering machine report", text)
    }

    private companion object {
        const val TAG = "IPick"
        /** The recorder's three seconds to answer a test, its wait of up to
         *  eight for the call's audio, and a poll. */
        const val ANSWER_GRACE_MS = 15_000L
    }
}

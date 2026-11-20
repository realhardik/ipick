package app.ipick.voicemail.capture

import app.ipick.voicemail.response.*
import android.os.Handler
import android.os.Looper
import android.content.Context
import android.telecom.Call
import android.telecom.InCallService
import android.util.Log
import app.ipick.voicemail.answering.AnswerConditions
import app.ipick.voicemail.answering.AnsweringMachine
import app.ipick.voicemail.answering.EmergencyPause
import app.ipick.voicemail.answering.Greeting
import app.ipick.voicemail.calls.CallLedger
import app.ipick.voicemail.calls.CallLogLookup
import app.ipick.voicemail.calls.CallRules
import app.ipick.voicemail.calls.CallTracking
import app.ipick.voicemail.calls.CoverageHistory
import app.ipick.voicemail.calls.PhoneNumbers
import app.ipick.voicemail.calls.TelecomAccount
import app.ipick.voicemail.recordings.RecordingSaver
import app.ipick.voicemail.rethrowingCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import app.ipick.voicemail.Prefs
import app.ipick.voicemail.shortcuts.IncomingVoicemail
import app.ipick.voicemail.answering.AnswerDiagnostics
import android.widget.Toast

/**
 * Telecom's own account of each phone call, as it happens - see CallTracking.
 *
 * Bound by Telecom for the length of each call, and only while exact call
 * tracking is on: the daemon allows MANAGE_ONGOING_CALLS for this app's uid
 * then, and takes it back when it is turned off. Being bound also keeps this
 * process at the front while a call is up, which is what lets the end of a
 * call be acted on here rather than only through the daemon's wake.
 *
 * It can decline the original ringing call after an auto-text succeeds. Voicemail answering is
 * the recorder's doing, on a go-ahead from here (decideAnswer). Messenger calls are not
 * here: they are self-managed, and Telecom keeps them from a service like
 * this one unless it asks, which it does not.
 */
class CallTracker : InCallService() {

    private val handler = Handler(Looper.getMainLooper())
    private val callGenerations = mutableMapOf<Call, Long>()
    private val ringTokens = mutableMapOf<Call, Long?>()
    private var mayHaveVoicemail = false
    private val pendingTexts = mutableMapOf<Call, Runnable>()

    private val manualScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var manualCall: Call? = null
    private var manualToken: String? = null
    private var manualInFlight: Call? = null
    private var destroyed = false
    private val manualHandled = mutableSetOf<Call>()

    private var dndWatch: Job? = null
    private val answerVersions = mutableMapOf<Call, Int>()
    private val exceptedCalls = mutableSetOf<Call>()
    private val exceptionWatches = mutableListOf<() -> Unit>()

    override fun onCreate() {
        super.onCreate()
        exceptionWatches += Prefs.watch(this, StatusRepository.EPOCH) {
            handler.post {
                if (!destroyed) {
                    pendingTexts.keys.filterNot(::enabledFor).forEach { call ->
                        pendingTexts.remove(call)?.let(handler::removeCallbacks)
                    }
                    for ((call, ring) in ringTokens) {
                        if (ring != null && !enabledFor(call) && call.state == Call.STATE_RINGING) {
                            manualScope.launch { CaptureDaemon.goAhead(this@CallTracker, -1, 0L, ring) }
                        }
                    }
                    refreshDndWatch()
                    refreshManual()
                }
            }
        }
        exceptionWatches += listOf(CallExceptions.KEY, CallExceptions.DND_KEY, DndCalls.REPEATS).map { key -> Prefs.watch(this, key) {
            handler.post {
                if (!destroyed) {
                    calls.forEach(::applyException)
                    refreshManual()
                }
            }
        } }
    }

    private fun enabledFor(call: Call) = RecorderSwitch.isOn(this) &&
        callGenerations[call] == StatusRepository.generation(this)

    private fun refreshDndWatch() {
        if (calls.none { it.state == Call.STATE_RINGING && enabledFor(it) }) {
            dndWatch?.cancel()
            dndWatch = null
            return
        }
        if (dndWatch?.isActive == true) return
        dndWatch = manualScope.launch {
            var previous = StatusRepository.phoneDnd(this@CallTracker)
            StatusRepository.watch(this@CallTracker, ringing = true).map { it.status == Availability.DND }.distinctUntilChanged().collect { active ->
                if (previous != active) {
                    previous = active
                    calls.filter { it.state == Call.STATE_RINGING }.forEach { call ->
                        applyException(call)
                        pendingTexts.remove(call)?.let(handler::removeCallbacks)
                        if (!isExcepted(call) && CallTracking.enabled(this@CallTracker) && manualInFlight !== call && call !in manualHandled) {
                            if (StatusRepository.autoText(this@CallTracker)) scheduleText(call)
                            else decideAnswer(call, lazy { CallRules.verdict(this@CallTracker, PhoneNumbers.of(call.details.handle)) })
                        }
                    }
                    refreshManual()
                }
            }
        }
    }

    private fun isExcepted(call: Call): Boolean =
        call in exceptedCalls || CallExceptions.matches(this, call.details.handle) ||
            (RecorderSwitch.isOn(this) && CallTracking.enabled(this) && DndCalls.allows(this, call.details))

    private fun applyException(call: Call) {
        if (call.state != Call.STATE_RINGING || call.details.callDirection != Call.Details.DIRECTION_INCOMING ||
            !isExcepted(call)) return
        pendingTexts.remove(call)?.let(handler::removeCallbacks)
        AutoText.forget(call)
        if (!exceptedCalls.add(call)) return
        val ring = ringTokens[call] ?: return
        manualScope.launch { CaptureDaemon.excludeRingingCall(this@CallTracker, ring) }
    }

    private fun checkRingingCall(call: Call) {
        if (calls.singleOrNull() !== call || ringTokens.containsKey(call) || call.details.callDirection != Call.Details.DIRECTION_INCOMING) return
        ringTokens[call] = null
        manualScope.launch {
            for (wait in longArrayOf(0, 100, 200, 400, 800, 1_500)) {
                if (wait > 0) delay(wait)
                if (destroyed || calls.singleOrNull() !== call || call.state != Call.STATE_RINGING) return@launch
                val token = CaptureDaemon.ringingToken(this@CallTracker, call.details.creationTimeMillis) ?: return@launch
                if (destroyed || calls.singleOrNull() !== call || call.state != Call.STATE_RINGING || !CallTracking.enabled(this@CallTracker)) return@launch
                if (token > 0) {
                    ringTokens[call] = token
                    if (!enabledFor(call)) return@launch
                    if (isExcepted(call)) {
                        exceptedCalls.add(call)
                        CaptureDaemon.excludeRingingCall(this@CallTracker, token)
                    } else {
                        refreshManual()
                        decideAnswer(call, lazy { CallRules.verdict(this@CallTracker, PhoneNumbers.of(call.details.handle)) })
                    }
                    return@launch
                }
            }
        }
    }

    private fun eligibleManual(call: Call): Boolean =
        !destroyed && enabledFor(call) && (ringTokens[call] != null || StatusRepository.autoText(this)) && !isExcepted(call) && calls.size == 1 && call in calls && call.state == Call.STATE_RINGING &&
            call.details.callDirection == Call.Details.DIRECTION_INCOMING &&
            CallTracking.enabled(this) &&
            !EmergencyPause.isEmergency(this, call) && !EmergencyPause.isCallback(call.details) &&
            EmergencyPause.pausedUntil(this, EmergencyPause.telecomLast(call.details)) == null &&
            call !in manualHandled && !AutoText.wasSubmitted(call)

    private fun clearManual() {
        IncomingVoicemail.clear(this, manualToken)
        manualCall = null
        manualToken = null
    }

    private fun refreshManual() {
        val candidate = calls.singleOrNull()?.takeIf { eligibleManual(it) && manualInFlight == null }
        if (candidate === manualCall) return
        clearManual()
        if (candidate == null) return
        val token = java.util.UUID.randomUUID().toString()
        manualCall = candidate
        manualToken = token
        IncomingVoicemail.show(this, token) { sendManual(candidate, token) }
    }

    private fun sendManual(call: Call, token: String) {
        if (manualToken != token || manualInFlight != null || !eligibleManual(call)) return
        if (AnswerDiagnostics.stageOne(this)?.possible != true) {
            Toast.makeText(this, "Check voicemail audio support in Settings first.", Toast.LENGTH_LONG).show()
            return
        }
        pendingTexts.remove(call)?.let(handler::removeCallbacks)
        AutoText.forget(call)
        manualInFlight = call
        clearManual()
        manualScope.launch {
            var commandSent = false
            try {
                val ring = CaptureDaemon.ringingToken(this@CallTracker, call.details.creationTimeMillis)
                if (ring == null || ring <= 0 || (!StatusRepository.autoText(this@CallTracker) && ringTokens[call] != ring)) {
                    error("Voicemail is unavailable for this call.")
                }
                if (!eligibleManual(call)) return@launch
                check(CaptureDaemon.syncGreeting(this@CallTracker)) { "Couldn’t load your greeting. Try again." }
                if (!eligibleManual(call)) return@launch
                rememberMessageCaller(call)
                commandSent = true
                mayHaveVoicemail = true
                val accepted = CaptureDaemon.sendToVoicemail(this@CallTracker, ring)
                if (!accepted) error("Couldn’t confirm voicemail started. The call may have ended or been answered.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@CallTracker, e.message ?: "Couldn’t send this call to voicemail.", Toast.LENGTH_LONG).show()
            } finally {
                // Once sent, a missing acknowledgement must not race an SMS
                // against a voicemail answer that may already be happening.
                if (commandSent && call in calls) manualHandled += call
                manualInFlight = null
                if (!commandSent && eligibleManual(call) && StatusRepository.autoText(this@CallTracker)) scheduleText(call)
                refreshManual()
            }
        }
    }

    private fun scheduleText(call: Call) {
        val details = call.details
        val generation = callGenerations[call] ?: return
        if (!enabledFor(call) || !StatusRepository.autoText(this) || isExcepted(call) || AutoText.wasSubmitted(call)) return
        if (calls.singleOrNull() !== call || details.state != Call.STATE_RINGING ||
            details.callDirection != Call.Details.DIRECTION_INCOMING || call in pendingTexts ||
            manualInFlight === call || call in manualHandled) return
        val seconds = AnsweringMachine.responseDelay(this)
        val elapsed = (System.currentTimeMillis() - details.creationTimeMillis).coerceAtLeast(0)
        val remaining = seconds * 1000L - elapsed
        val task = Runnable {
            pendingTexts.remove(call)
            if (calls.singleOrNull() === call && !isExcepted(call) && manualInFlight !== call && call !in manualHandled) {
                AutoText.send(this, call, generation)
            }
            refreshManual()
        }
        pendingTexts[call] = task
        handler.postDelayed(task, remaining.coerceAtLeast(0))
    }

    override fun onDestroy() {
        destroyed = true
        exceptionWatches.forEach { it() }
        exceptionWatches.clear()
        exceptedCalls.clear()
        answerVersions.clear()
        callGenerations.clear()
        ringTokens.clear()
        clearManual()
        manualScope.cancel()
        calls.forEach(AutoText::forget)
        handler.removeCallbacksAndMessages(null)
        pendingTexts.clear()
        super.onDestroy()
    }

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = changed(call)
        override fun onDetailsChanged(call: Call, details: Call.Details) = changed(call)
    }

    /** The calls the rules left out, so their end is noted as such. */
    private val leftOut = mutableSetOf<Long>()

    /** Emergency calls under way, so their end restarts the pause. */
    private val emergencyCalls = mutableSetOf<Long>()

    override fun onCallAdded(call: Call) {
        call.registerCallback(callback)
        // Before anything else, tracking on or not: the answering machine
        // stands down for a day after it (EmergencyPause).
        if (EmergencyPause.isEmergency(this, call)) {
            emergencyCalls += call.details.creationTimeMillis
            EmergencyPause.noteCall(this)
        }
        // Switched off, and bound all the same - the daemon could not be told
        // to withdraw the permission yet: off means off, nothing is kept.
        if (!CallTracking.enabled(this)) return
        changed(call)
        if (isExcepted(call)) return
        val number = PhoneNumbers.of(call.details.handle)
        val keeps = lazy { CallRules.verdict(applicationContext, number) }
        decideEarly(call, keeps)
    }

    /**
     * The answering machine: should the recorder answer this ringing call,
     * and after how long? Decided here, where the caller is known while the
     * phone rings; the recorder runs the timer, and answers nothing without
     * this go-ahead. Off the main thread: the rules and Do Not Disturb both
     * read the contacts.
     */
    private fun decideAnswer(call: Call, keeps: Lazy<Boolean?>) {
        val ring = ringTokens[call] ?: return
        val context = applicationContext
        if (!enabledFor(call) || isExcepted(call) || !AnsweringMachine.enabled(context) ||
            AnswerDiagnostics.stageOne(context)?.possible != true) return
        if (call.details.callDirection != Call.Details.DIRECTION_INCOMING || call.state != Call.STATE_RINGING) return
        // Sent with the go-ahead, so one that comes too late for its own
        // ring is never taken for the next caller's. The recorder's clock:
        // since boot, the same in both processes, and a wall-clock step
        // before the go-ahead lands does not move it.
        val version = (answerVersions[call] ?: 0) + 1
        answerVersions[call] = version
        val rangAt = android.os.SystemClock.elapsedRealtime()
        val others = calls.size - 1
        val handle = call.details.handle
        val account = call.details.accountHandle?.id
        val emergencyCallback = EmergencyPause.isCallback(call.details)
        val telecomLast = EmergencyPause.telecomLast(call.details)
        // Kept, so the answering machine's screen and the self-test show the
        // pause that Telecom's record starts too.
        EmergencyPause.noteTelecom(context, telecomLast)
        scope.launch {
            // The SIMs as they are now, not as the settings screen last saw
            // them: the default can change, and a SIM come, in between.
            CaptureDaemon.sims(context)?.let { AnswerConditions.remember(context, it) }
            val decision = runCatching {
                AnsweringMachine.decide(
                    ringing(context, others, handle, account, keeps).copy(
                        emergencyCallback = emergencyCallback,
                        emergencyPaused = EmergencyPause.pausedUntil(context, telecomLast) != null,
                    ),
                )
            }.getOrElse { AnsweringMachine.Decision.No("could not decide: ${it.javaClass.simpleName}") }
            when (decision) {
                is AnsweringMachine.Decision.Answer -> {
                    Log.i(TAG, "calltrack: answering machine - answer after ${decision.delaySeconds} s")
                    withContext(Dispatchers.Main.immediate) {
                        if (!destroyed && ringTokens[call] == ring && call.state == Call.STATE_RINGING) {
                            mayHaveVoicemail = true
                            rememberMessageCaller(call)
                        }
                    }
                    goAheads.trySend(GoAhead(context, decision.delaySeconds * 1000, rangAt, ring) {
                        !destroyed && ringTokens[call] == ring && calls.singleOrNull() === call &&
                            call.state == Call.STATE_RINGING && !isExcepted(call) && enabledFor(call) && CallTracking.enabled(context) &&
                            answerVersions[call] == version && manualInFlight !== call && call !in manualHandled
                    })
                }
                is AnsweringMachine.Decision.No -> Log.i(TAG, "calltrack: answering machine - not answering: ${decision.why}")
            }
        }
    }

    private fun ringing(
        context: Context,
        others: Int,
        handle: android.net.Uri?,
        account: String?,
        keeps: Lazy<Boolean?>,
    ): AnsweringMachine.Ringing {
        return AnsweringMachine.Ringing(
            enabled = AnsweringMachine.enabled(context),
            greeting = Greeting.present(context),
            recorderOn = RecorderSwitch.isOn(context),
            trackingOn = CallTracking.enabled(context),
            otherCalls = others,
            // The default SIM's, not necessarily the ringing one's: the
            // app cannot read another SIM's without a phone permission.
            // Unknown counts as roaming: a call is never answered on a guess.
            roaming = runCatching {
                context.getSystemService(android.telephony.TelephonyManager::class.java).isNetworkRoaming
            }.getOrDefault(true),
            rulesKeep = keeps.value,
            delay = AnsweringMachine.responseDelay(context),
            simAllowed = AnswerConditions.simAllowed(context, account),
            // Messenger calls are invisible to this service (Telecom shows it
            // only calls it manages); the audio mode is theirs while they run.
            otherAppCall = runCatching {
                context.getSystemService(android.media.AudioManager::class.java).mode ==
                    android.media.AudioManager.MODE_IN_COMMUNICATION
            }.getOrDefault(false),
        )
    }

    override fun onCallRemoved(call: Call) {
        if (calls.isEmpty() && call.state == Call.STATE_DISCONNECTED) CallMonitorService.lastCallDisconnected()
        exceptedCalls.remove(call)
        answerVersions.remove(call)
        callGenerations.remove(call)
        ringTokens.remove(call)
        refreshDndWatch()
        if (manualCall === call) clearManual()
        manualHandled.remove(call)
        AutoText.forget(call)
        pendingTexts.remove(call)?.let(handler::removeCallbacks)
        call.unregisterCallback(callback)
        val id = call.details.creationTimeMillis
        // The day is counted from the emergency call's end. The network can
        // mark one only once it is up: asked again here.
        if (emergencyCalls.remove(id) || EmergencyPause.isEmergency(this, call)) EmergencyPause.noteCall(this)
        wasActive -= id
        val wasLeftOut = leftOut.remove(id)
        if (!CallTracking.enabled(this)) {
            // Switched off mid-call: nothing more is kept - but a call left
            // out on purpose is still noted as such, or the missed-call check
            // reports it, and the Calls tab marks it red.
            if (wasLeftOut) CallLedger.noteSkipped(this, id, call.details.callDirection == Call.Details.DIRECTION_INCOMING, 0)
            return
        }
        val ended = System.currentTimeMillis()
        CallTracking.update(this, id) { old -> TelecomAccount.ended(old ?: track(call), ended) }
        Log.i(TAG, "calltrack: call ended")
        if (wasLeftOut) {
            val tracked = CallTracking.calls(this).firstOrNull { it.id == id }
            CallLedger.noteSkipped(this, id, tracked?.incoming ?: true, tracked?.seconds ?: 0)
        }
        if (calls.isEmpty() && mayHaveVoicemail) {
            mayHaveVoicemail = false
            collectSoon()
        }
    }

    /** A state change: note an answer or a hang-up. */
    private fun changed(call: Call) {
        if (call.details.state == Call.STATE_RINGING && CallTracking.enabled(this)) {
            callGenerations.getOrPut(call) { StatusRepository.generation(this) }
            if (enabledFor(call)) {
                refreshDndWatch()
                if (StatusRepository.autoText(this)) scheduleText(call)
                else {
                    rememberMessageCaller(call)
                    checkRingingCall(call)
                }
            }
        }
        applyException(call)
        refreshManual()
        if (call.details.state != Call.STATE_RINGING) {
            refreshDndWatch()
            pendingTexts.remove(call)?.let(handler::removeCallbacks)
            AutoText.forget(call)
        }
        if (!CallTracking.enabled(this)) return
        val id = call.details.creationTimeMillis
        val now = System.currentTimeMillis()
        val change = when (call.state) {
            Call.STATE_ACTIVE -> TelecomAccount.Change.ACTIVE
            Call.STATE_DISCONNECTED -> TelecomAccount.Change.DISCONNECTED
            else -> TelecomAccount.Change.OTHER
        }
        CallTracking.update(this, id) { old ->
            TelecomAccount.changed(
                base = old ?: track(call),
                isNew = old == null,
                change = change,
                connectedAt = call.details.connectTimeMillis,
                wasActive = wasActive[id] == true,
                now = now,
            )
        }
        val becameActive = call.state == Call.STATE_ACTIVE && wasActive[id] != true
        wasActive[id] = call.state == Call.STATE_ACTIVE
        // A second call answered - a waiting one, or one the user placed -
        // while the answering machine may be taking a message on the first:
        // the user is back, and this call is theirs. Left to the machine, the
        // greeting would play into it, their microphone stay muted, and the
        // message's limit hang it up. Talking takes the message over, as
        // Talk to them does; with no message being taken the recorder
        // ignores it.
        if (becameActive && calls.size > 1) {
            val context = applicationContext
            val session = CallMonitorService.currentMessageSession
            if (session != null) scope.launch { CaptureDaemon.handOver(context, talk = true, session = session) }
        }
    }

    /** Whether each call was active at its last change, so a repeat of
     *  ACTIVE is not counted as a second answer. */
    private val wasActive = mutableMapOf<Long, Boolean>()

    private fun rememberMessageCaller(call: Call) {
        val details = call.details
        if (call !in calls || details.handlePresentation != android.telecom.TelecomManager.PRESENTATION_ALLOWED ||
            details.handle?.scheme != "tel") return
        val number = PhoneNumbers.digits(PhoneNumbers.of(details.handle))?.takeIf { it.length <= 32 } ?: return
        CallTracking.update(this, details.creationTimeMillis) { (it ?: track(call)).copy(messageNumber = number) }
    }

    private fun track(call: Call): CallTracking.Tracked = CallTracking.Tracked(
        id = call.details.creationTimeMillis,
        incoming = call.details.callDirection == Call.Details.DIRECTION_INCOMING,
        // The number only with the call-log permission: the same consent the
        // call log needs, for the same information.
        number = if (CallLogLookup.granted(this)) PhoneNumbers.of(call.details.handle) else null,
        // Already answered, if Telecom says so: a call first seen here late -
        // tracking switched on mid-call, a service bound mid-call - is not
        // then taken for one nobody answered, and deleted as such.
        activations = listOfNotNull(call.details.connectTimeMillis.takeIf { it > 0 }),
        endedAt = 0L,
        // Both clocks read together, while the call is up and the phone
        // cannot sleep between them: nanoTime is the monotonic clock the
        // daemon stamps its packets with (AudioTimestamp.TIMEBASE_MONOTONIC
        // and System.nanoTime there). Issue #40: see PacketClock.
        clockOffset = System.currentTimeMillis() - System.nanoTime() / 1_000_000,
    )

    private fun decideEarly(call: Call, keeps: Lazy<Boolean?>) {
        val context = applicationContext
        val id = call.details.creationTimeMillis
        val skip = TelecomAccount.skipEarly(
            id = id,
            callsUp = calls.size,
            now = System.currentTimeMillis(),
            tracked = { CallTracking.calls(context) },
            automatic = { CoverageHistory.automatic(context) },
            keeps = { keeps.value },
        )
        if (!skip) return
        leftOut += id
    }

    /**
     * The last call is over: collect what the daemon recorded, a few seconds
     * on, once it has finished the file. The daemon's own wake does the same
     * and usually first; this is for the times its wake does not land.
     */
    private fun collectSoon() {
        val context = applicationContext
        scope.launch {
            // Twice: the daemon ends its file only once the audio mode has
            // left the call, which can be seconds after Telecom says so - and
            // a file still being written is not listed. Collecting twice is
            // harmless (RecordingSaver claims and checks).
            for (wait in COLLECT_AFTER_MS) {
                delay(wait)
                runCatching { RecordingSaver.reconcile(context) }.rethrowingCancellation()
                    .onFailure { Log.w(TAG, "calltrack: collecting after the call failed", it) }
            }
        }
    }

    private companion object {
        const val TAG = "IPick"
        /** Five seconds after the last call, and fifteen after that. */
        val COLLECT_AFTER_MS = longArrayOf(5_000L, 15_000L)

        /** Not tied to the service: Telecom unbinds it the moment the last
         *  call goes, and the collection after it must still run. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** A go-ahead for the answering machine: the delay, and when the
         *  ring it is for was seen. */
        class GoAhead(val context: Context, val delayMs: Int, val rangAt: Long, val ring: Long, val stillAllowed: () -> Boolean)

        val goAheads = Channel<GoAhead>(Channel.UNLIMITED).also { channel ->
            scope.launch {
                for (go in channel) {
                    if (!AnsweringMachine.enabled(go.context) || !withContext(Dispatchers.Main.immediate) { go.stillAllowed() }) continue
                    if (!CaptureDaemon.syncGreeting(go.context) || !AnsweringMachine.enabled(go.context) ||
                        !withContext(Dispatchers.Main.immediate) { go.stillAllowed() }) continue
                    val state = CaptureDaemon.goAhead(go.context, go.delayMs, go.rangAt, go.ring)
                    Log.i(TAG, "calltrack: answering machine go-ahead -> ${state ?: "the recorder did not take it"}")
                }
            }
        }
    }
}

package app.ipick.voicemail.ui

import android.app.Activity
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ipick.voicemail.DeepLink
import app.ipick.voicemail.capture.CaptureDaemon
import app.ipick.voicemail.recordings.RecordingSaver
import kotlinx.coroutines.delay
import kotlin.system.exitProcess
import kotlinx.coroutines.flow.conflate

/**
 * Routes between setup and the running app.
 *
 * There is no "settings" concept here on purpose. Either the phone can record a
 * call or it cannot, and whichever is true is what the screen shows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    // Re-checked every time the screen comes forward, not once when it is
    // created. Everything shown here lives outside this process - the daemon,
    // the pairing, the recordings on disk - and the interesting moment is
    // exactly the one where the user returns from somewhere else: from Settings
    // after pairing, or from a call that just produced a recording.
    //
    // Doing this only on first composition is why a finished call showed
    // nothing until "Check" was pressed. Making the user ask is not a refresh
    // strategy.
    LifecycleResumeEffect(Unit) {
        vm.refreshSetup()
        onPauseOrDispose { }
    }

    // Out of sight, the scrubber is not driven and the fill-in stops; a
    // recording plays on, under the system's media controls. Back in sight,
    // the screen shows what played on meanwhile. See MainViewModel.screenHidden
    // and fillInWaveforms.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        vm.screenHidden()
        vm.stopFillingIn()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.screenShown() }

    // A recording saved while this screen is up - by the per-call service
    // seconds after a hang-up, or by the keep-alive's reconcile right after an
    // open - would otherwise wait for the next resume to appear. Measured: the
    // file was in the folder and not in the list until the app was reopened.
    // Conflated behind a short pause, so a reconcile saving several files is
    // one refresh.
    LaunchedEffect(Unit) {
        RecordingSaver.saved.conflate().collect {
            delay(250)
            vm.refresh()
        }
    }

    // A notification's button - play this call, share it, run the self-test,
    // pair again. The activity hands the link over; this acts on it once.
    val link by DeepLink.pending.collectAsStateWithLifecycle()
    LaunchedEffect(link) { link?.let(vm::open) }

    // The recorder coming back is the other change that happens out of sight:
    // the keep-alive revives it when Wi-Fi returns, and the header kept saying
    // "needs Wi-Fi" until the next resume. Measured, after a reboot.
    LaunchedEffect(Unit) {
        CaptureDaemon.changed.collect { vm.refresh() }
    }

    // And Wi-Fi arriving while this screen is up starts the revive from here,
    // so the header goes to "Starting..." at once rather than waiting for the
    // keep-alive job to be scheduled, run, and report back. The ViewModel
    // decides whether there is anything to revive.
    val appContext = LocalContext.current.applicationContext
    DisposableEffect(Unit) {
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = vm.onWifiAvailable()
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching { cm?.registerNetworkCallback(request, callback) }
        onDispose { runCatching { cm?.unregisterNetworkCallback(callback) } }
    }

    // MediaStore will occasionally not take our word for it that a recording
    // is ours to delete, and hands back a request for the user to approve.
    // Launching it needs an Activity, so it lives here, above both screens -
    // deleting one recording happens on the home screen and deleting all of
    // them happens in settings, and they should not each own a copy.
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> vm.onDeleteConsentResult(result.resultCode == Activity.RESULT_OK) }

    LaunchedEffect(state.deleteConsent) {
        state.deleteConsent?.let {
            consentLauncher.launch(IntentSenderRequest.Builder(it).build())
            vm.onDeleteConsentShown()
        }
    }

    // Reset revokes this app's own permissions, and the system only applies
    // that once the app is out of the way. Closing is part of the operation,
    // not a courtesy.
    val activity = LocalActivity.current
    LaunchedEffect(state.resetDone) {
        if (!state.resetDone) return@LaunchedEffect
        activity?.finishAndRemoveTask()
        // Handing back a permission only takes effect when the process dies,
        // and Android decides when that is - for a cached process it can be a
        // long while. Ending it here is what makes "start fresh" mean now
        // rather than eventually, and the dialog promised the app would close.
        delay(300)
        exitProcess(0)
    }

    VoicemailScreen(state, vm)
}

/**
 * Text that must stay on one line and shrinks to fit, from [max] (which grows
 * with the phone's font size, as text should) down to [min] in dp (which does
 * not: an sp floor doubles at twice the size, and a line that cannot shrink
 * past it overflows - over the header's switch, out of a connected button).
 */
@Composable
@ReadOnlyComposable
internal fun oneLineAutoSize(min: Dp, max: TextUnit): TextAutoSize =
    with(LocalDensity.current) { TextAutoSize.StepBased(minFontSize = min.toSp(), maxFontSize = max) }

// SPDX-License-Identifier: AGPL-3.0-only

package app.farsofa.vr.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Base64
import android.util.Log
import java.io.File
import org.json.JSONObject
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.farsofa.vr.R
import app.farsofa.vr.console.ConsoleStore
import app.farsofa.vr.console.DiscoveredConsole
import app.farsofa.vr.console.Discovery
import app.farsofa.vr.console.RegisteredConsole
import app.farsofa.vr.console.toHexId
import app.farsofa.vr.core.Native
import app.farsofa.vr.psn.PsnAccount
import app.farsofa.vr.psn.PsnAuth
import app.farsofa.vr.psn.PsnStore
import app.farsofa.vr.stream.ImmersiveActivity
import app.farsofa.vr.stream.StreamActivity
import app.farsofa.vr.stream.StreamSession
import android.Manifest
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import app.farsofa.vr.input.DualSenseLink
import app.farsofa.vr.input.PadPrefs
import app.farsofa.vr.input.PadLearner
import app.farsofa.vr.input.PadMappingStore
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.mutableIntStateOf
import app.farsofa.vr.psn.CaBundle
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var discovery: Discovery
    private lateinit var consoleStore: ConsoleStore
    private lateinit var psnStore: PsnStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        discovery = Discovery(this)
        consoleStore = ConsoleStore(this)
        psnStore = PsnStore(this)
        importPairing()
        if (intent.getBooleanExtra(ImmersiveActivity.EXTRA_DEMO, false) && applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            startActivity(
                Intent(this, ImmersiveActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtras(intent)
            )
            finishAndRemoveTask()
            return
        }
        setContent { FarsofaTheme { App() } }
    }

    /**
     * Development builds only: takes a pairing made by another client (the keys a console hands out
     * are not tied to the device) from a file dropped into the app's private folder, then deletes it.
     */
    private fun importPairing() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val file = File(filesDir, "import-console.json")
        if (!file.exists()) return
        try {
            val item = JSONObject(file.readText())
            consoleStore.save(
                RegisteredConsole(
                    id = item.getString("id"),
                    nickname = item.getString("nickname"),
                    registKey = Base64.decode(item.getString("registKey"), Base64.NO_WRAP),
                    rpKey = Base64.decode(item.getString("rpKey"), Base64.NO_WRAP),
                    lastHost = item.optString("lastHost").ifEmpty { null },
                )
            )
            Log.i("Farsofa", "Imported a pairing")
        } catch (e: Exception) {
            Log.e("Farsofa", "Could not import the pairing", e)
        }
        file.delete()
    }

    override fun onStart() {
        super.onStart()
        discovery.start()
    }

    override fun onStop() {
        super.onStop()
        discovery.stop()
    }

    @Composable
    private fun App() {
        val scope = rememberCoroutineScope()
        var account by remember { mutableStateOf(psnStore.load()) }
        var registered by remember { mutableStateOf(consoleStore.all()) }
        val discovered by discovery.consoles.collectAsStateWithLifecycle()

        var signingIn by remember { mutableStateOf(false) }
        var browserSignIn by remember { mutableStateOf(false) }
        val loginUrl = remember { PsnAuth.loginUrl(Native.generateDuid()) }
        var exchangingCode by remember { mutableStateOf(false) }
        var signInError by remember { mutableStateOf<String?>(null) }
        var pairing by remember { mutableStateOf<DiscoveredConsole?>(null) }
        var remoteConsoles by remember { mutableStateOf<RemoteConsoles>(RemoteConsoles.Loading) }
        var remoteAttempt by remember { mutableIntStateOf(0) }
        // The account's consoles are asked for once per sign-in, and again when the player says so.
        LaunchedEffect(account?.refreshToken, remoteAttempt) {
            val signedIn = account ?: return@LaunchedEffect
            remoteConsoles = RemoteConsoles.Loading
            remoteConsoles = try {
                val valid = PsnAuth.fresh(signedIn)
                if (valid !== signedIn) { psnStore.save(valid); account = valid }
                withContext(Dispatchers.IO) {
                    CaBundle.ensure(this@MainActivity)
                    RemoteConsoles.parse(Native.psnListDevices(valid.accessToken))
                }
            } catch (e: Exception) {
                RemoteConsoles.Failed(e.message ?: "sign-in")
            }
        }
        var wakingId by remember { mutableStateOf<String?>(null) }
        var notice by remember { mutableStateOf(intent.getStringExtra(EXTRA_NOTICE)) }

        val wakeTimeout = stringResource(R.string.wake_timeout)
        val signInFailed = stringResource(R.string.sign_in_failed)

        // Keep nudging a console in rest mode until discovery reports it ready, then start playing.
        LaunchedEffect(wakingId) {
            val id = wakingId ?: return@LaunchedEffect
            val console = registered.firstOrNull { it.id == id } ?: return@LaunchedEffect
            repeat(WAKE_ATTEMPTS) {
                val seen = discovery.consoles.value.firstOrNull { it.id == id }
                if (seen?.state == DiscoveredConsole.State.READY) {
                    wakingId = null
                    play(seen.host, console)
                    return@LaunchedEffect
                }
                (seen?.host ?: console.lastHost)?.let { discovery.wake(it, console) }
                delay(WAKE_INTERVAL_MS)
            }
            wakingId = null
            notice = wakeTimeout
        }

        val exchangeCode: (String) -> Unit = { code ->
            if (!exchangingCode) {
                exchangingCode = true
                scope.launch {
                    try {
                        val signedIn = PsnAuth.signIn(code)
                        psnStore.save(signedIn)
                        account = signedIn
                        signInError = null
                    } catch (e: Exception) {
                        signInError = signInFailed
                    }
                    exchangingCode = false
                    signingIn = false
                    browserSignIn = false
                }
            }
        }

        val currentAccount = account
        when {
            signingIn -> LoginScreen(
                url = loginUrl,
                busy = exchangingCode,
                onCode = exchangeCode,
                onCancel = { signingIn = false },
            )

            browserSignIn -> BrowserLoginScreen(
                busy = exchangingCode,
                onOpenBrowser = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(loginUrl))) },
                onAddress = { address ->
                    val code = PsnAuth.codeFromRedirect(address)
                    if (code != null) exchangeCode(code)
                    code != null
                },
                onCancel = { browserSignIn = false },
            )

            currentAccount == null -> WelcomeScreen(
                error = signInError,
                onSignIn = { signInError = null; signingIn = true },
                onBrowserSignIn = { signInError = null; browserSignIn = true },
            )

            else -> {
                ConsoleListScreen(
                    rows = consoleRows(discovered, registered),
                    wakingId = wakingId,
                    notice = notice,
                    onPlay = { row ->
                        notice = null
                        val console = row.registered ?: return@ConsoleListScreen
                        val seen = row.discovered
                        if (seen?.state == DiscoveredConsole.State.READY) play(seen.host, console)
                        else wakingId = console.id
                    },
                    onPair = { row -> notice = null; pairing = row.discovered },
                    onForget = { row ->
                        row.registered?.let { consoleStore.remove(it.id) }
                        registered = consoleStore.all()
                    },
                    remote = remoteConsoles,
                    onPlayRemote = { console ->
                        notice = null
                        scope.launch {
                            try {
                                val valid = PsnAuth.fresh(currentAccount)
                                if (valid !== currentAccount) { psnStore.save(valid); account = valid }
                                playRemote(console.duid, valid)
                            } catch (e: Exception) {
                                remoteConsoles = RemoteConsoles.Failed(e.message ?: "sign-in")
                            }
                        }
                    },
                    onRefreshRemote = { remoteAttempt++ },
                    onLearnPad = {
                        learnDevice = null
                        learner = PadLearner()
                    },
                    onSignOut = {
                        psnStore.save(null)
                        account = null
                    },
                )
                learner?.let { current ->
                    PadLearnDialog(
                        tick = learnTick,
                        target = current.target,
                        step = current.progress,
                        deviceName = learnDevice?.name,
                        finished = current.finished,
                        onSkip = { current.skip(); afterLearningEvent(current) },
                        onReset = {
                            learnDevice?.let { PadMappingStore(this@MainActivity).clear(PadMappingStore.deviceKey(it.vendorId, it.productId)) }
                            learner = null
                        },
                        onClose = { learner = null },
                    )
                }
                pairing?.let { console ->
                    PairDialog(
                        console = console,
                        pair = { pin, onResult -> pair(console, currentAccount, pin, onResult) },
                        onPaired = {
                            registered = consoleStore.all()
                            pairing = null
                        },
                        onDismiss = { pairing = null },
                    )
                }
            }
        }
    }

    // --- Teaching the app an unusual gamepad ---

    private var learner by mutableStateOf<PadLearner?>(null)
    private var learnTick by mutableIntStateOf(0)
    private var learnDevice: InputDevice? = null

    private fun learning(device: InputDevice?): PadLearner? {
        val current = learner ?: return null
        if (device == null || !(device.supportsSource(InputDevice.SOURCE_GAMEPAD) || device.supportsSource(InputDevice.SOURCE_JOYSTICK))) return null
        // The first gamepad to answer is the one being taught; another one joining in would confuse it.
        if (learnDevice == null) learnDevice = device
        return if (learnDevice?.id == device.id) current else null
    }

    private fun afterLearningEvent(current: PadLearner) {
        learnTick++
        val device = learnDevice
        if (current.finished && device != null) {
            PadMappingStore(this).save(PadMappingStore.deviceKey(device.vendorId, device.productId), current.result)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val current = learning(event.device) ?: return super.dispatchKeyEvent(event)
        if (event.repeatCount == 0 && (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP)) {
            current.onKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN)
            afterLearningEvent(current)
        }
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val device = event.device
        val current = (if (event.isFromSource(InputDevice.SOURCE_JOYSTICK)) learning(device) else null)
            ?: return super.dispatchGenericMotionEvent(event)
        if (event.action == MotionEvent.ACTION_MOVE && device != null) {
            val values = device.motionRanges.filter { it.isFromSource(InputDevice.SOURCE_JOYSTICK) }
                .associate { it.axis to event.getAxisValue(it.axis) }
            current.onAxes(values)
            afterLearningEvent(current)
        }
        return true
    }

    // Talking back to a DualSense needs the Bluetooth permission. It is asked for once, here in the
    // panel, because a system dialog on top of the immersive screen is worse; the game starts either way.
    private var afterPermission: (() -> Unit)? = null
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        afterPermission?.invoke()
        afterPermission = null
    }

    private fun playRemote(consoleDuid: String, account: PsnAccount) {
        val target = if (packageManager.hasSystemFeature(FEATURE_STANDALONE_VR)) ImmersiveActivity::class.java else StreamActivity::class.java
        val intent = StreamSession.remoteIntent(this, target, consoleDuid, account.accessToken, account.accountId)
        if (target == ImmersiveActivity::class.java) {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finishAndRemoveTask()
        } else {
            startActivity(intent)
        }
    }

    private fun play(host: String, console: RegisteredConsole) {
        val asked = getPreferences(MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 31 && PadPrefs(this).needsLink && !DualSenseLink.available(this)
            && !asked.getBoolean("bluetoothAsked", false)
        ) {
            asked.edit().putBoolean("bluetoothAsked", true).apply()
            afterPermission = { play(host, console) }
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        if (console.lastHost != host) {
            consoleStore.save(RegisteredConsole(console.id, console.nickname, console.registKey, console.rpKey, host))
        }
        if (packageManager.hasSystemFeature(FEATURE_STANDALONE_VR)) {
            // On a headset the stream replaces the panel with the immersive screen.
            startActivity(
                StreamSession.intent(this, ImmersiveActivity::class.java, host, console)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            finishAndRemoveTask()
        } else {
            startActivity(StreamSession.intent(this, StreamActivity::class.java, host, console))
        }
    }

    /** Registers this headset with [console]. [onResult] runs on the main thread. */
    private fun pair(console: DiscoveredConsole, account: PsnAccount, pin: Int, onResult: (Boolean) -> Unit) {
        var ptr = 0L
        val listener = object : Native.RegistListener {
            override fun onRegistResult(status: Int, nickname: String?, mac: ByteArray?, registKey: ByteArray?, rpKey: ByteArray?) {
                runOnUiThread {
                    Native.registFree(ptr)
                    val paired = status == Native.REGIST_SUCCESS && mac != null && registKey != null && rpKey != null
                    if (paired) {
                        consoleStore.save(
                            RegisteredConsole(
                                id = mac!!.toHexId(),
                                nickname = nickname?.ifEmpty { null } ?: console.name,
                                registKey = registKey!!,
                                rpKey = rpKey!!,
                                lastHost = console.host,
                            )
                        )
                    }
                    onResult(paired)
                }
            }
        }
        ptr = Native.registStart(console.host, false, account.accountId, pin, listener)
        if (ptr == 0L) onResult(false)
    }

    companion object {
        /** Message to show on the console list, set when a stream ended with something to report. */
        const val EXTRA_NOTICE = "notice"

        /** Quest headsets report this rather than the generic Android head-tracking feature. */
        private const val FEATURE_STANDALONE_VR = "oculus.hardware.standalone_vr"
        private const val WAKE_ATTEMPTS = 30
        private const val WAKE_INTERVAL_MS = 3000L
    }
}

/** One line in the console list: a console that is on the network, paired, or both. */
class ConsoleRow(val discovered: DiscoveredConsole?, val registered: RegisteredConsole?) {
    val id: String get() = discovered?.id ?: registered!!.id
    val name: String get() = registered?.nickname ?: discovered!!.name
}

fun consoleRows(discovered: List<DiscoveredConsole>, registered: List<RegisteredConsole>): List<ConsoleRow> {
    val online = discovered.filter { it.ps5 }
    val paired = registered.map { console -> ConsoleRow(online.firstOrNull { it.id == console.id }, console) }
    val unpaired = online.filter { seen -> registered.none { it.id == seen.id } }.map { ConsoleRow(it, null) }
    return paired + unpaired
}

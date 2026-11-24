// SPDX-License-Identifier: AGPL-3.0-only

package app.farsofa.vr.ui

import app.farsofa.vr.core.Report
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import app.farsofa.vr.input.PadMapping
import androidx.compose.ui.res.stringArrayResource
import app.farsofa.vr.input.PadPrefs
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Switch
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.farsofa.vr.R
import app.farsofa.vr.console.DiscoveredConsole
import app.farsofa.vr.psn.PsnAuth

private val Accent = Color(0xFF5B8CFF)
private val Ready = Color(0xFF4CD08A)
private val Resting = Color(0xFFF2B544)
private val Offline = Color(0xFF6B7280)

private val FarsofaColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Color(0xFF0B0E14),
    onBackground = Color(0xFFE8EBF2),
    surface = Color(0xFF151A24),
    onSurface = Color(0xFFE8EBF2),
    surfaceVariant = Color(0xFF1D2431),
    onSurfaceVariant = Color(0xFFA3ACBD),
    error = Color(0xFFFF6B6B),
)

@Composable
fun FarsofaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = FarsofaColors) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}

@Composable
fun WelcomeScreen(error: String?, onSignIn: () -> Unit, onBrowserSignIn: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.app_name), fontSize = 44.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.welcome_body),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 520.dp),
        )
        Spacer(Modifier.height(10.dp))
        // The store asks streaming apps to say so, and that a good connection is needed, when they open.
        Text(
            stringResource(R.string.streaming_notice),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 520.dp),
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onSignIn, modifier = Modifier.height(56.dp).widthIn(min = 280.dp)) {
            Text(stringResource(R.string.welcome_sign_in), fontSize = 18.sp)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBrowserSignIn) { Text(stringResource(R.string.welcome_browser_sign_in)) }
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Sign-in through the headset's own browser, for when the embedded page refuses to load. The player
 * signs in there, lands on a blank page, and pastes that page's address here.
 */
@Composable
fun BrowserLoginScreen(busy: Boolean, onOpenBrowser: () -> Unit, onAddress: (String) -> Boolean, onCancel: () -> Unit) {
    var address by remember { mutableStateOf("") }
    var rejected by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.browser_login_title), fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.browser_login_step_1), color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onOpenBrowser, enabled = !busy, modifier = Modifier.height(52.dp)) {
                Text(stringResource(R.string.browser_login_open), fontSize = 17.sp)
            }
            Text(stringResource(R.string.browser_login_step_2), color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = address,
                onValueChange = { address = it.trim(); rejected = false },
                label = { Text(stringResource(R.string.browser_login_address_label)) },
                singleLine = true,
                enabled = !busy,
                isError = rejected,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            if (rejected) {
                Text(stringResource(R.string.browser_login_rejected), color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { rejected = !onAddress(address) },
                    enabled = address.isNotEmpty() && !busy,
                    modifier = Modifier.height(52.dp),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.browser_login_continue), fontSize = 17.sp)
                }
                TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(url: String, busy: Boolean, onCode: (String) -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.login_title), fontSize = 18.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Present as a regular browser; the sign-in page turns embedded web views away.
                        settings.userAgentString = settings.userAgentString.replace("; wv", "")
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            private var delivered = false

                            private fun intercept(target: String?): Boolean {
                                val code = target?.let(PsnAuth::codeFromRedirect) ?: return false
                                if (!delivered) {
                                    delivered = true
                                    onCode(code)
                                }
                                return true
                            }

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                intercept(request.url.toString())

                            override fun onPageStarted(view: WebView, target: String?, favicon: Bitmap?) {
                                if (intercept(target)) view.stopLoading()
                            }
                        }
                        loadUrl(url)
                    }
                },
            )
            if (busy) {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
fun ConsoleListScreen(
    rows: List<ConsoleRow>,
    wakingId: String?,
    notice: String?,
    onPlay: (ConsoleRow) -> Unit,
    onPair: (ConsoleRow) -> Unit,
    onForget: (ConsoleRow) -> Unit,
    remote: RemoteConsoles,
    onPlayRemote: (RemoteConsole) -> Unit,
    onRefreshRemote: () -> Unit,
    onLearnPad: () -> Unit,
    onSignOut: () -> Unit,
) {
    var controllerOpen by remember { mutableStateOf(false) }
    // Shown by itself the first time, while there is no console to play on yet; after that, on request.
    val context = LocalContext.current
    val guidePrefs = remember { context.getSharedPreferences("guide", android.content.Context.MODE_PRIVATE) }
    var guideOpen by remember { mutableStateOf(rows.none { it.registered != null } && !guidePrefs.getBoolean("seen", false)) }
    var reportOpen by remember { mutableStateOf(false) }
    if (reportOpen) ReportDialog(onDismiss = { reportOpen = false })
    if (guideOpen) SetupGuideDialog(onDismiss = { guideOpen = false; guidePrefs.edit().putBoolean("seen", true).apply() })
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.consoles_title),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onLearnPad) { Text(stringResource(R.string.pad_learn_open)) }
            TextButton(onClick = { controllerOpen = true }) { Text(stringResource(R.string.controller_title)) }
            TextButton(onClick = onSignOut) { Text(stringResource(R.string.sign_out)) }
        }
        if (controllerOpen) ControllerDialog(onDismiss = { controllerOpen = false }, onLearnPad = { controllerOpen = false; onLearnPad() })
        Text(stringResource(R.string.streaming_notice), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        if (notice != null) {
            Spacer(Modifier.height(8.dp))
            Text(notice, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(20.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { SectionTitle(stringResource(R.string.consoles_local), stringResource(R.string.consoles_local_hint)) }
                    TextButton(onClick = { guideOpen = true }) { Text(stringResource(R.string.guide_open)) }
                }
            }
            if (rows.isEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(stringResource(R.string.consoles_searching), fontSize = 18.sp)
                            Text(stringResource(R.string.consoles_searching_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        }
                    }
                }
            } else {
                items(rows, key = { it.id }) { row ->
                    ConsoleCard(row, waking = wakingId == row.id, busy = wakingId != null, onPlay, onPair, onForget)
                }
            }

            item {
                Spacer(Modifier.height(10.dp))
                SectionTitle(stringResource(R.string.consoles_remote), stringResource(R.string.consoles_remote_hint))
            }
            when (remote) {
                RemoteConsoles.Loading -> item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(16.dp))
                        Text(stringResource(R.string.consoles_remote_loading), fontSize = 16.sp)
                    }
                }
                is RemoteConsoles.Failed -> item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.consoles_remote_error, remote.reason), color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = onRefreshRemote) { Text(stringResource(R.string.consoles_remote_retry)) }
                    }
                }
                is RemoteConsoles.Found -> if (remote.consoles.isEmpty()) {
                    item { Text(stringResource(R.string.consoles_remote_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(remote.consoles, key = { "remote-" + it.duid }) { console ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.surface).padding(horizontal = 24.dp, vertical = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(14.dp).clip(CircleShape).background(if (console.enabled) Accent else Offline))
                            Spacer(Modifier.width(18.dp))
                            Column(Modifier.weight(1f)) {
                                Text(console.name, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                                if (!console.enabled) Text(stringResource(R.string.consoles_remote_off), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Button(onClick = { onPlayRemote(console) }, enabled = console.enabled && wakingId == null, modifier = Modifier.height(52.dp)) {
                                Text(stringResource(R.string.console_play_remote), fontSize = 17.sp)
                            }
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { reportOpen = true }) { Text(stringResource(R.string.report_open)) }
            }
        }
    }
}

/**
 * Sends the app's diary of its last sessions to the developer, when the player asks. Says what goes
 * and what does not, and afterwards shows the code to quote when asking for help.
 */
@Composable
fun ReportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(stringResource(R.string.report_title)) },
        text = {
            Column {
                val sent = code
                if (sent != null) {
                    Text(stringResource(R.string.report_sent))
                    Spacer(Modifier.height(12.dp))
                    Text(sent, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.report_sent_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(stringResource(R.string.report_body))
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.report_privacy), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    if (failed) {
                        Spacer(Modifier.height(10.dp))
                        Text(stringResource(R.string.report_failed), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            if (code != null) {
                Button(onClick = onDismiss) { Text(stringResource(R.string.controller_done)) }
            } else {
                Button(
                    enabled = !sending,
                    onClick = {
                        sending = true
                        failed = false
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching { Report.send(context) } }
                            sending = false
                            result.onSuccess { code = it.ifEmpty { "OK" } }.onFailure { failed = true }
                        }
                    },
                ) {
                    if (sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.report_send))
                }
            }
        },
        dismissButton = {
            if (code == null) OutlinedButton(onClick = onDismiss, enabled = !sending) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** What has to be switched on at the console before any of this works, in the order it is done. */
@Composable
fun SetupGuideDialog(onDismiss: () -> Unit) {
    val steps = stringArrayResource(R.array.guide_steps)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.guide_title)) },
        text = {
            Column {
                Text(stringResource(R.string.guide_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                steps.forEachIndexed { index, step ->
                    Row(Modifier.padding(vertical = 6.dp)) {
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(Accent),
                            contentAlignment = Alignment.Center,
                        ) { Text((index + 1).toString(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                        Spacer(Modifier.width(12.dp))
                        Text(step, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text(stringResource(R.string.guide_done)) } },
    )
}

@Composable
private fun SectionTitle(title: String, hint: String?) {
    Column {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (hint != null) Text(hint, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A console of the account, as PlayStation Network lists it. */
class RemoteConsole(val duid: String, val name: String, val enabled: Boolean)

/** What is known about the account's consoles for playing away from home. */
sealed interface RemoteConsoles {
    data object Loading : RemoteConsoles
    class Failed(val reason: String) : RemoteConsoles
    class Found(val consoles: List<RemoteConsole>) : RemoteConsoles

    companion object {
        /** Reads the library's answer: "ok" and one line per console, or "error" and why. */
        fun parse(lines: Array<String>?): RemoteConsoles {
            if (lines == null || lines.isEmpty()) return Failed("no answer")
            if (lines[0] != "ok") return Failed(lines.getOrElse(1) { "unknown" })
            return Found(lines.drop(1).mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 3 || parts[0].length != 64) null else RemoteConsole(parts[0], parts[1].ifBlank { "PS5" }, parts[2] == "1")
            })
        }
    }
}

@Composable
private fun ConsoleCard(
    row: ConsoleRow,
    waking: Boolean,
    busy: Boolean,
    onPlay: (ConsoleRow) -> Unit,
    onPair: (ConsoleRow) -> Unit,
    onForget: (ConsoleRow) -> Unit,
) {
    val state = row.discovered?.state
    val (dot, statusText) = when {
        waking -> Resting to stringResource(R.string.console_waking)
        state == DiscoveredConsole.State.READY ->
            Ready to (row.discovered?.runningApp ?: stringResource(R.string.console_ready))
        state == DiscoveredConsole.State.STANDBY -> Resting to stringResource(R.string.console_standby)
        else -> Offline to stringResource(R.string.console_offline)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(row.name, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(statusText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            row.registered == null ->
                Button(onClick = { onPair(row) }, enabled = !busy, modifier = Modifier.height(52.dp)) {
                    Text(stringResource(R.string.console_pair), fontSize = 17.sp)
                }

            waking -> CircularProgressIndicator(Modifier.size(32.dp))

            else -> {
                TextButton(onClick = { onForget(row) }, enabled = !busy) {
                    Text(stringResource(R.string.console_forget))
                }
                Spacer(Modifier.width(8.dp))
                // An offline console may still be reachable at its last address, so playing stays available.
                Button(
                    onClick = { onPlay(row) },
                    enabled = !busy && (row.discovered != null || row.registered.lastHost != null),
                    modifier = Modifier.height(52.dp),
                ) {
                    Text(
                        stringResource(
                            if (state == DiscoveredConsole.State.READY) R.string.console_play
                            else R.string.console_wake_and_play
                        ),
                        fontSize = 17.sp,
                    )
                }
            }
        }
    }
}

/** What a DualSense does beyond buttons and sticks. Changes apply to the next game started. */
@Composable
fun ControllerDialog(onDismiss: () -> Unit, onLearnPad: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PadPrefs(context) }
    var triggers by remember { mutableStateOf(prefs.triggers) }
    var lights by remember { mutableStateOf(prefs.lights) }
    var motion by remember { mutableStateOf(prefs.motion) }
    var fineHaptics by remember { mutableStateOf(prefs.fineHaptics) }
    var smooth by remember { mutableStateOf(prefs.smoothMotion) }

    @Composable
    fun option(title: Int, detail: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(stringResource(title), fontWeight = FontWeight.Medium)
                Text(stringResource(detail), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.controller_title)) },
        text = {
            Column {
                Text(stringResource(R.string.controller_intro), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                option(R.string.controller_triggers, R.string.controller_triggers_detail, triggers) { triggers = it; prefs.triggers = it }
                option(R.string.controller_lights, R.string.controller_lights_detail, lights) { lights = it; prefs.lights = it }
                option(R.string.controller_motion, R.string.controller_motion_detail, motion) { motion = it; prefs.motion = it }
                option(R.string.controller_haptics, R.string.controller_haptics_detail, fineHaptics) { fineHaptics = it; prefs.fineHaptics = it }
                option(R.string.option_smooth, R.string.option_smooth_detail, smooth) { smooth = it; prefs.smoothMotion = it }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text(stringResource(R.string.controller_done)) } },
        dismissButton = { OutlinedButton(onClick = onLearnPad) { Text(stringResource(R.string.pad_learn_open)) } },
    )
}

/**
 * A gamepad seen from above, with the control being asked for lit up. [target] is a button's bit
 * number, a trigger or a stick direction, as in [PadMapping].
 */
@Composable
fun PadPicture(target: Int, modifier: Modifier = Modifier) {
    val body = MaterialTheme.colorScheme.surface
    val part = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val lit = Accent
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun colour(vararg targets: Int) = if (target in targets) lit else part
        fun pill(x: Float, y: Float, pw: Float, ph: Float, vararg targets: Int) =
            drawRoundRect(colour(*targets), Offset(x * w, y * h), Size(pw * w, ph * h), CornerRadius(ph * h / 2))

        // Triggers and shoulder buttons, peeking out above the body.
        pill(0.12f, 0.02f, 0.16f, 0.10f, PadMapping.L2)
        pill(0.72f, 0.02f, 0.16f, 0.10f, PadMapping.R2)
        pill(0.10f, 0.14f, 0.20f, 0.08f, 8)
        pill(0.70f, 0.14f, 0.20f, 0.08f, 9)
        drawRoundRect(body, Offset(0.04f * w, 0.20f * h), Size(0.92f * w, 0.72f * h), CornerRadius(0.22f * h))

        // D-pad on the left: left, right, up, down.
        val pad = 0.045f * w
        val dx = 0.20f * w
        val dy = 0.44f * h
        drawRect(colour(4), Offset(dx - 1.5f * pad, dy - pad / 2), Size(pad, pad))
        drawRect(colour(5), Offset(dx + 0.5f * pad, dy - pad / 2), Size(pad, pad))
        drawRect(colour(6), Offset(dx - pad / 2, dy - 1.5f * pad), Size(pad, pad))
        drawRect(colour(7), Offset(dx - pad / 2, dy + 0.5f * pad), Size(pad, pad))

        // Face buttons on the right: cross below, circle right, square left, triangle above.
        val fx = 0.80f * w
        val r = 0.028f * w
        drawCircle(colour(0), r, Offset(fx, dy + 2.2f * r))
        drawCircle(colour(1), r, Offset(fx + 2.2f * r, dy))
        drawCircle(colour(2), r, Offset(fx - 2.2f * r, dy))
        drawCircle(colour(3), r, Offset(fx, dy - 2.2f * r))

        // Create, touchpad, options, PS in the middle.
        pill(0.31f, 0.30f, 0.035f, 0.10f, 13)
        drawRoundRect(colour(14), Offset(0.36f * w, 0.27f * h), Size(0.28f * w, 0.24f * h), CornerRadius(0.03f * h))
        pill(0.655f, 0.30f, 0.035f, 0.10f, 12)
        drawCircle(colour(15), 0.022f * w, Offset(0.5f * w, 0.62f * h))

        // Sticks; pressing one is L3 or R3, and a push is shown as a dot in that direction.
        val stick = 0.062f * w
        val sy = 0.72f * h
        for ((cx, press, pushX, pushY) in listOf(
            listOf(0.36f * w, 10f, PadMapping.LEFT_X.toFloat(), PadMapping.LEFT_Y.toFloat()),
            listOf(0.64f * w, 11f, PadMapping.RIGHT_X.toFloat(), PadMapping.RIGHT_Y.toFloat()),
        )) {
            val pushed = target == pushX.toInt() || target == pushY.toInt()
            drawCircle(if (target == press.toInt()) lit else part, stick, Offset(cx, sy))
            if (pushed) {
                drawCircle(lit, stick, Offset(cx, sy), style = Stroke(width = 0.012f * w))
                val offset = if (target == pushX.toInt()) Offset(stick * 0.55f, 0f) else Offset(0f, stick * 0.55f)
                drawCircle(lit, stick * 0.38f, Offset(cx, sy) + offset)
            }
        }
    }
}

/**
 * Asks for the gamepad's controls one by one. The gamepad's own events are taken by the activity
 * while this is open, so the buttons here are for the headset's pointer.
 */
@Composable
fun PadLearnDialog(
    tick: Int, target: Int, step: Int, deviceName: String?, finished: Boolean,
    onSkip: () -> Unit, onReset: () -> Unit, onClose: () -> Unit,
) {
    val names = stringArrayResource(R.array.pad_controls)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.pad_learn_title)) },
        text = {
            Column {
                // [tick] changes with every event, which is what brings this text up to date.
                Text(deviceName ?: stringResource(R.string.pad_learn_waiting), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(bottom = if (tick >= 0) 12.dp else 12.dp))
                if (finished) {
                    Text(stringResource(R.string.pad_learn_done), fontSize = 20.sp)
                } else {
                    PadPicture(target, Modifier.fillMaxWidth().height(190.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.pad_learn_prompt), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(names.getOrElse(target) { "" }, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.pad_learn_step, step + 1, PadMapping.LEARN_ORDER.size), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            if (finished) Button(onClick = onClose) { Text(stringResource(R.string.controller_done)) }
            else Button(onClick = onSkip) { Text(stringResource(R.string.pad_learn_skip)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text(stringResource(R.string.pad_learn_reset)) }
                if (!finished) TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
fun PairDialog(
    console: DiscoveredConsole,
    pair: (pin: Int, onResult: (Boolean) -> Unit) -> Unit,
    onPaired: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(R.string.pair_title, console.name)) },
        text = {
            Column {
                Text(stringResource(R.string.pair_steps))
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { value -> pin = value.filter(Char::isDigit).take(8); failed = false },
                    label = { Text(stringResource(R.string.pair_code_label)) },
                    singleLine = true,
                    enabled = !working,
                    isError = failed,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (failed) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.pair_failed), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = pin.length == 8 && !working,
                onClick = {
                    working = true
                    pair(pin.toInt()) { paired ->
                        working = false
                        if (paired) onPaired() else failed = true
                    }
                },
            ) {
                if (working) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.console_pair))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, enabled = !working) { Text(stringResource(R.string.cancel)) }
        },
    )
}

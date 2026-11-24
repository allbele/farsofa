// SPDX-License-Identifier: AGPL-3.0-only

package app.farsofa.vr.ui

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
        Spacer(Modifier.height(32.dp))
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
    onSignOut: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.consoles_title),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSignOut) { Text(stringResource(R.string.sign_out)) }
        }
        if (notice != null) {
            Spacer(Modifier.height(8.dp))
            Text(notice, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(20.dp))

        if (rows.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.consoles_searching), fontSize = 20.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.consoles_searching_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 520.dp),
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(rows, key = { it.id }) { row ->
                    ConsoleCard(row, waking = wakingId == row.id, busy = wakingId != null, onPlay, onPair, onForget)
                }
            }
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

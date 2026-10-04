package dev.bontaramsonta.poof.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.bontaramsonta.poof.core.Countries
import dev.bontaramsonta.poof.core.FailReason
import dev.bontaramsonta.poof.core.SessionEvent
import dev.bontaramsonta.poof.core.SessionState
import dev.bontaramsonta.poof.session.SessionController
import dev.bontaramsonta.poof.tunnel.TunnelStats
import kotlinx.coroutines.delay

/** What the screens can ask for; the activity adds VPN consent in front of connects. */
class ScreenActions(
    val controller: SessionController,
    val connect: (String) -> Unit,
    val reconnect: () -> Unit,
) {
    fun send(event: SessionEvent) = controller.dispatch(event)
}

@Composable
fun PoofScreens(
    state: SessionState,
    token: String?,
    stats: TunnelStats?,
    actions: ScreenActions,
) {
    val dismissable = state is SessionState.Failed || state is SessionState.Conflict ||
        state == SessionState.Expired || state == SessionState.SelfDestructNotice
    BackHandler(enabled = dismissable) { actions.send(SessionEvent.Dismiss) }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
        when (state) {
            SessionState.Idle -> if (token == null) TokenScreen(actions.controller::saveToken) else CountryList(actions)
            is SessionState.Launching -> Progress(state.country, step = 1)
            is SessionState.Handshaking -> Progress(state.record.country, step = 2, ip = state.record.exitIp)
            is SessionState.Connected -> ConnectedScreen(state, stats, actions)
            is SessionState.Ending, is SessionState.TerminatingExisting -> Busy("Destroying the Exit…")
            is SessionState.Checking -> Busy("Checking your ${Countries.displayName(state.record.country)} Exit…")
            is SessionState.Failed -> FailedScreen(state) { actions.send(SessionEvent.Dismiss) }
            is SessionState.Conflict -> Message(
                title = "An Exit is already running",
                body = "${Countries.displayName(state.existing.country)} · ${state.existing.publicIp}. " +
                    "Only one phone Exit may run at a time.",
                action = "Terminate it" to { actions.send(SessionEvent.TerminateExisting) },
                secondary = "Cancel" to { actions.send(SessionEvent.Dismiss) },
            )
            is SessionState.StillRunning -> Message(
                title = "Your ${Countries.displayName(state.record.country)} Exit is still running",
                body = "It will self-destruct a few minutes after it last heard from this phone.",
                action = "Reconnect" to actions.reconnect,
                secondary = "Destroy" to { actions.send(SessionEvent.Disconnect) },
            )
            SessionState.Expired -> Message(
                title = "Your Exit expired",
                body = "It destroyed itself after losing contact with this phone.",
                action = "OK" to { actions.send(SessionEvent.Dismiss) },
            )
            SessionState.SelfDestructNotice -> Message(
                title = "Couldn't reach the control plane",
                body = "The Exit will self-destruct within ~6 minutes.",
                action = "OK" to { actions.send(SessionEvent.Dismiss) },
            )
        }
    }
}

@Composable
private fun TokenScreen(onSave: (String) -> Unit) {
    var token by remember { mutableStateOf("") }
    Text("poof", style = MaterialTheme.typography.displayMedium)
    Spacer(Modifier.height(8.dp))
    Text("Paste the control plane token printed by token.sh.")
    Spacer(Modifier.height(24.dp))
    OutlinedTextField(
        token, { token = it },
        label = { Text("Token") },
        singleLine = true,
        // Password type: the keyboard neither learns nor suggests the token.
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    Button({ onSave(token) }, enabled = token.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Save") }
}

@Composable
private fun CountryList(actions: ScreenActions) {
    var countries by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        try {
            countries = actions.controller.countries()
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load the Countries"
        }
    }

    Text("Where should your traffic exit?", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(12.dp))
    val list = countries
    when {
        error != null -> {
            Text(error!!)
            TextButton({ attempt++ }) { Text("Retry") }
        }
        list == null -> CircularProgressIndicator()
        else -> LazyColumn {
            items(list) { c ->
                Text(
                    Countries.displayName(c),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth().clickable { actions.connect(c) }.padding(vertical = 16.dp),
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun Progress(country: String, step: Int, ip: String? = null) {
    Text("Connecting to ${Countries.displayName(country)}", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(24.dp))
    Step("Launching an Exit", done = step > 1, active = step == 1)
    Step("Waiting for it to answer" + (ip?.let { " ($it)" } ?: ""), done = false, active = step == 2)
    Spacer(Modifier.height(16.dp))
    Text("This usually takes about a minute.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Step(text: String, done: Boolean, active: Boolean) {
    Row(
        Modifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // One fixed 18 dp column so the markers line up.
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            when {
                done -> Text("✓")
                active -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else -> Text("·")
            }
        }
        Text(text)
    }
}

@Composable
private fun Busy(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(24.dp))
    CircularProgressIndicator()
}

@Composable
private fun ColumnScope.ConnectedScreen(state: SessionState.Connected, stats: TunnelStats?, actions: ScreenActions) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val record = state.record
    Text("Connected", style = MaterialTheme.typography.headlineSmall)
    Text("Your traffic exits from ${Countries.displayName(record.country)}.")
    Spacer(Modifier.height(20.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Stat("Exit IP", record.exitIp)
            Stat("Uptime", duration(now - actions.controller.connectedSince))
            Stat("Down", bytes(stats?.rxBytes))
            Stat("Up", bytes(stats?.txBytes))
            Stat(
                "Last handshake",
                stats?.lastHandshakeMillis?.takeIf { it > 0 }?.let { duration(now - it) + " ago" } ?: "—",
            )
        }
    }
    Spacer(Modifier.weight(1f))
    Button({ actions.send(SessionEvent.Disconnect) }, Modifier.fillMaxWidth()) { Text("Disconnect and destroy") }
}

@Composable
private fun Stat(k: String, v: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(k)
    Text(v, fontFamily = FontFamily.Monospace)
}

@Composable
private fun FailedScreen(state: SessionState.Failed, onBack: () -> Unit) {
    val context = LocalContext.current
    Text("Couldn't connect to ${Countries.displayName(state.country)}", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    Text(
        when (val r = state.reason) {
            is FailReason.Timeout -> if (r.terminated) {
                "The Exit never answered. It has been terminated."
            } else {
                "The Exit never answered. It will self-destruct within ~6 minutes."
            }
            is FailReason.LaunchError -> r.message
        },
    )
    if (state.region != null && state.instanceId != null) {
        val ids = "${state.region} ${state.instanceId}"
        Spacer(Modifier.height(16.dp))
        SelectionContainer {
            Text(ids, fontFamily = FontFamily.Monospace)
        }
        TextButton({ copy(context, ids) }) { Text("Copy region and instance ID") }
        Text(
            "Read its console soon: aws ec2 get-console-output --region ${state.region} --instance-id ${state.instanceId}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Spacer(Modifier.height(24.dp))
    Button(onBack, Modifier.fillMaxWidth()) { Text("Back") }
}

@Composable
private fun Message(
    title: String,
    body: String,
    action: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>? = null,
) {
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    Text(body)
    Spacer(Modifier.height(24.dp))
    Button(action.second, Modifier.fillMaxWidth()) { Text(action.first) }
    secondary?.let { (t, f) -> OutlinedButton(f, Modifier.fillMaxWidth()) { Text(t) } }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("poof Exit", text))
}

internal fun duration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

internal fun bytes(n: Long?): String {
    if (n == null) return "—"
    if (n < 1024) return "$n B"
    var v = n.toDouble()
    var unit = 0
    while (v >= 1024 && unit < 4) {
        v /= 1024
        unit++
    }
    return "%.1f %siB".format(v, "KMGT"[unit - 1])
}

// PROTOTYPE — throwaway. Variant A: a stack of full screens, one per step.
package dev.bontaramsonta.poof.prototype

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun VariantA(s: FakeSession) {
  Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
    when (val p = s.phase) {
      Phase.NeedsToken -> TokenScreenA(s::saveToken)
      Phase.Idle -> CountryListA(s::connect)
      is Phase.Launching -> ProgressA(p.country, step = 1)
      is Phase.Handshaking -> ProgressA(p.country, step = 2, ip = p.ip)
      is Phase.Connected -> ConnectedA(p, s::disconnect)
      Phase.Disconnecting -> ProgressA("", step = 0)
      is Phase.Failed -> MessageA("Couldn't connect to ${p.country.title()}", p.reason, "Back", s::dismiss)
      is Phase.ExistingExit -> MessageA(
        "An Exit is already running",
        "${p.country.title()} · ${p.ip}. Only one phone Exit may run at a time.",
        "Terminate it", s::terminateExisting, secondary = "Cancel" to s::dismiss,
      )
    }
  }
}

@Composable
private fun TokenScreenA(onSave: (String) -> Unit) {
  var token by remember { mutableStateOf("") }
  Text("poof", style = MaterialTheme.typography.displayMedium)
  Spacer(Modifier.height(8.dp))
  Text("Paste the control-plane token printed at deploy time.")
  Spacer(Modifier.height(24.dp))
  OutlinedTextField(token, { token = it }, label = { Text("Token") }, singleLine = true, modifier = Modifier.fillMaxWidth())
  Spacer(Modifier.height(16.dp))
  Button({ onSave(token) }, enabled = token.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Save") }
}

@Composable
private fun CountryListA(onPick: (String) -> Unit) {
  Text("Where should your traffic exit?", style = MaterialTheme.typography.headlineSmall)
  Spacer(Modifier.height(12.dp))
  LazyColumn {
    items(Countries) { c ->
      Text(c.title(), style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 16.dp))
      HorizontalDivider()
    }
  }
}

@Composable
private fun ProgressA(country: String, step: Int, ip: String? = null) {
  if (step == 0) { Text("Destroying the Exit…", style = MaterialTheme.typography.headlineSmall); return }
  Text("Connecting to ${country.title()}", style = MaterialTheme.typography.headlineSmall)
  Spacer(Modifier.height(24.dp))
  StepA("Launching an Exit", done = step > 1, active = step == 1)
  StepA("Waiting for it to answer" + (ip?.let { " ($it)" } ?: ""), done = false, active = step == 2)
  Spacer(Modifier.height(16.dp))
  Text("This usually takes about a minute.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun StepA(text: String, done: Boolean, active: Boolean) {
  Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    when {
      done -> Text("✓")
      active -> CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
      else -> Text("·")
    }
    Text(text)
  }
}

@Composable
private fun ColumnScope.ConnectedA(p: Phase.Connected, onDisconnect: () -> Unit) {
  Text("Connected", style = MaterialTheme.typography.headlineSmall)
  Text("Your traffic exits from ${p.country.title()}.")
  Spacer(Modifier.height(20.dp))
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      StatA("Exit IP", p.ip); StatA("Uptime", uptime(p.sinceMs))
      StatA("Down", bytes(p.rxBytes)); StatA("Up", bytes(p.txBytes)); StatA("Last handshake", "${p.handshakeAgeS}s ago")
    }
  }
  Spacer(Modifier.weight(1f))
  Button(onDisconnect, Modifier.fillMaxWidth().padding(bottom = 72.dp)) { Text("Disconnect and destroy") }
}

@Composable
private fun StatA(k: String, v: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
  Text(k); Text(v, fontFamily = FontFamily.Monospace)
}

@Composable
private fun MessageA(title: String, body: String, action: String, onAction: () -> Unit, secondary: Pair<String, () -> Unit>? = null) {
  Text(title, style = MaterialTheme.typography.headlineSmall)
  Spacer(Modifier.height(8.dp)); Text(body); Spacer(Modifier.height(24.dp))
  Button(onAction, Modifier.fillMaxWidth()) { Text(action) }
  secondary?.let { (t, f) -> OutlinedButton(f, Modifier.fillMaxWidth()) { Text(t) } }
}

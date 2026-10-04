// PROTOTYPE — throwaway. Variant B: one screen, one big round button that carries every state.
package dev.bontaramsonta.poof.prototype

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VariantB(s: FakeSession) {
  var country by rememberSaveable { mutableStateOf("japan") }
  var open by remember { mutableStateOf(false) }
  val p = s.phase
  val busy = p !is Phase.Idle && p !is Phase.NeedsToken && p !is Phase.Failed

  Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    ExposedDropdownMenuBox(open, { if (!busy) open = it }) {
      OutlinedTextField(country.title(), {}, readOnly = true, enabled = !busy, label = { Text("Exit country") },
        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable))
      ExposedDropdownMenu(open, { open = false }) {
        Countries.forEach { c -> DropdownMenuItem({ Text(c.title()) }, { country = c; open = false }) }
      }
    }
    Spacer(Modifier.weight(1f))

    val (label, sub) = when (p) {
      Phase.Idle, Phase.NeedsToken -> "Connect" to "tap to launch an Exit"
      is Phase.Launching -> "Launching" to "creating the Exit"
      is Phase.Handshaking -> "Waiting" to "for ${p.ip} to answer"
      is Phase.Connected -> "Connected" to "tap to disconnect"
      Phase.Disconnecting -> "Destroying" to ""
      is Phase.Failed -> "Retry" to p.reason
      is Phase.ExistingExit -> "Blocked" to "an Exit is already running"
    }
    Box(contentAlignment = Alignment.Center) {
      if (p is Phase.Launching || p is Phase.Handshaking || p is Phase.Disconnecting)
        CircularProgressIndicator(Modifier.size(232.dp), strokeWidth = 4.dp)
      Button(
        onClick = { if (p is Phase.Connected) s.disconnect() else if (!busy) s.connect(country) },
        shape = CircleShape, modifier = Modifier.size(210.dp),
        colors = if (p is Phase.Connected) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary) else ButtonDefaults.buttonColors(),
      ) { Text(label, style = MaterialTheme.typography.headlineMedium) }
    }
    Spacer(Modifier.height(12.dp))
    Text(sub, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.weight(1f))

    if (p is Phase.Connected) {
      Row(Modifier.fillMaxWidth().padding(bottom = 80.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        StatB("↓", bytes(p.rxBytes)); StatB("↑", bytes(p.txBytes)); StatB("up", uptime(p.sinceMs)); StatB("ip", p.ip)
      }
    } else Spacer(Modifier.height(120.dp))
  }

  if (p is Phase.NeedsToken) TokenDialogB(s::saveToken)
  if (p is Phase.ExistingExit) AlertDialog(
    onDismissRequest = s::dismiss,
    title = { Text("An Exit is already running") },
    text = { Text("${p.country.title()} · ${p.ip}. Terminate it to start a new one?") },
    confirmButton = { TextButton(s::terminateExisting) { Text("Terminate") } },
    dismissButton = { TextButton(s::dismiss) { Text("Cancel") } },
  )
}

@Composable
private fun StatB(k: String, v: String) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
  Text(v, fontFamily = FontFamily.Monospace); Text(k, style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun TokenDialogB(onSave: (String) -> Unit) {
  var token by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = {},
    title = { Text("Set up poof") },
    text = { OutlinedTextField(token, { token = it }, label = { Text("Control-plane token") }, singleLine = true) },
    confirmButton = { TextButton({ onSave(token) }, enabled = token.isNotBlank()) { Text("Save") } },
  )
}

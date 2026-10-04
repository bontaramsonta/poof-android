// PROTOTYPE — throwaway. Variant C: a grid of Country tiles; one tap connects; a pinned banner carries the Session.
package dev.bontaramsonta.poof.prototype

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun VariantC(s: FakeSession) {
  val p = s.phase
  Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    if (p is Phase.NeedsToken) { TokenInlineC(s::saveToken); return@Column }
    BannerC(p, s)
    val active = when (p) {
      is Phase.Launching -> p.country; is Phase.Handshaking -> p.country; is Phase.Connected -> p.country
      else -> null
    }
    LazyVerticalGrid(GridCells.Fixed(3), Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      items(Countries) { c ->
        val isActive = c == active
        val locked = active != null && !isActive
        OutlinedCard(
          Modifier.aspectRatio(1f).alpha(if (locked) 0.35f else 1f).clickable(enabled = active == null) { s.connect(c) },
          border = if (isActive) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
          Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(c.take(2).uppercase(), style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
            Text(c.title(), style = MaterialTheme.typography.labelMedium)
          }
        }
      }
    }
  }
}

@Composable
private fun BannerC(p: Phase, s: FakeSession) {
  val shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
  Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer, shape).padding(16.dp)) {
    when (p) {
      Phase.Idle -> Text("Tap a country to exit there.", style = MaterialTheme.typography.titleMedium)
      is Phase.Launching -> { Text("Launching ${p.country.title()}…"); LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp)) }
      is Phase.Handshaking -> { Text("Waiting for ${p.ip}…"); LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp)) }
      is Phase.Connected -> Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text("● ${p.country.title()} · ${p.ip}", style = MaterialTheme.typography.titleMedium)
          Text("${uptime(p.sinceMs)}  ↓${bytes(p.rxBytes)}  ↑${bytes(p.txBytes)}  hs ${p.handshakeAgeS}s",
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(s::disconnect) { Text("Stop") }
      }
      Phase.Disconnecting -> Text("Destroying the Exit…")
      is Phase.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${p.country.title()}: ${p.reason}", Modifier.weight(1f)); TextButton(s::dismiss) { Text("OK") }
      }
      is Phase.ExistingExit -> Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Already running: ${p.country.title()} · ${p.ip}", Modifier.weight(1f))
        TextButton(s::terminateExisting) { Text("Terminate") }; TextButton(s::dismiss) { Text("×") }
      }
      Phase.NeedsToken -> {}
    }
  }
}

@Composable
private fun TokenInlineC(onSave: (String) -> Unit) {
  var token by remember { mutableStateOf("") }
  Column(Modifier.padding(24.dp)) {
    Spacer(Modifier.height(48.dp))
    Text("poof", style = MaterialTheme.typography.displayLarge, fontFamily = FontFamily.Monospace)
    Text("ephemeral exits")
    Spacer(Modifier.height(32.dp))
    OutlinedTextField(token, { token = it }, placeholder = { Text("paste token") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    TextButton({ onSave(token) }, enabled = token.isNotBlank()) { Text("Continue →") }
  }
}

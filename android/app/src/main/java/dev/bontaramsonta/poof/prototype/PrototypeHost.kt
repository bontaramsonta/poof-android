// PROTOTYPE — throwaway. Hosts the variants and the floating switcher; debug builds only.
package dev.bontaramsonta.poof.prototype

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class Variant(val key: String, val name: String, val ui: @Composable (FakeSession) -> Unit)

private val variants = listOf(
  Variant("A", "Stack of screens") { VariantA(it) },
  Variant("B", "One big button") { VariantB(it) },
  Variant("C", "Country tiles") { VariantC(it) },
)

@Composable
fun PrototypeHost() {
  val scope = rememberCoroutineScope()
  val session = remember { FakeSession(scope) }
  var index by rememberSaveable { mutableIntStateOf(0) }
  val v = variants[index]

  Box(Modifier.fillMaxSize()) {
    v.ui(session)
    PrototypeSwitcher(
      label = "${v.key} — ${v.name}",
      state = "${session.phase::class.simpleName} · next: ${session.sim.label}",
      onPrev = { index = (index + variants.size - 1) % variants.size },
      onNext = { index = (index + 1) % variants.size },
      onSim = { session.sim = Sim.entries[(session.sim.ordinal + 1) % Sim.entries.size] },
      onReset = { session.forgetToken() },
      modifier = Modifier.align(Alignment.BottomCenter),
    )
  }
}

@Composable
private fun PrototypeSwitcher(
  label: String, state: String,
  onPrev: () -> Unit, onNext: () -> Unit, onSim: () -> Unit, onReset: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val pill = RoundedCornerShape(20.dp)
  Column(
    modifier.navigationBarsPadding().padding(bottom = 8.dp).shadow(8.dp, pill)
      .background(Color(0xFF111111), pill).padding(horizontal = 12.dp, vertical = 6.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Chip("◀", onPrev); Text(label, color = Color(0xFFFFEB3B), fontSize = 13.sp); Chip("▶", onNext)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(state, color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
      Chip("sim", onSim); Chip("reset", onReset)
    }
  }
}

@Composable
private fun Chip(text: String, onClick: () -> Unit) =
  Text(text, color = Color.White, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onClick).padding(6.dp))

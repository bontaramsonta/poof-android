// PROTOTYPE — throwaway. Answers: "What do the v1 screens look like and how do they flow?"
// (https://github.com/bontaramsonta/poof-android/issues/10). Fake control plane, compressed timings, no persistence.
package dev.bontaramsonta.poof.prototype

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val Countries = listOf(
  "australia", "brazil", "britain", "canada", "france", "germany",
  "india", "ireland", "japan", "korea", "singapore", "usa",
)

/** Which control-plane outcome the next Connect gets. Cycled from the switcher bar. */
enum class Sim(val label: String) { Ok("ok"), HandshakeTimeout("handshake timeout"), ExistingExit("409 existing exit") }

sealed interface Phase {
  data object NeedsToken : Phase
  data object Idle : Phase
  /** POST /exits in flight (real: ~7–8 s). */
  data class Launching(val country: String) : Phase
  /** Exit has a public IP; waiting for the first handshake (real: ~47 s, timeout 3 min). */
  data class Handshaking(val country: String, val ip: String) : Phase
  data class Connected(val country: String, val ip: String, val sinceMs: Long, val rxBytes: Long, val txBytes: Long, val handshakeAgeS: Int) : Phase
  data object Disconnecting : Phase
  data class Failed(val country: String, val reason: String) : Phase
  /** 409: a phone Exit is already running (lost track of it). */
  data class ExistingExit(val country: String, val ip: String) : Phase
}

class FakeSession(private val scope: CoroutineScope) {
  var phase: Phase by mutableStateOf(Phase.NeedsToken)
    private set
  var sim: Sim by mutableStateOf(Sim.Ok)
  private var job: Job? = null

  fun saveToken(token: String) {
    if (token.isNotBlank()) phase = Phase.Idle
  }

  fun forgetToken() {
    job?.cancel(); phase = Phase.NeedsToken
  }

  fun connect(country: String) {
    job?.cancel()
    job = scope.launch {
      phase = Phase.Launching(country)
      delay(2_000)
      if (sim == Sim.ExistingExit) {
        phase = Phase.ExistingExit("japan", "43.207.74.158"); return@launch
      }
      val ip = "13.115.${(10..250).random()}.${(10..250).random()}"
      phase = Phase.Handshaking(country, ip)
      delay(4_000)
      if (sim == Sim.HandshakeTimeout) {
        phase = Phase.Failed(country, "The Exit never answered. It has been terminated."); return@launch
      }
      val since = System.currentTimeMillis()
      var rx = 0L; var tx = 0L; var age = 0
      while (true) {
        phase = Phase.Connected(country, ip, since, rx, tx, age)
        delay(1_000)
        rx += (20_000..400_000).random(); tx += (2_000..60_000).random()
        age = if (age >= 120) 0 else age + 1
      }
    }
  }

  fun disconnect() {
    job?.cancel()
    job = scope.launch { phase = Phase.Disconnecting; delay(800); phase = Phase.Idle }
  }

  /** From the 409 state: DELETE the stray Exit. */
  fun terminateExisting() = disconnect()

  fun dismiss() {
    job?.cancel(); phase = Phase.Idle
  }
}

fun bytes(n: Long): String = when {
  n >= 1_000_000_000 -> "%.1f GB".format(n / 1e9)
  n >= 1_000_000 -> "%.1f MB".format(n / 1e6)
  n >= 1_000 -> "%.0f kB".format(n / 1e3)
  else -> "$n B"
}

fun uptime(sinceMs: Long): String {
  val s = ((System.currentTimeMillis() - sinceMs) / 1000).toInt()
  return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
}

fun String.title() = replaceFirstChar { it.uppercase() }

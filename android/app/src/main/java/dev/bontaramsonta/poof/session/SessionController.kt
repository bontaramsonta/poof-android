package dev.bontaramsonta.poof.session

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.bontaramsonta.poof.core.ExitLiveness
import dev.bontaramsonta.poof.core.HANDSHAKE_TIMEOUT_MS
import dev.bontaramsonta.poof.core.STALE_HANDSHAKE_MS
import dev.bontaramsonta.poof.core.SessionEffect
import dev.bontaramsonta.poof.core.SessionEvent
import dev.bontaramsonta.poof.core.SessionRecord
import dev.bontaramsonta.poof.core.SessionState
import dev.bontaramsonta.poof.core.TunnelConfig
import dev.bontaramsonta.poof.core.reduce
import dev.bontaramsonta.poof.data.ControlPlaneClient
import dev.bontaramsonta.poof.data.ControlPlaneException
import dev.bontaramsonta.poof.data.CreateResult
import dev.bontaramsonta.poof.data.SecretStore
import dev.bontaramsonta.poof.data.UnauthorizedException
import dev.bontaramsonta.poof.service.Notifier
import dev.bontaramsonta.poof.service.PoofVpnService
import dev.bontaramsonta.poof.tunnel.TunnelManager
import dev.bontaramsonta.poof.tunnel.TunnelStats
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs the Session: feeds events through [reduce] on the main thread and
 * executes each transition's effects in order. Lives for the whole process,
 * so the Session survives the UI.
 */
class SessionController(
    private val context: Context,
    private val store: SecretStore,
    private val clientFor: (token: String) -> ControlPlaneClient,
    private val notifier: Notifier,
    private val scope: CoroutineScope,
) {
    private val tunnel = TunnelManager(context) { dispatch(SessionEvent.Revoked) }

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _stats = MutableStateFlow<TunnelStats?>(null)
    val stats: StateFlow<TunnelStats?> = _stats.asStateFlow()

    private val _token = MutableStateFlow(store.token)
    val token: StateFlow<String?> = _token.asStateFlow()

    /** When the current tunnel first handshook; drives uptime. */
    var connectedSince: Long = 0L
        private set

    /** Polling runs fast only while the UI is visible (spec §6.3). */
    val uiVisible = MutableStateFlow(false)

    private var timeoutJob: Job? = null
    private var pollJob: Job? = null

    init {
        dispatch(SessionEvent.Opened(store.session))
    }

    fun saveToken(token: String) {
        store.token = token.trim()
        _token.value = store.token
    }

    private fun clearToken() {
        store.token = null
        _token.value = null
    }

    /** The Country list from `GET /countries`. A rejected token sends the owner back to setup. */
    suspend fun countries(): List<String> = try {
        io { client().countries() }
    } catch (e: UnauthorizedException) {
        clearToken()
        throw e
    }

    /** The Country tap. The service starts first so the connect survives leaving the app. */
    fun connect(country: String) {
        startService()
        dispatch(SessionEvent.Connect(country, TunnelConfig.newClientKeys()))
    }

    fun reconnect() {
        startService()
        dispatch(SessionEvent.Reconnect)
    }

    fun dispatch(event: SessionEvent) {
        scope.launch(Dispatchers.Main.immediate) { apply(event) }
    }

    private fun apply(event: SessionEvent) {
        val before = _state.value
        val t = reduce(before, event)
        if (t.state == before && t.effects.isEmpty()) return
        _state.value = t.state
        if (t.state is SessionState.Connected && before !is SessionState.Connected) {
            connectedSince = System.currentTimeMillis()
        }
        if (t.state !is SessionState.Handshaking) timeoutJob?.cancel()
        updatePolling(t.state)
        if (t.effects.isNotEmpty()) {
            scope.launch { t.effects.forEach { run(it) } }
        }
    }

    private suspend fun run(effect: SessionEffect) {
        when (effect) {
            is SessionEffect.PostExit -> postExit(effect)
            is SessionEffect.WriteRecord -> io { store.session = effect.record }
            is SessionEffect.StartTunnel -> startTunnel(effect.record)
            SessionEffect.ArmHandshakeTimeout -> {
                timeoutJob?.cancel()
                timeoutJob = scope.launch {
                    delay(HANDSHAKE_TIMEOUT_MS)
                    dispatch(SessionEvent.HandshakeTimeout)
                }
            }
            SessionEffect.StopTunnel -> io { runCatching { tunnel.down() } }
            is SessionEffect.DeleteExit -> {
                val ok = io { call { deleteExit(effect.region, effect.instanceId) } } != null
                dispatch(
                    if (_state.value is SessionState.TerminatingExisting) SessionEvent.ExistingTerminated(ok)
                    else SessionEvent.DeleteDone(ok),
                )
            }
            SessionEffect.DeleteRecord -> io { store.session = null }
            is SessionEffect.GetExit -> {
                val liveness = io { call { exitLiveness(effect.region, effect.instanceId) } } ?: ExitLiveness.Unknown
                dispatch(SessionEvent.ExitState(liveness))
            }
            is SessionEffect.NotifyConnected -> notifier.postConnected(effect.record, connectedSince)
            is SessionEffect.NotifyRevoked -> notifier.postRevoked(effect.destroyed)
        }
    }

    private suspend fun postExit(effect: SessionEffect.PostExit) {
        val launching = _state.value as? SessionState.Launching ?: return
        val event = try {
            when (val r = io { client().createExit(effect.country, effect.clientPublicKey) }) {
                is CreateResult.Created -> SessionEvent.Launched(
                    SessionRecord(
                        country = effect.country,
                        region = r.region,
                        instanceId = r.instanceId,
                        exitIp = r.publicIp,
                        serverPublicKey = r.serverPublicKey,
                        clientPrivateKey = launching.keys.privateKey,
                    ),
                )
                is CreateResult.Conflict -> SessionEvent.LaunchConflict(r.existing)
            }
        } catch (e: UnauthorizedException) {
            clearToken()
            SessionEvent.LaunchFailed("The control plane rejected the token. Paste it again.")
        } catch (e: ControlPlaneException) {
            SessionEvent.LaunchFailed(e.message ?: "Launch failed", e.region, e.instanceId)
        } catch (e: IOException) {
            SessionEvent.LaunchFailed("Couldn't reach the control plane (${e.message}).")
        }
        dispatch(event)
    }

    private suspend fun startTunnel(record: SessionRecord) {
        try {
            io { tunnel.up(record) }
        } catch (e: Exception) {
            // The 3 min timeout then ends the Session and terminates the Exit.
        }
    }

    private fun updatePolling(state: SessionState) {
        val tunnelUp = state is SessionState.Handshaking || state is SessionState.Connected
        if (!tunnelUp) {
            pollJob?.cancel()
            pollJob = null
            _stats.value = null
            return
        }
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            var lastStaleCheck = 0L
            while (isActive) {
                val s = io { runCatching { tunnel.stats() }.getOrNull() }
                _stats.value = s
                val current = _state.value
                val handshook = s != null && s.lastHandshakeMillis > 0
                val now = System.currentTimeMillis()
                if (current is SessionState.Handshaking && handshook) {
                    dispatch(SessionEvent.Handshake)
                } else if (current is SessionState.Connected && handshook &&
                    now - s!!.lastHandshakeMillis > STALE_HANDSHAKE_MS && now - lastStaleCheck > STALE_RECHECK_MS
                ) {
                    lastStaleCheck = now
                    dispatch(SessionEvent.HandshakeStale)
                }
                val fast = current is SessionState.Handshaking || uiVisible.value
                delay(if (fast) FAST_POLL_MS else SLOW_POLL_MS)
            }
        }
    }

    private fun client(): ControlPlaneClient =
        clientFor(_token.value ?: throw UnauthorizedException())

    private suspend fun <T> call(block: suspend ControlPlaneClient.() -> T): T? =
        try {
            client().block()
        } catch (e: IOException) {
            null
        }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun startService() {
        ContextCompat.startForegroundService(context, Intent(context, PoofVpnService::class.java))
    }

    private companion object {
        const val FAST_POLL_MS = 1_000L
        const val SLOW_POLL_MS = 30_000L
        const val STALE_RECHECK_MS = 2 * 60_000L
    }
}

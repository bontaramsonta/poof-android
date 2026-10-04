package dev.bontaramsonta.poof.core

/**
 * The phone Session as a pure state machine (spec §6.4). [reduce] maps a
 * state and an event to the next state plus the effects the caller must run,
 * in order. Events that make no sense in a state are ignored, so late timers
 * and duplicate callbacks are harmless.
 */
sealed interface SessionState {
    /** No Session. The Country picker. */
    data object Idle : SessionState

    /** `POST /exits` in flight. Step one of the connecting screen. */
    data class Launching(val country: String, val keys: ClientKeys) : SessionState

    /** Record written, tunnel up, waiting for the first handshake. */
    data class Handshaking(val record: SessionRecord) : SessionState

    data class Connected(val record: SessionRecord) : SessionState

    /** Tunnel stopped, `DELETE` in flight. */
    data class Ending(val record: SessionRecord, val reason: EndReason) : SessionState

    data class Failed(
        val country: String,
        val reason: FailReason,
        val region: String? = null,
        val instanceId: String? = null,
    ) : SessionState

    /** `409`: a phone Exit is already running. */
    data class Conflict(val existing: ExistingExit) : SessionState

    data class TerminatingExisting(val existing: ExistingExit) : SessionState

    /** App opened with a stored record; `GET` in flight. */
    data class Checking(val record: SessionRecord) : SessionState

    /** Ending 4: the Exit outlived the app. Reconnect or Destroy. */
    data class StillRunning(val record: SessionRecord) : SessionState

    data object Expired : SessionState

    /** `DELETE` failed; the Dead-man's switch will reap the Exit. */
    data object SelfDestructNotice : SessionState
}

enum class EndReason { Disconnect, Revoke, Timeout }

sealed interface FailReason {
    /** The Exit never answered within [HANDSHAKE_TIMEOUT_MS]. */
    data class Timeout(val terminated: Boolean) : FailReason

    data class LaunchError(val message: String) : FailReason
}

/** What the control plane says about a stored Session's Exit. */
enum class ExitLiveness { Alive, Gone, Unknown }

sealed interface SessionEvent {
    data class Connect(val country: String, val keys: ClientKeys) : SessionEvent
    data class Launched(val record: SessionRecord) : SessionEvent
    data class LaunchConflict(val existing: ExistingExit) : SessionEvent
    data class LaunchFailed(
        val message: String,
        val region: String? = null,
        val instanceId: String? = null,
    ) : SessionEvent
    data object Handshake : SessionEvent
    data object HandshakeTimeout : SessionEvent
    data object Disconnect : SessionEvent
    data object Revoked : SessionEvent
    data class DeleteDone(val ok: Boolean) : SessionEvent
    data class Opened(val record: SessionRecord?) : SessionEvent
    data class ExitState(val liveness: ExitLiveness) : SessionEvent
    data object Reconnect : SessionEvent
    /** Handshake age passed [STALE_HANDSHAKE_MS] with the tunnel up. */
    data object HandshakeStale : SessionEvent
    data object TerminateExisting : SessionEvent
    data class ExistingTerminated(val ok: Boolean) : SessionEvent
    data object Dismiss : SessionEvent
}

sealed interface SessionEffect {
    data class PostExit(val country: String, val clientPublicKey: String) : SessionEffect
    data class WriteRecord(val record: SessionRecord) : SessionEffect
    data class StartTunnel(val record: SessionRecord) : SessionEffect
    /** Fire [SessionEvent.HandshakeTimeout] after [HANDSHAKE_TIMEOUT_MS]. */
    data object ArmHandshakeTimeout : SessionEffect
    /** Bring the tunnel down. On revoke it MUST run before anything else. */
    data object StopTunnel : SessionEffect
    data class DeleteExit(val region: String, val instanceId: String) : SessionEffect
    data object DeleteRecord : SessionEffect
    data class GetExit(val region: String, val instanceId: String) : SessionEffect
    data class NotifyConnected(val record: SessionRecord) : SessionEffect
    data class NotifyRevoked(val destroyed: Boolean) : SessionEffect
}

data class Transition(val state: SessionState, val effects: List<SessionEffect> = emptyList())

const val HANDSHAKE_TIMEOUT_MS = 3 * 60_000L
const val STALE_HANDSHAKE_MS = 7 * 60_000L

fun reduce(state: SessionState, event: SessionEvent): Transition {
    val same = Transition(state)
    return when (event) {
        is SessionEvent.Connect -> if (state is SessionState.Idle) {
            Transition(
                SessionState.Launching(event.country, event.keys),
                listOf(SessionEffect.PostExit(event.country, event.keys.publicKey)),
            )
        } else same

        is SessionEvent.Launched -> if (state is SessionState.Launching) {
            Transition(
                SessionState.Handshaking(event.record),
                listOf(
                    SessionEffect.WriteRecord(event.record),
                    SessionEffect.StartTunnel(event.record),
                    SessionEffect.ArmHandshakeTimeout,
                ),
            )
        } else same

        is SessionEvent.LaunchConflict ->
            if (state is SessionState.Launching) Transition(SessionState.Conflict(event.existing)) else same

        is SessionEvent.LaunchFailed -> if (state is SessionState.Launching) {
            Transition(
                SessionState.Failed(
                    state.country, FailReason.LaunchError(event.message), event.region, event.instanceId,
                ),
            )
        } else same

        SessionEvent.Handshake -> if (state is SessionState.Handshaking) {
            Transition(
                SessionState.Connected(state.record),
                listOf(SessionEffect.NotifyConnected(state.record)),
            )
        } else same

        SessionEvent.HandshakeTimeout ->
            if (state is SessionState.Handshaking) end(state.record, EndReason.Timeout) else same

        SessionEvent.Disconnect -> when (state) {
            is SessionState.Connected -> end(state.record, EndReason.Disconnect)
            is SessionState.StillRunning -> end(state.record, EndReason.Disconnect)
            else -> same
        }

        SessionEvent.Revoked -> when (state) {
            is SessionState.Handshaking -> end(state.record, EndReason.Revoke)
            is SessionState.Connected -> end(state.record, EndReason.Revoke)
            else -> same
        }

        is SessionEvent.DeleteDone -> if (state is SessionState.Ending) {
            val next = when (state.reason) {
                EndReason.Timeout -> SessionState.Failed(
                    state.record.country,
                    FailReason.Timeout(terminated = event.ok),
                    state.record.region,
                    state.record.instanceId,
                )
                EndReason.Disconnect, EndReason.Revoke ->
                    if (event.ok) SessionState.Idle else SessionState.SelfDestructNotice
            }
            val effects = buildList {
                add(SessionEffect.DeleteRecord)
                if (state.reason == EndReason.Revoke) add(SessionEffect.NotifyRevoked(event.ok))
            }
            Transition(next, effects)
        } else same

        is SessionEvent.Opened -> if (state is SessionState.Idle && event.record != null) {
            Transition(
                SessionState.Checking(event.record),
                listOf(SessionEffect.GetExit(event.record.region, event.record.instanceId)),
            )
        } else same

        is SessionEvent.ExitState -> when (state) {
            is SessionState.Checking -> when (event.liveness) {
                ExitLiveness.Gone -> Transition(SessionState.Expired, listOf(SessionEffect.DeleteRecord))
                // Offline: still offer the choice; Destroy then fails into the notice.
                ExitLiveness.Alive, ExitLiveness.Unknown -> Transition(SessionState.StillRunning(state.record))
            }
            is SessionState.Connected -> when (event.liveness) {
                ExitLiveness.Gone -> Transition(
                    SessionState.Expired,
                    listOf(SessionEffect.StopTunnel, SessionEffect.DeleteRecord),
                )
                // Control plane unreachable or Exit alive: keep waiting.
                ExitLiveness.Alive, ExitLiveness.Unknown -> same
            }
            else -> same
        }

        SessionEvent.Reconnect -> if (state is SessionState.StillRunning) {
            Transition(
                SessionState.Handshaking(state.record),
                listOf(SessionEffect.StartTunnel(state.record), SessionEffect.ArmHandshakeTimeout),
            )
        } else same

        SessionEvent.HandshakeStale -> if (state is SessionState.Connected) {
            Transition(state, listOf(SessionEffect.GetExit(state.record.region, state.record.instanceId)))
        } else same

        SessionEvent.TerminateExisting -> if (state is SessionState.Conflict) {
            Transition(
                SessionState.TerminatingExisting(state.existing),
                listOf(SessionEffect.DeleteExit(state.existing.region, state.existing.instanceId)),
            )
        } else same

        is SessionEvent.ExistingTerminated -> if (state is SessionState.TerminatingExisting) {
            Transition(if (event.ok) SessionState.Idle else SessionState.SelfDestructNotice)
        } else same

        SessionEvent.Dismiss -> when (state) {
            is SessionState.Failed, is SessionState.Conflict,
            SessionState.Expired, SessionState.SelfDestructNotice -> Transition(SessionState.Idle)
            else -> same
        }
    }
}

private fun end(record: SessionRecord, reason: EndReason) = Transition(
    SessionState.Ending(record, reason),
    listOf(SessionEffect.StopTunnel, SessionEffect.DeleteExit(record.region, record.instanceId)),
)

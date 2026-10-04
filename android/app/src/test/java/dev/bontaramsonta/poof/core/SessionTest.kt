package dev.bontaramsonta.poof.core

import dev.bontaramsonta.poof.core.SessionEffect.ArmHandshakeTimeout
import dev.bontaramsonta.poof.core.SessionEffect.DeleteExit
import dev.bontaramsonta.poof.core.SessionEffect.DeleteRecord
import dev.bontaramsonta.poof.core.SessionEffect.GetExit
import dev.bontaramsonta.poof.core.SessionEffect.NotifyConnected
import dev.bontaramsonta.poof.core.SessionEffect.NotifyRevoked
import dev.bontaramsonta.poof.core.SessionEffect.PostExit
import dev.bontaramsonta.poof.core.SessionEffect.StartTunnel
import dev.bontaramsonta.poof.core.SessionEffect.StopTunnel
import dev.bontaramsonta.poof.core.SessionEffect.WriteRecord
import dev.bontaramsonta.poof.core.SessionState.Connected
import dev.bontaramsonta.poof.core.SessionState.Ending
import dev.bontaramsonta.poof.core.SessionState.Handshaking
import dev.bontaramsonta.poof.core.SessionState.Idle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SessionTest {
    private val keys = ClientKeys("cHJpdmF0ZQ==", "cHVibGlj")
    private val record = SessionRecord("japan", "ap-northeast-1", "i-0abc", "198.51.100.7", "c2VydmVy", keys.privateKey)
    private val existing = ExistingExit("britain", "eu-west-2", "i-0def", "203.0.113.9")
    private val delete = DeleteExit(record.region, record.instanceId)

    private fun run(start: SessionState, vararg events: SessionEvent): Pair<SessionState, List<SessionEffect>> {
        var state = start
        val effects = mutableListOf<SessionEffect>()
        for (e in events) {
            val t = reduce(state, e)
            state = t.state
            effects += t.effects
        }
        return state to effects
    }

    @Test fun connectHappyPath() {
        val (state, effects) = run(
            Idle,
            SessionEvent.Connect("japan", keys),
            SessionEvent.Launched(record),
            SessionEvent.Handshake,
        )
        assertEquals(Connected(record), state)
        assertEquals(
            listOf(PostExit("japan", keys.publicKey), WriteRecord(record), StartTunnel(record), ArmHandshakeTimeout, NotifyConnected(record)),
            effects,
        )
    }

    @Test fun recordIsWrittenBeforeTheTunnelStarts() {
        val effects = reduce(SessionState.Launching("japan", keys), SessionEvent.Launched(record)).effects
        assertEquals(WriteRecord(record), effects.first())
    }

    @Test fun handshakeTimeoutDeletesThenFails() {
        val (state, effects) = run(Handshaking(record), SessionEvent.HandshakeTimeout, SessionEvent.DeleteDone(ok = true))
        assertEquals(
            SessionState.Failed("japan", FailReason.Timeout(terminated = true), "ap-northeast-1", "i-0abc"),
            state,
        )
        assertEquals(listOf(StopTunnel, delete, DeleteRecord), effects)
    }

    @Test fun lateTimeoutAfterHandshakeIsIgnored() {
        val (state, effects) = run(Handshaking(record), SessionEvent.Handshake, SessionEvent.HandshakeTimeout)
        assertEquals(Connected(record), state)
        assertEquals(listOf(NotifyConnected(record)), effects)
    }

    @Test fun disconnectStopsTunnelDeletesExitThenRecord() {
        val (state, effects) = run(Connected(record), SessionEvent.Disconnect, SessionEvent.DeleteDone(ok = true))
        assertEquals(Idle, state)
        assertEquals(listOf(StopTunnel, delete, DeleteRecord), effects)
    }

    @Test fun failedDeleteStillDropsRecordAndWarns() {
        val (state, effects) = run(Connected(record), SessionEvent.Disconnect, SessionEvent.DeleteDone(ok = false))
        assertEquals(SessionState.SelfDestructNotice, state)
        assertEquals(DeleteRecord, effects.last())
    }

    @Test fun revokeStopsEngineFirst() {
        for (start in listOf(Handshaking(record), Connected(record))) {
            val t = reduce(start, SessionEvent.Revoked)
            assertEquals(StopTunnel, t.effects.first())
            assertEquals(Ending(record, EndReason.Revoke), t.state)
        }
        val (_, effects) = run(Connected(record), SessionEvent.Revoked, SessionEvent.DeleteDone(ok = true))
        assertEquals(listOf(StopTunnel, delete, DeleteRecord, NotifyRevoked(destroyed = true)), effects)
    }

    @Test fun launchConflictAndTerminate() {
        val (state, effects) = run(
            Idle,
            SessionEvent.Connect("japan", keys),
            SessionEvent.LaunchConflict(existing),
            SessionEvent.TerminateExisting,
            SessionEvent.ExistingTerminated(ok = true),
        )
        assertEquals(Idle, state)
        assertEquals(listOf(PostExit("japan", keys.publicKey), DeleteExit("eu-west-2", "i-0def")), effects)
    }

    @Test fun launchErrorKeepsInstanceForTheFailureScreen() {
        val (state, effects) = run(
            SessionState.Launching("india", keys),
            SessionEvent.LaunchFailed("the Exit got no public IP", "ap-south-1", "i-0bad"),
        )
        assertEquals(
            SessionState.Failed("india", FailReason.LaunchError("the Exit got no public IP"), "ap-south-1", "i-0bad"),
            state,
        )
        assertEquals(emptyList<SessionEffect>(), effects)
    }

    @Test fun openWithLiveExitOffersReconnect() {
        val (state, effects) = run(
            Idle,
            SessionEvent.Opened(record),
            SessionEvent.ExitState(ExitLiveness.Alive),
            SessionEvent.Reconnect,
        )
        assertEquals(Handshaking(record), state)
        assertEquals(listOf(GetExit(record.region, record.instanceId), StartTunnel(record), ArmHandshakeTimeout), effects)
    }

    @Test fun openWithLiveExitCanDestroy() {
        val (state, effects) = run(
            SessionState.StillRunning(record),
            SessionEvent.Disconnect,
            SessionEvent.DeleteDone(ok = true),
        )
        assertEquals(Idle, state)
        assertEquals(listOf(StopTunnel, delete, DeleteRecord), effects)
    }

    @Test fun openWithGoneExitExpires() {
        val (state, effects) = run(Idle, SessionEvent.Opened(record), SessionEvent.ExitState(ExitLiveness.Gone))
        assertEquals(SessionState.Expired, state)
        assertEquals(listOf(GetExit(record.region, record.instanceId), DeleteRecord), effects)
    }

    @Test fun openWithoutRecordStaysIdle() {
        assertEquals(Transition(Idle), reduce(Idle, SessionEvent.Opened(null)))
    }

    @Test fun staleHandshakeChecksAndExpiresOnlyWhenGone() {
        val connected = Connected(record)
        assertEquals(listOf(GetExit(record.region, record.instanceId)), reduce(connected, SessionEvent.HandshakeStale).effects)
        assertSame(connected, reduce(connected, SessionEvent.ExitState(ExitLiveness.Unknown)).state)
        assertSame(connected, reduce(connected, SessionEvent.ExitState(ExitLiveness.Alive)).state)
        val gone = reduce(connected, SessionEvent.ExitState(ExitLiveness.Gone))
        assertEquals(SessionState.Expired, gone.state)
        assertEquals(listOf(StopTunnel, DeleteRecord), gone.effects)
    }

    @Test fun connectIgnoredWhileASessionExists() {
        for (s in listOf(Handshaking(record), Connected(record), SessionState.StillRunning(record))) {
            assertEquals(Transition(s), reduce(s, SessionEvent.Connect("usa", keys)))
        }
    }

    @Test fun dismissReturnsToPicker() {
        for (s in listOf(
            SessionState.Failed("japan", FailReason.Timeout(true)),
            SessionState.Conflict(existing),
            SessionState.Expired,
            SessionState.SelfDestructNotice,
        )) {
            assertEquals(Idle, reduce(s, SessionEvent.Dismiss).state)
        }
        assertEquals(Connected(record), reduce(Connected(record), SessionEvent.Dismiss).state)
    }
}

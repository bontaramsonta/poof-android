package dev.bontaramsonta.poof.tunnel

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.crypto.Key
import dev.bontaramsonta.poof.core.SessionRecord
import dev.bontaramsonta.poof.core.TunnelConfig

/** One poll of the tunnel's peer. */
data class TunnelStats(val rxBytes: Long, val txBytes: Long, val lastHandshakeMillis: Long)

/**
 * Holds the GoBackend and the one tunnel. GoBackend's own VpnService stops
 * the engine itself when the VPN is revoked, then reports DOWN; a DOWN we did
 * not ask for is therefore a revoke, and [onRevoked] fires.
 */
class TunnelManager(context: Context, private val onRevoked: () -> Unit) {
    private val backend = GoBackend(context.applicationContext)
    @Volatile private var record: SessionRecord? = null
    @Volatile private var stopping = false

    private val tunnel = object : Tunnel {
        override fun getName() = "poof"

        override fun onStateChange(newState: Tunnel.State) {
            if (newState == Tunnel.State.DOWN && !stopping && record != null) {
                record = null
                onRevoked()
            }
        }
    }

    /** Blocking; call off the main thread. */
    fun up(record: SessionRecord) {
        stopping = false
        this.record = record
        backend.setState(tunnel, Tunnel.State.UP, TunnelConfig.build(record))
    }

    /** Blocking and idempotent; call off the main thread. */
    fun down() {
        stopping = true
        record = null
        if (backend.getState(tunnel) != Tunnel.State.DOWN) {
            backend.setState(tunnel, Tunnel.State.DOWN, null)
        }
    }

    fun stats(): TunnelStats? {
        val r = record ?: return null
        if (backend.getState(tunnel) != Tunnel.State.UP) return null
        val peer = backend.getStatistics(tunnel).peer(Key.fromBase64(r.serverPublicKey)) ?: return null
        return TunnelStats(peer.rxBytes(), peer.txBytes(), peer.latestHandshakeEpochMillis())
    }
}

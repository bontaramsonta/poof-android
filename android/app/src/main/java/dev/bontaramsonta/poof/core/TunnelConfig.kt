package dev.bontaramsonta.poof.core

import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import java.net.InetAddress

/**
 * Builds the tunnel config for a Session (spec §6.2).
 *
 * Exactly one peer with a v4 `/0` route and no v6 anything: GoBackend then
 * leaves the v6 family disallowed and Android blackholes IPv6 instead of
 * leaking it. Adding a v6 address, route or DNS server, a second peer or a
 * split route breaks that.
 */
object TunnelConfig {
    const val CLIENT_ADDRESS = "10.66.0.2/32"
    val DNS_SERVERS = listOf("1.1.1.1", "1.0.0.1")
    const val ALLOWED_IPS = "0.0.0.0/0"
    const val EXIT_PORT = 51820
    const val KEEPALIVE_SECONDS = 25

    fun build(record: SessionRecord): Config {
        val iface = Interface.Builder()
            .addAddress(InetNetwork.parse(CLIENT_ADDRESS))
            .addDnsServers(DNS_SERVERS.map { InetAddress.getByName(it) })
            .setKeyPair(KeyPair(Key.fromBase64(record.clientPrivateKey)))
            .build()
        val peer = Peer.Builder()
            .setPublicKey(Key.fromBase64(record.serverPublicKey))
            .setEndpoint(InetEndpoint.parse("${record.exitIp}:$EXIT_PORT"))
            .addAllowedIp(InetNetwork.parse(ALLOWED_IPS))
            .setPersistentKeepalive(KEEPALIVE_SECONDS)
            .build()
        return Config.Builder().setInterface(iface).addPeer(peer).build()
    }

    /** A fresh client keypair for one Session. */
    fun newClientKeys(): ClientKeys {
        val pair = KeyPair()
        return ClientKeys(pair.privateKey.toBase64(), pair.publicKey.toBase64())
    }
}

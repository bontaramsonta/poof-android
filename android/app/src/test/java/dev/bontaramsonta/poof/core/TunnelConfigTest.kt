package dev.bontaramsonta.poof.core

import java.net.Inet4Address
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelConfigTest {
    private val client = TunnelConfig.newClientKeys()
    private val server = TunnelConfig.newClientKeys()
    private val record = SessionRecord("japan", "ap-northeast-1", "i-0abc", "198.51.100.7", server.publicKey, client.privateKey)
    private val config = TunnelConfig.build(record)

    @Test fun exactlyOnePeerWithOnlyTheV4DefaultRoute() {
        assertEquals(1, config.peers.size)
        val allowed = config.peers.single().allowedIps.map { it.toString() }
        assertEquals(listOf("0.0.0.0/0"), allowed)
    }

    @Test fun noV6AddressRouteOrDns() {
        assertTrue(config.`interface`.addresses.all { it.address is Inet4Address })
        assertTrue(config.`interface`.dnsServers.all { it is Inet4Address })
        assertTrue(config.peers.single().allowedIps.all { it.address is Inet4Address })
        assertFalse(config.toWgQuickString().contains("::"))
    }

    @Test fun pushesCloudflareDns() {
        assertEquals(listOf("1.1.1.1", "1.0.0.1"), config.`interface`.dnsServers.map { it.hostAddress })
    }

    @Test fun interfaceAndPeerFields() {
        val iface = config.`interface`
        assertEquals(listOf("10.66.0.2/32"), iface.addresses.map { it.toString() })
        assertEquals(client.publicKey, iface.keyPair.publicKey.toBase64())
        val peer = config.peers.single()
        assertEquals(server.publicKey, peer.publicKey.toBase64())
        assertEquals("198.51.100.7:51820", peer.endpoint.get().toString())
        assertEquals(25, peer.persistentKeepalive.get())
    }

    @Test fun freshKeysEachSession() {
        assertFalse(TunnelConfig.newClientKeys() == TunnelConfig.newClientKeys())
    }
}

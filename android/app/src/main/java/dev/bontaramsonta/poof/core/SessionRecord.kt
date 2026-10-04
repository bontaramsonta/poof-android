package dev.bontaramsonta.poof.core

import kotlinx.serialization.Serializable

/**
 * The stored Session record. A phone Session lives exactly as long as this
 * record: written at `201`, deleted at any ending. Stored encrypted.
 */
@Serializable
data class SessionRecord(
    val country: String,
    val region: String,
    val instanceId: String,
    val exitIp: String,
    val serverPublicKey: String,
    val clientPrivateKey: String,
) {
    // Keeps the private key out of logs and crash reports.
    override fun toString() = "SessionRecord($country, $region, $instanceId, $exitIp)"
}

/** The phone Exit a `409` reports as already running. */
data class ExistingExit(
    val country: String,
    val region: String,
    val instanceId: String,
    val publicIp: String,
)

/** A WireGuard keypair, base64. */
data class ClientKeys(val privateKey: String, val publicKey: String) {
    override fun toString() = "ClientKeys($publicKey)"
}

package dev.bontaramsonta.poof.data

import dev.bontaramsonta.poof.core.ExistingExit
import dev.bontaramsonta.poof.core.ExitLiveness
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** The control plane rejected the bearer token. */
class UnauthorizedException : IOException("the control plane rejected the token")

/**
 * Any other non-success answer. [message] is the control plane's error text;
 * [region] and [instanceId] are set when a launched instance was terminated.
 */
class ControlPlaneException(
    val status: Int,
    message: String,
    val region: String? = null,
    val instanceId: String? = null,
) : IOException(message)

sealed interface CreateResult {
    data class Created(
        val instanceId: String,
        val region: String,
        val publicIp: String,
        val serverPublicKey: String,
    ) : CreateResult

    data class Conflict(val existing: ExistingExit) : CreateResult
}

/**
 * The phone's client for the control plane API (spec §4.3). Every call
 * retries a `429` (reserved concurrency 1) with a short backoff.
 */
class ControlPlaneClient(
    baseUrl: String,
    private val token: () -> String,
    private val http: OkHttpClient = defaultHttpClient(),
    private val backoffMs: List<Long> = listOf(500, 1_000, 2_000, 4_000),
) {
    private val base: HttpUrl = baseUrl.toHttpUrl()

    suspend fun countries(): List<String> =
        decode<CountriesBody>(call(get("countries"))).countries

    suspend fun createExit(country: String, clientPublicKey: String): CreateResult {
        val body = json.encodeToString(CreateBody(country, clientPublicKey))
        val request = Request.Builder()
            .url(base.newBuilder().addPathSegment("exits").build())
            .post(body.toRequestBody(JSON))
            .build()
        return call(request, accept = setOf(201, 409)) { status, text ->
            if (status == 201) {
                val c = json.decodeFromString<CreatedBody>(text)
                CreateResult.Created(c.instanceId, c.region, c.publicIp, c.serverPublicKey)
            } else {
                val e = json.decodeFromString<ExistingBody>(text)
                CreateResult.Conflict(ExistingExit(e.country, e.region, e.instanceId, e.publicIp))
            }
        }
    }

    suspend fun exitLiveness(region: String, instanceId: String): ExitLiveness {
        val state = decode<StateBody>(call(get("exits", region, instanceId))).state
        return when (state) {
            "pending", "running" -> ExitLiveness.Alive
            else -> ExitLiveness.Gone
        }
    }

    suspend fun deleteExit(region: String, instanceId: String) {
        val request = Request.Builder().url(exitUrl(region, instanceId)).delete().build()
        call(request, accept = setOf(204)) { _, _ -> }
    }

    private fun get(vararg segments: String) = Request.Builder()
        .url(base.newBuilder().apply { segments.forEach(::addPathSegment) }.build())
        .get()
        .build()

    private fun exitUrl(region: String, instanceId: String) =
        base.newBuilder().addPathSegment("exits").addPathSegment(region).addPathSegment(instanceId).build()

    private suspend fun call(request: Request): String = call(request, setOf(200)) { _, text -> text }

    private suspend fun <T> call(request: Request, accept: Set<Int>, parse: (Int, String) -> T): T {
        val authed = request.newBuilder().header("Authorization", "Bearer ${token()}").build()
        var attempt = 0
        while (true) {
            val (status, text) = withContext(Dispatchers.IO) {
                http.newCall(authed).execute().use { it.code to it.bodyText() }
            }
            when {
                status in accept -> return parse(status, text)
                status == 401 -> throw UnauthorizedException()
                status == 429 && attempt < backoffMs.size -> delay(backoffMs[attempt++])
                else -> {
                    val err = runCatching { json.decodeFromString<ErrorBody>(text) }.getOrNull()
                    throw ControlPlaneException(status, err?.error ?: "HTTP $status", err?.region, err?.instanceId)
                }
            }
        }
    }

    private inline fun <reified T> decode(text: String): T = json.decodeFromString(text)

    private fun Response.bodyText(): String = body.string()

    @Serializable private data class CountriesBody(val countries: List<String>)
    @Serializable private data class CreateBody(val country: String, val clientPublicKey: String)
    @Serializable private data class CreatedBody(
        val instanceId: String,
        val region: String,
        val publicIp: String,
        val serverPublicKey: String,
    )
    @Serializable private data class ExistingBody(
        val country: String,
        val region: String,
        val instanceId: String,
        val publicIp: String,
    )
    @Serializable private data class StateBody(val state: String)
    @Serializable private data class ErrorBody(
        val error: String,
        val region: String? = null,
        val instanceId: String? = null,
    )

    companion object {
        private val JSON = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        /** The Lambda times out at 60 s; a call never needs longer. */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

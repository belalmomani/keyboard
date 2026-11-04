package com.omsingh.beckon.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException

/** One answer from the network: the status and the body. */
data class HttpReply(val status: Int, val body: String)

/** Sends a request. A real one is [UrlConnectionTransport]; a test has its own. Throws [IOException] if the network fails. */
fun interface HttpTransport {
    @Throws(IOException::class)
    suspend fun post(url: String, headers: Map<String, String>, body: String): HttpReply
}

/**
 * Talks to a broker: a small service of your own (see `infra/aws-broker`) that keeps the real provider's key.
 * The phone holds at most a token for the broker; it never holds a provider's key, and neither does the app.
 *
 * Request: `{"task","text","style","language","commands"}`. Answer: `{"text": "..."}`.
 */
class HttpBrokerProvider(
    private val config: AiConfig,
    private val transport: HttpTransport,
) : AiProvider {

    override suspend fun complete(request: AiRequest): AiOutcome {
        if (!config.hasUsableEndpoint) return AiOutcome.NotConfigured
        val headers = buildMap {
            put("Content-Type", "application/json; charset=utf-8")
            put("Accept", "application/json")
            if (config.token.isNotBlank()) put("Authorization", "Bearer ${config.token.trim()}")
        }
        val reply = try {
            transport.post(config.endpoint.trim(), headers, body(request))
        } catch (_: IOException) {
            return AiOutcome.Offline
        }
        return when {
            reply.status == 401 || reply.status == 403 || reply.status == 429 -> AiOutcome.Rejected(reply.status)
            reply.status !in 200..299 -> AiOutcome.Failed
            else -> parse(reply.body)
        }
    }

    internal fun body(request: AiRequest): String {
        val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "task" to JsonPrimitive(request.task.wire),
            "text" to JsonPrimitive(request.text),
        )
        request.style?.let { fields["style"] = JsonPrimitive(it.wire) }
        request.language?.let { fields["language"] = JsonPrimitive(it) }
        if (request.commands.isNotEmpty()) fields["commands"] = JsonArray(request.commands.map(::JsonPrimitive))
        return JsonObject(fields).toString()
    }

    private fun parse(body: String): AiOutcome {
        if (body.length > MAX_REPLY_CHARS) return AiOutcome.Failed
        val text = runCatching {
            ((Json.parseToJsonElement(body) as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull
        }.getOrNull()
        return if (text.isNullOrBlank()) AiOutcome.Failed else AiOutcome.Success(text)
    }

    companion object {
        const val MAX_REPLY_CHARS = 16_000
    }
}

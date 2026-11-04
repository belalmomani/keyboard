package com.omsingh.beckon.core.ai

import com.omsingh.beckon.core.clipboard.SensitiveText
import com.omsingh.beckon.core.commands.Command
import com.omsingh.beckon.core.commands.CommandCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The only way the app asks an assistant for anything. It decides, before anything leaves the phone,
 * whether a request may be sent at all:
 *
 * - the assistant must be on, and have a secure address;
 * - the text must not look like a secret ([SensitiveText]);
 * - the text must not be empty or very long.
 *
 * And it decides what comes back is worth anything: a suggested command must be one of the ids it was
 * given, and must not be a dangerous one. It never runs a command; it only names one for the person to run.
 */
class AiAssistant(
    private val config: () -> AiConfig,
    private val providerFor: (AiConfig) -> AiProvider,
    private val language: () -> String? = { null },
) {

    suspend fun rewrite(text: String, style: RewriteStyle): AiOutcome {
        val gate = gate(text, MAX_TEXT_CHARS)
        if (gate != null) return gate
        return providerFor(config()).complete(
            AiRequest(AiTask.REWRITE, text.trim(), style = style, language = language()),
        )
    }

    /** What command fits [description]. On success the text is the id of a command in [suggestable]. */
    suspend fun suggestCommand(description: String): AiOutcome {
        val gate = gate(description, MAX_REQUEST_CHARS)
        if (gate != null) return gate
        val ids = suggestable().map { it.id }
        val outcome = providerFor(config()).complete(
            AiRequest(AiTask.SUGGEST_COMMAND, description.trim(), language = language(), commands = ids),
        )
        if (outcome !is AiOutcome.Success) return outcome
        val id = parseCommandId(outcome.text, ids.toSet()) ?: return AiOutcome.NotOnList
        return AiOutcome.Success(id)
    }

    private fun gate(text: String, max: Int): AiOutcome? {
        val c = config()
        if (!c.enabled) return AiOutcome.NotEnabled
        if (!c.hasUsableEndpoint) return AiOutcome.NotConfigured
        if (text.isBlank()) return AiOutcome.Empty
        if (text.length > max) return AiOutcome.TooLong
        SensitiveText.reasonToSkip(text)?.let { return AiOutcome.Sensitive(it) }
        return null
    }

    companion object {
        const val MAX_TEXT_CHARS = 4_000
        const val MAX_REQUEST_CHARS = 300

        /**
         * The commands an assistant may name: the ones the app ships, minus every one that needs a confirmation.
         * A person's own commands are left out too: their names and texts are theirs and are not sent.
         */
        fun suggestable(): List<Command> = CommandCatalog.builtIn.filter { !it.dangerous }

        /** The id in a reply, which is either a bare id or `{"command":"id"}`; null for anything else. */
        fun parseCommandId(reply: String, allowed: Set<String>): String? {
            val trimmed = reply.trim().trim('`').trim()
            val candidate = if (trimmed.startsWith("{")) {
                val obj = runCatching { Json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull() ?: return null
                (obj["command"] as? JsonPrimitive)?.contentOrNull ?: return null
            } else {
                trimmed
            }
            val id = candidate.trim().trim('"').trim()
            return id.takeIf { it in allowed }
        }
    }
}

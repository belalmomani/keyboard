package com.omsingh.beckon.core.ai

import com.omsingh.beckon.core.clipboard.SensitiveText

/** What the person asked the assistant to do. Nothing here runs by itself: every result is a proposal. */
enum class AiTask(val wire: String) {
    /** Rewrite a text the person chose to send. */
    REWRITE("rewrite"),

    /** Name one command, out of a fixed list, that fits what the person described. */
    SUGGEST_COMMAND("command"),
}

enum class RewriteStyle(val wire: String) {
    FIX("fix"),
    SHORTEN("shorten"),
    FORMAL("formal"),
    FRIENDLY("friendly"),
}

/**
 * What is sent to a provider. It is only what the person typed or chose for this one request: never the
 * clipboard history, the phrase library, window titles or anything else the app holds.
 */
data class AiRequest(
    val task: AiTask,
    val text: String,
    val style: RewriteStyle? = null,
    /** BCP-47 tag of the phone's language, so a reply can be in the person's language. */
    val language: String? = null,
    /** For [AiTask.SUGGEST_COMMAND]: the command ids the answer may name. Nothing else is accepted back. */
    val commands: List<String> = emptyList(),
)

/** How a request ended. Every case is shown to the person as it is; a failure is never dressed up as an answer. */
sealed interface AiOutcome {
    data class Success(val text: String) : AiOutcome

    /** The person has not turned the assistant on. Nothing was sent. */
    data object NotEnabled : AiOutcome

    /** It is on, but there is no usable address for a service. Nothing was sent. */
    data object NotConfigured : AiOutcome

    /** The text looks like a password, a card number or a key, so it was not sent. */
    data class Sensitive(val reason: SensitiveText.Reason) : AiOutcome

    data object Empty : AiOutcome
    data object TooLong : AiOutcome

    /** The service could not be reached. */
    data object Offline : AiOutcome

    /** The service answered that it will not do this (no or wrong key, a limit reached). */
    data class Rejected(val httpStatus: Int) : AiOutcome

    /** The service failed, or answered with something that cannot be used. */
    data object Failed : AiOutcome

    /** For a command suggestion: the service named something that is not on the list, so it is thrown away. */
    data object NotOnList : AiOutcome
}

/**
 * A service that answers requests. The app talks to this interface only, so a provider can be replaced
 * (a broker of your own, another company's service) without touching anything that uses it.
 */
interface AiProvider {
    suspend fun complete(request: AiRequest): AiOutcome
}

/** What the person set up. The key is kept encrypted on the phone and is never part of the app. */
data class AiConfig(
    val enabled: Boolean = false,
    val endpoint: String = "",
    val token: String = "",
) {
    /** A secure address with a host. Plain http is refused: the text of a request must not cross a network in the clear. */
    val hasUsableEndpoint: Boolean
        get() = endpoint.trim().let { url ->
            url.startsWith("https://", ignoreCase = true) && url.length > "https://".length &&
                !url.any { it.isWhitespace() }
        }
}

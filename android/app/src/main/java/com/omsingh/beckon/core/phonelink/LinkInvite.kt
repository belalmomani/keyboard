package com.omsingh.beckon.core.phonelink

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64

/**
 * What the QR code on the receiving phone says: where to connect, and the one-time secret that proves the
 * phone that scanned it was shown the code.
 *
 * It is a link, `beckon://link?v=1&k=...&p=40123&h=192.168.1.20,10.0.0.4&n=Belal`:
 *
 *  - `v` the format version (this reads 1)
 *  - `k` the secret, 32 random bytes, base64url without padding. It never travels over the network:
 *    the two phones use it to key their encryption, so only a phone that saw the code can talk.
 *  - `p` the TCP port the receiving phone is listening on
 *  - `h` its IPv4 addresses, comma separated, best first
 *  - `n` its name, percent-encoded
 */
class LinkInvite(
    val hosts: List<String>,
    val port: Int,
    val secret: ByteArray,
    val name: String?,
) {
    fun toUri(): String {
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
        val builder = StringBuilder("beckon://link?v=$VERSION&k=$key&p=$port&h=${hosts.joinToString(",")}")
        if (!name.isNullOrBlank()) builder.append("&n=").append(URLEncoder.encode(name, "UTF-8"))
        return builder.toString()
    }

    /** The secret is held by the code's owner for a few minutes: it is never printed. */
    override fun toString(): String = "LinkInvite(name=$name, hosts=$hosts, port=$port)"

    override fun equals(other: Any?): Boolean =
        other is LinkInvite && hosts == other.hosts && port == other.port &&
            secret.contentEquals(other.secret) && name == other.name

    override fun hashCode(): Int = (hosts.hashCode() * 31 + port) * 31 + secret.contentHashCode()

    /** How reading a piece of text went. */
    sealed interface Read {
        data class Valid(val invite: LinkInvite) : Read

        /** Some other text: not a code for linking phones. */
        data object NotALink : Read

        /** A link code from a newer version of the app. */
        data object NeedsNewerApp : Read

        /** Looked like a link code but cannot be used. */
        data object Damaged : Read
    }

    companion object {
        const val VERSION = 1
        const val SECRET_BYTES = 32
        private const val PREFIX = "beckon://link?"

        fun create(hosts: List<String>, port: Int, name: String?): LinkInvite {
            val secret = ByteArray(SECRET_BYTES)
            SecureRandom().nextBytes(secret)
            return LinkInvite(hosts, port, secret, name)
        }

        fun parse(text: String): Read {
            val trimmed = text.trim()
            if (!trimmed.startsWith(PREFIX, ignoreCase = true)) return Read.NotALink
            val fields = HashMap<String, String>()
            for (part in trimmed.substring(PREFIX.length).split('&')) {
                val at = part.indexOf('=')
                if (at <= 0) continue
                fields[part.substring(0, at)] = part.substring(at + 1)
            }
            val version = fields["v"]?.toIntOrNull() ?: return Read.Damaged
            if (version > VERSION) return Read.NeedsNewerApp
            if (version < 1) return Read.Damaged
            val secret = try {
                Base64.getUrlDecoder().decode(fields["k"] ?: return Read.Damaged)
            } catch (_: IllegalArgumentException) {
                return Read.Damaged
            }
            if (secret.size != SECRET_BYTES) return Read.Damaged
            val port = fields["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return Read.Damaged
            val hosts = (fields["h"] ?: "").split(',').map { it.trim() }.filter(::looksLikeIpv4)
            if (hosts.isEmpty()) return Read.Damaged
            val name = fields["n"]?.let {
                try {
                    URLDecoder.decode(it, "UTF-8").take(MAX_NAME_CHARS)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            return Read.Valid(LinkInvite(hosts, port, secret, name?.ifBlank { null }))
        }

        const val MAX_NAME_CHARS = 40

        private fun looksLikeIpv4(text: String): Boolean {
            val parts = text.split('.')
            return parts.size == 4 && parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true && part.length <= 3 }
        }
    }
}

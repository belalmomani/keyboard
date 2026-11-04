package com.omsingh.beckon.core.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** [HttpTransport] over the platform's own HTTP client: no extra library, short timeouts, no redirects followed. */
class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 20_000,
) : HttpTransport {

    override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpReply =
        withContext(Dispatchers.IO) {
            val connection = try {
                URL(url).openConnection() as HttpURLConnection
            } catch (e: ClassCastException) {
                throw IOException("not an http address", e)
            }
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                // A redirect could carry the token to another host.
                connection.instanceFollowRedirects = false
                connection.doOutput = true
                headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.use { it.readBytes(MAX_BYTES).toString(Charsets.UTF_8) }.orEmpty()
                HttpReply(status, text)
            } finally {
                connection.disconnect()
            }
        }

    private fun java.io.InputStream.readBytes(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (out.size() <= limit) {
            val n = read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray().let { if (it.size > limit) it.copyOf(limit + 1) else it }
    }

    private companion object {
        const val MAX_BYTES = 32_000
    }
}

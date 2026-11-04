package com.omsingh.beckon.core.ai

import com.omsingh.beckon.core.clipboard.SensitiveText
import com.omsingh.beckon.core.commands.CommandCatalog
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AiTest {

    private val on = AiConfig(enabled = true, endpoint = "https://broker.example/ai", token = "tok-123")

    private class Fake(var reply: AiOutcome = AiOutcome.Success("ok")) : AiProvider {
        val seen = mutableListOf<AiRequest>()
        override suspend fun complete(request: AiRequest): AiOutcome { seen += request; return reply }
    }

    private fun assistant(config: AiConfig, provider: Fake) =
        AiAssistant(config = { config }, providerFor = { provider }, language = { "ar" })

    // ── The gate: what may leave the phone ──────────────────────────

    @Test
    fun `off by default, and nothing is sent while it is off`() = runBlocking {
        val p = Fake()
        assertEquals(AiOutcome.NotEnabled, assistant(AiConfig(), p).rewrite("hello", RewriteStyle.FIX))
        assertEquals(AiOutcome.NotEnabled, assistant(on.copy(enabled = false), p).suggestCommand("copy"))
        assertTrue(p.seen.isEmpty())
    }

    @Test
    fun `on without a secure address is not configured, and nothing is sent`() = runBlocking {
        val p = Fake()
        for (endpoint in listOf("", "   ", "http://broker.example/ai", "https://", "ftp://x", "https://a b")) {
            assertEquals(endpoint, AiOutcome.NotConfigured, assistant(on.copy(endpoint = endpoint), p).rewrite("hello", RewriteStyle.FIX))
        }
        assertTrue(p.seen.isEmpty())
    }

    @Test
    fun `text that looks like a secret is never sent`() = runBlocking {
        val p = Fake()
        val a = assistant(on, p)
        assertTrue(a.rewrite("password: hunter2", RewriteStyle.FIX) is AiOutcome.Sensitive)
        assertTrue(a.rewrite("4111 1111 1111 1111", RewriteStyle.FIX) is AiOutcome.Sensitive)
        assertTrue(a.rewrite("-----BEGIN PRIVATE KEY-----", RewriteStyle.FIX) is AiOutcome.Sensitive)
        assertTrue(a.suggestCommand("123456") is AiOutcome.Sensitive)
        assertTrue(p.seen.isEmpty())
    }

    @Test
    fun `empty and over-long text is refused`() = runBlocking {
        val p = Fake()
        val a = assistant(on, p)
        assertEquals(AiOutcome.Empty, a.rewrite("  \n ", RewriteStyle.FIX))
        assertEquals(AiOutcome.TooLong, a.rewrite("a".repeat(AiAssistant.MAX_TEXT_CHARS + 1), RewriteStyle.FIX))
        assertEquals(AiOutcome.TooLong, a.suggestCommand("a".repeat(AiAssistant.MAX_REQUEST_CHARS + 1)))
        assertTrue(p.seen.isEmpty())
    }

    @Test
    fun `a rewrite sends only the text, the style and the language`() = runBlocking {
        val p = Fake(AiOutcome.Success("better"))
        val out = assistant(on, p).rewrite("  hello wrold  ", RewriteStyle.FORMAL)
        assertEquals(AiOutcome.Success("better"), out)
        assertEquals(AiRequest(AiTask.REWRITE, "hello wrold", RewriteStyle.FORMAL, "ar", emptyList()), p.seen.single())
    }

    // ── Commands: a suggestion is a name on a list, nothing more ─────

    @Test
    fun `an assistant is offered only commands that need no confirmation`() {
        val ids = AiAssistant.suggestable().map { it.id }
        assertFalse("lock" in ids)
        assertFalse("close_window" in ids)
        assertTrue("copy" in ids && "play_pause" in ids)
        assertTrue(AiAssistant.suggestable().none { it.dangerous })
        assertTrue(ids.all { id -> CommandCatalog.builtIn.any { it.id == id } })
    }

    @Test
    fun `a named command on the list is accepted, as a bare id or as json`() = runBlocking {
        for (reply in listOf("copy", " \"copy\" ", "{\"command\":\"copy\"}", "```{\"command\": \"copy\"}```")) {
            val p = Fake(AiOutcome.Success(reply))
            assertEquals(reply, AiOutcome.Success("copy"), assistant(on, p).suggestCommand("copy this"))
            assertEquals(AiAssistant.suggestable().map { it.id }, p.seen.single().commands)
        }
    }

    @Test
    fun `anything not on the list is thrown away, including a dangerous or invented command`() = runBlocking {
        for (reply in listOf("lock", "close_window", "rm -rf /", "{\"command\":\"format_disk\"}", "{\"cmd\":\"copy\"}", "{bad", "copy and paste", "")) {
            assertEquals(reply, AiOutcome.NotOnList, assistant(on, Fake(AiOutcome.Success(reply))).suggestCommand("do it"))
        }
    }

    @Test
    fun `a failure from the provider passes through untouched`() = runBlocking {
        assertEquals(AiOutcome.Offline, assistant(on, Fake(AiOutcome.Offline)).suggestCommand("copy"))
        assertEquals(AiOutcome.Rejected(401), assistant(on, Fake(AiOutcome.Rejected(401))).rewrite("hi", RewriteStyle.FIX))
    }

    // ── The broker provider ──────────────────────────────────────────

    private class FakeTransport(val reply: () -> HttpReply) : HttpTransport {
        var url: String? = null
        var headers: Map<String, String> = emptyMap()
        var body: String = ""
        override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpReply {
            this.url = url; this.headers = headers; this.body = body
            return reply()
        }
    }

    private val rewrite = AiRequest(AiTask.REWRITE, "hi \"there\"\nline", RewriteStyle.SHORTEN, "en")

    @Test
    fun `the request is json, the token is only in the header`() = runBlocking {
        val t = FakeTransport { HttpReply(200, "{\"text\":\"short\"}") }
        val out = HttpBrokerProvider(on, t).complete(rewrite)
        assertEquals(AiOutcome.Success("short"), out)
        assertEquals("https://broker.example/ai", t.url)
        assertEquals("Bearer tok-123", t.headers["Authorization"])
        assertFalse(t.body.contains("tok-123"))
        val sent = Json.parseToJsonElement(t.body) as JsonObject
        assertEquals(JsonPrimitive("rewrite"), sent["task"])
        assertEquals(JsonPrimitive("hi \"there\"\nline"), sent["text"])
        assertEquals(JsonPrimitive("shorten"), sent["style"])
        assertEquals(JsonPrimitive("en"), sent["language"])
        assertFalse(sent.containsKey("commands"))
    }

    @Test
    fun `a command request lists the allowed ids`() = runBlocking {
        val t = FakeTransport { HttpReply(200, "{\"text\":\"copy\"}") }
        HttpBrokerProvider(on.copy(token = ""), t).complete(AiRequest(AiTask.SUGGEST_COMMAND, "copy", commands = listOf("copy", "paste")))
        assertFalse(t.headers.containsKey("Authorization"))
        val sent = Json.parseToJsonElement(t.body) as JsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive("copy"), JsonPrimitive("paste"))), sent["commands"])
    }

    @Test
    fun `statuses are told apart`() = runBlocking {
        fun outcome(status: Int, body: String = "") = runBlocking { HttpBrokerProvider(on, FakeTransport { HttpReply(status, body) }).complete(rewrite) }
        assertEquals(AiOutcome.Rejected(401), outcome(401))
        assertEquals(AiOutcome.Rejected(403), outcome(403))
        assertEquals(AiOutcome.Rejected(429), outcome(429))
        assertEquals(AiOutcome.Failed, outcome(500))
        assertEquals(AiOutcome.Failed, outcome(404))
        assertEquals(AiOutcome.Failed, outcome(200, "not json"))
        assertEquals(AiOutcome.Failed, outcome(200, "{\"text\":\"  \"}"))
        assertEquals(AiOutcome.Failed, outcome(200, "{\"other\":\"x\"}"))
        assertEquals(AiOutcome.Failed, outcome(200, "{\"text\":\"" + "a".repeat(HttpBrokerProvider.MAX_REPLY_CHARS) + "\"}"))
    }

    @Test
    fun `no network is offline, and a missing address sends nothing`() = runBlocking {
        assertEquals(AiOutcome.Offline, HttpBrokerProvider(on, FakeTransport { throw IOException("down") }).complete(rewrite))
        val t = FakeTransport { HttpReply(200, "{\"text\":\"x\"}") }
        assertEquals(AiOutcome.NotConfigured, HttpBrokerProvider(on.copy(endpoint = "http://x"), t).complete(rewrite))
        assertEquals(null, t.url)
    }

    @Test
    fun `sensitive reasons are the shared ones`() {
        assertEquals(SensitiveText.Reason.LABELLED_SECRET, SensitiveText.reasonToSkip("password: abc"))
    }
}

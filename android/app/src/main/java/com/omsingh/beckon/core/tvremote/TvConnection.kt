package com.omsingh.beckon.core.tvremote

import android.annotation.SuppressLint
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/**
 * How this phone talks to an Android TV or Google TV over the network: the same protocol the Google TV app uses.
 * Pairing is on port 6467 (the TV shows a code, the person types it here), the remote itself on port 6466.
 * Both are TLS with this phone's own certificate, which the TV remembers once it is paired.
 */
object TvProtocol {
    const val PAIRING_PORT = 6467
    const val REMOTE_PORT = 6466
    const val SERVICE_TYPE = "_androidtvremote2._tcp."

    private const val STATUS_OK = 200
    private const val VERSION = 2
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val ANSWER_TIMEOUT_MS = 20_000

    /** A TLS socket to the TV that shows this phone's certificate and takes the TV's, whatever it is: the pairing code is what proves it. */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    internal fun open(identity: TvIdentity, host: String, port: Int): SSLSocket {
        val keys = object : X509KeyManager {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
            override fun getCertificateChain(alias: String?) = arrayOf(identity.certificate)
            override fun getPrivateKey(alias: String?): PrivateKey = identity.key
        }
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(arrayOf(keys), arrayOf<TrustManager>(trust), SecureRandom())
        val plain = Socket()
        plain.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        val socket = context.socketFactory.createSocket(plain, host, port, true) as SSLSocket
        socket.soTimeout = ANSWER_TIMEOUT_MS
        socket.startHandshake()
        return socket
    }

    private const val ALIAS = "beckon"

    // ── Messages are a length (a varint), then that many bytes ────────

    internal fun write(out: OutputStream, message: ByteArray) {
        out.write(Proto.varint(message.size.toLong()) + message)
        out.flush()
    }

    internal fun read(input: InputStream): ByteArray {
        var length = 0
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("closed")
            length = length or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 28) throw IOException("bad length")
        }
        if (length < 0 || length > 1 shl 20) throw IOException("bad length")
        val data = ByteArray(length)
        var got = 0
        while (got < length) {
            val n = input.read(data, got, length - got)
            if (n < 0) throw IOException("closed")
            got += n
        }
        return data
    }

    // ── Pairing ───────────────────────────────────────────────────────

    /** A pairing message: version and status, then what is being said under its number. */
    private fun pairingMessage(vararg parts: ByteArray) =
        Proto.message(Proto.int(1, VERSION), Proto.int(2, STATUS_OK), *parts)

    private fun expect(input: InputStream, field: Int): Proto.Fields {
        val fields = Proto.parse(read(input))
        if (fields.long(2) != STATUS_OK.toLong() || !fields.has(field)) throw IOException("the TV refused")
        return fields
    }

    /** What the TV and this phone agree on for the code: six hexadecimal characters, typed by the person. */
    private val ENCODING = Proto.message(Proto.int(1, 3), Proto.int(2, 6))

    /** A pairing in progress: the TV is showing a code. */
    class Pairing internal constructor(
        private val socket: SSLSocket,
        private val clientCertificate: X509Certificate,
    ) : AutoCloseable {

        enum class Result { PAIRED, WRONG_CODE, FAILED }

        /** Sends the code the person typed. A code that is not the one the TV shows is noticed here, and can be tried again. */
        fun finish(code: String): Result {
            return try {
                val server = socket.session.peerCertificates[0] as X509Certificate
                val secret = secretFor(clientCertificate, server, code) ?: return Result.WRONG_CODE
                write(socket.outputStream, pairingMessage(Proto.bytes(40, Proto.bytes(1, secret))))
                expect(socket.inputStream, 41)
                close()
                Result.PAIRED
            } catch (_: IOException) {
                close()
                Result.FAILED
            }
        }

        override fun close() {
            try { socket.close() } catch (_: IOException) {}
        }
    }

    /** Starts pairing: afterwards the TV shows a code, and [Pairing.finish] takes it. */
    fun beginPairing(identity: TvIdentity, host: String, clientName: String): Pairing {
        val socket = open(identity, host, PAIRING_PORT)
        try {
            val out = socket.outputStream
            val input = socket.inputStream
            write(out, pairingMessage(Proto.bytes(10, Proto.message(Proto.string(1, "androidtvremote2"), Proto.string(2, clientName)))))
            expect(input, 11)
            write(out, pairingMessage(Proto.bytes(20, Proto.message(Proto.bytes(1, ENCODING), Proto.int(3, 1)))))
            expect(input, 20)
            write(out, pairingMessage(Proto.bytes(30, Proto.message(Proto.bytes(1, ENCODING), Proto.int(2, 1)))))
            expect(input, 31)
            socket.soTimeout = 0
            return Pairing(socket, identity.certificate)
        } catch (e: Exception) {
            try { socket.close() } catch (_: IOException) {}
            throw if (e is IOException) e else IOException(e)
        }
    }

    /**
     * The answer to the code: SHA-256 of both certificates' keys and the code's last two bytes. The code's first byte is
     * the first byte of that answer, which is how a wrong code is noticed here. Null if it is not six hexadecimal characters
     * or does not fit.
     */
    internal fun secretFor(client: X509Certificate, server: X509Certificate, code: String): ByteArray? {
        val text = code.trim()
        if (text.length != 6 || !text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val bytes = ByteArray(3) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val digest = MessageDigest.getInstance("SHA-256")
        for (cert in listOf(client, server)) {
            val key = cert.publicKey as? RSAPublicKey ?: return null
            digest.update(unsigned(key.modulus.toByteArray()))
            digest.update(unsigned(key.publicExponent.toByteArray()))
        }
        digest.update(bytes, 1, 2)
        val hash = digest.digest()
        return if (hash[0] == bytes[0]) hash else null
    }

    /** A big number's bytes without the sign byte [java.math.BigInteger] puts in front. */
    private fun unsigned(bytes: ByteArray): ByteArray = if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes

    // ── The remote ────────────────────────────────────────────────────

    /** Keys the TV understands (Android key codes). */
    object Key {
        const val HOME = 3
        const val BACK = 4
        const val UP = 19
        const val DOWN = 20
        const val LEFT = 21
        const val RIGHT = 22
        const val OK = 23
        const val VOLUME_UP = 24
        const val VOLUME_DOWN = 25
        const val POWER = 26
        const val SPACE = 62
        const val ENTER = 66
        const val DELETE = 67
        const val MENU = 82
        const val PLAY_PAUSE = 85
        const val MUTE = 164
        const val CHANNEL_UP = 166
        const val CHANNEL_DOWN = 167
        const val SHIFT = 59
    }

    private const val FEATURES = 622L

    /** An open remote: keys go to the TV through [press]; [onClosed] is called once when the connection ends. */
    class Remote internal constructor(
        private val socket: SSLSocket,
        private val onClosed: () -> Unit,
    ) : AutoCloseable {
        private val out = socket.outputStream
        @Volatile private var closed = false

        /** Whether the TV has said anything: a TV that has not paired with this phone closes at once, saying nothing. */
        @Volatile var heard = false
            private set
        @Volatile private var imeCounter = -1
        @Volatile private var fieldCounter = -1

        @Synchronized
        private fun send(message: ByteArray) {
            if (closed) return
            try {
                write(out, message)
            } catch (_: IOException) {
                close()
            }
        }

        /** Reads what the TV says and answers what has to be answered, until the connection ends. */
        internal fun run() {
            try {
                val input = socket.inputStream
                while (!closed) {
                    val message = Proto.parse(read(input))
                    heard = true
                    when {
                        message.has(1) -> send(
                            Proto.bytes(
                                1,
                                Proto.message(
                                    Proto.int(1, FEATURES.toInt()),
                                    Proto.bytes(
                                        2,
                                        Proto.message(
                                            Proto.string(1, "Beckon"), Proto.string(2, "Beckon"), Proto.int(3, 1),
                                            Proto.string(4, "1"), Proto.string(5, "com.omsingh.beckon"), Proto.string(6, "1.0.0"),
                                        ),
                                    ),
                                ),
                            ),
                        )
                        message.has(2) -> send(Proto.bytes(2, Proto.int(1, FEATURES.toInt())))
                        message.has(8) -> send(Proto.bytes(9, Proto.int(1, message.message(8)?.long(1)?.toInt() ?: 0)))
                        message.has(20) -> {
                            val ime = message.message(20)
                            imeCounter = ime?.message(1)?.long(1)?.toInt() ?: imeCounter
                            fieldCounter = ime?.message(2)?.long(1)?.toInt() ?: fieldCounter
                        }
                    }
                }
            } catch (_: Exception) {
                // The connection ended.
            } finally {
                close()
            }
        }

        /** One press of a key. */
        fun press(key: Int) = send(Proto.bytes(10, Proto.message(Proto.int(1, key), Proto.int(2, 3))))

        private fun hold(key: Int, down: Boolean) = send(Proto.bytes(10, Proto.message(Proto.int(1, key), Proto.int(2, if (down) 1 else 2))))

        /** Types [text] on the TV: letters, digits and a few signs as keys; anything else through the TV's text field, if one is open. */
        fun type(text: String) {
            val others = StringBuilder()
            for (c in text) {
                val plain = keyOf(c.lowercaseChar())
                when {
                    plain == null -> others.append(c)
                    c.isUpperCase() -> { hold(Key.SHIFT, true); press(plain); hold(Key.SHIFT, false) }
                    else -> press(plain)
                }
            }
            if (others.isNotEmpty() && imeCounter >= 0 && fieldCounter >= 0) {
                val value = others.toString()
                val status = Proto.message(Proto.int(1, fieldCounter), Proto.string(2, value), Proto.int(3, value.length - 1), Proto.int(4, value.length - 1))
                send(
                    Proto.bytes(
                        21,
                        Proto.message(
                            Proto.int(1, imeCounter), Proto.int(2, fieldCounter),
                            Proto.bytes(3, Proto.message(Proto.int(1, 1), Proto.bytes(2, status))),
                        ),
                    ),
                )
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            try { socket.close() } catch (_: IOException) {}
            onClosed()
        }
    }

    /** The key for a character, or null if there is none to press. */
    internal fun keyOf(c: Char): Int? = when (c) {
        in 'a'..'z' -> 29 + (c - 'a')
        in '0'..'9' -> 7 + (c - '0')
        ' ' -> Key.SPACE
        '.' -> 56
        ',' -> 55
        '-' -> 69
        '/' -> 76
        '@' -> 77
        '\n' -> Key.ENTER
        else -> null
    }

    /** Connects the remote to a paired TV and keeps answering it on a thread of its own. Throws if the TV does not take this phone (not paired). */
    fun connect(identity: TvIdentity, host: String, onClosed: () -> Unit): Remote {
        val socket = open(identity, host, REMOTE_PORT)
        socket.soTimeout = 0
        val remote = Remote(socket, onClosed)
        Thread({ remote.run() }, "beckon-tv-remote").apply { isDaemon = true }.start()
        return remote
    }
}

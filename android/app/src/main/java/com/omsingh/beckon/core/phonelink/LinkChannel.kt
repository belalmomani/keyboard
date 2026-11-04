package com.omsingh.beckon.core.phonelink

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The other side is not who the code was shown to, or the conversation was tampered with. */
class LinkSecurityException(message: String) : IOException(message)

/**
 * An encrypted, ordered conversation between two phones over a TCP stream.
 *
 * Both phones know the one-time secret from the QR code. Each side adds a fresh random value
 * (16 bytes, sent in the clear), and from the secret and both values they derive two keys, one for each
 * direction, so that no key is ever used in two conversations. Every record is then sealed with AES-GCM; its
 * nonce is a counter both sides keep, so a record that is repeated, dropped, reordered or altered cannot be
 * opened and ends the conversation. Anyone who did not see the code cannot make a record that opens.
 *
 *  - sender to receiver, once: `"BKLK"` and 16 random bytes
 *  - receiver to sender, once: 16 random bytes
 *  - then, each way, records: `[u32 length][sealed bytes]`
 *
 * Not seeing a record that opens is the only proof of knowing the secret, so the receiver's first record
 * (Accepted or Rejected) is also what proves *it* knew the secret to the sender.
 */
class LinkChannel private constructor(
    input: InputStream,
    output: OutputStream,
    private val sendKey: SecretKeySpec,
    private val receiveKey: SecretKeySpec,
) {
    private val input = DataInputStream(input)
    private val output = DataOutputStream(output)
    private var sent = 0L
    private var received = 0L

    /** Seals and sends one message. Safe to call from several threads. */
    @Synchronized
    fun send(plain: ByteArray) {
        require(plain.size <= MAX_RECORD_BYTES) { "message too large" }
        val sealed = crypt(Cipher.ENCRYPT_MODE, sendKey, sent++, plain)
        output.writeInt(sealed.size)
        output.write(sealed)
        output.flush()
    }

    /** Reads and opens the next message. Not for several threads at once. */
    fun receive(): ByteArray {
        val size = input.readInt()
        if (size < TAG_BYTES || size > MAX_RECORD_BYTES + TAG_BYTES) throw LinkSecurityException("bad record size")
        val sealed = ByteArray(size)
        input.readFully(sealed)
        return try {
            crypt(Cipher.DECRYPT_MODE, receiveKey, received++, sealed)
        } catch (_: GeneralSecurityException) {
            throw LinkSecurityException("record did not open")
        }
    }

    private fun crypt(mode: Int, key: SecretKeySpec, counter: Long, data: ByteArray): ByteArray {
        val nonce = ByteArray(12)
        for (i in 0 until 8) nonce[11 - i] = (counter ushr (8 * i)).toByte()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, key, GCMParameterSpec(TAG_BYTES * 8, nonce))
        return cipher.doFinal(data)
    }

    companion object {
        /** The biggest message (a picture of a screen is the largest). */
        const val MAX_RECORD_BYTES = 4 * 1024 * 1024
        private const val TAG_BYTES = 16
        private const val NONCE_BYTES = 16
        private val MAGIC = byteArrayOf('B'.code.toByte(), 'K'.code.toByte(), 'L'.code.toByte(), 'K'.code.toByte())
        private val random = SecureRandom()

        /** The phone that scanned the code: starts the conversation. */
        fun asSender(input: InputStream, output: OutputStream, secret: ByteArray): LinkChannel {
            val mine = ByteArray(NONCE_BYTES).also(random::nextBytes)
            val out = DataOutputStream(output)
            out.write(MAGIC)
            out.write(mine)
            out.flush()
            val theirs = ByteArray(NONCE_BYTES)
            DataInputStream(input).readFully(theirs)
            return open(input, output, secret, mine, theirs, sender = true)
        }

        /** The phone that showed the code: answers. */
        fun asReceiver(input: InputStream, output: OutputStream, secret: ByteArray): LinkChannel {
            val data = DataInputStream(input)
            val magic = ByteArray(MAGIC.size)
            data.readFully(magic)
            if (!magic.contentEquals(MAGIC)) throw LinkSecurityException("not a Beckon link")
            val theirs = ByteArray(NONCE_BYTES)
            data.readFully(theirs)
            val mine = ByteArray(NONCE_BYTES).also(random::nextBytes)
            DataOutputStream(output).apply {
                write(mine)
                flush()
            }
            return open(input, output, secret, theirs, mine, sender = false)
        }

        private fun open(
            input: InputStream,
            output: OutputStream,
            secret: ByteArray,
            senderValue: ByteArray,
            receiverValue: ByteArray,
            sender: Boolean,
        ): LinkChannel {
            val toReceiver = derive(secret, "beckon-link sender->receiver", senderValue, receiverValue)
            val toSender = derive(secret, "beckon-link receiver->sender", senderValue, receiverValue)
            return if (sender) LinkChannel(input, output, toReceiver, toSender) else LinkChannel(input, output, toSender, toReceiver)
        }

        private fun derive(secret: ByteArray, label: String, a: ByteArray, b: ByteArray): SecretKeySpec {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret, "HmacSHA256"))
            mac.update(label.toByteArray(Charsets.UTF_8))
            mac.update(a)
            mac.update(b)
            return SecretKeySpec(mac.doFinal(), "AES")
        }
    }
}

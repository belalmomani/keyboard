package com.omsingh.beckon.core.tvremote

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * This phone's certificate for talking to a TV. The TV remembers it once the pairing code has been entered, so it is made
 * once and kept (in the app's own folder); a new one would mean pairing again.
 */
class TvIdentity(val key: PrivateKey, val certificate: X509Certificate) {

    companion object {
        /** Loads the kept identity from [directory], or makes and keeps one. */
        fun loadOrCreate(directory: File): TvIdentity {
            val keyFile = File(directory, "identity.key")
            val certFile = File(directory, "identity.crt")
            try {
                if (keyFile.isFile && certFile.isFile) {
                    val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                    return TvIdentity(key, parse(certFile.readBytes()))
                }
            } catch (_: Exception) {
                // Unreadable: a new one is made below.
            }
            val made = create()
            try {
                directory.mkdirs()
                keyFile.writeBytes(made.key.encoded)
                certFile.writeBytes(made.certificate.encoded)
            } catch (_: Exception) {
                // Not kept: it works for now, and the TV has to be paired again next time.
            }
            return made
        }

        internal fun parse(der: ByteArray): X509Certificate =
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

        /** A new key and a self-signed certificate for it, good for twenty years. */
        fun create(nowMs: Long = System.currentTimeMillis()): TvIdentity {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = der(0x30, der(0x31, der(0x30, der(0x06, byteArrayOf(0x55, 0x04, 0x03)) + der(0x0C, "Beckon".toByteArray()))))
            val algorithm = der(0x30, der(0x06, byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)) + der(0x05, ByteArray(0)))
            val format = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val from = format.format(Date(nowMs - DAY_MS))
            val until = format.format(Date(nowMs + 20 * 365 * DAY_MS))
            val serial = BigInteger(64, java.security.SecureRandom()).abs().add(BigInteger.ONE)
            val tbs = der(
                0x30,
                der(0xA0, der(0x02, byteArrayOf(2))) +
                    der(0x02, serial.toByteArray()) +
                    algorithm + name +
                    der(0x30, der(0x17, from.toByteArray()) + der(0x17, until.toByteArray())) +
                    name + pair.public.encoded,
            )
            val signature = Signature.getInstance("SHA256withRSA").run {
                initSign(pair.private)
                update(tbs)
                sign()
            }
            val certificate = der(0x30, tbs + algorithm + der(0x03, byteArrayOf(0) + signature))
            return TvIdentity(pair.private, parse(certificate))
        }

        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** One DER element: tag, length, content. */
        internal fun der(tag: Int, content: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            val n = content.size
            when {
                n < 0x80 -> out.write(n)
                n < 0x100 -> { out.write(0x81); out.write(n) }
                else -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
            }
            out.write(content)
            return out.toByteArray()
        }
    }
}

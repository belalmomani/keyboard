package com.omsingh.beckon.core.tvremote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey

class TvRemoteProtocolTest {

    @Test
    fun `numbers and nested messages survive being written and read`() {
        val inner = Proto.message(Proto.int(1, 300), Proto.string(2, "Beckon"))
        val outer = Proto.message(Proto.int(1, 2), Proto.int(2, 200), Proto.bytes(10, inner))
        val read = Proto.parse(outer)
        assertEquals(2L, read.long(1))
        assertEquals(200L, read.long(2))
        val nested = read.message(10)!!
        assertEquals(300L, nested.long(1))
        assertEquals("Beckon", nested.string(2))
        assertFalse(read.has(11))
    }

    @Test
    fun `a varint is the shortest it can be`() {
        assertArrayEquals(byteArrayOf(0x00), Proto.varint(0))
        assertArrayEquals(byteArrayOf(0x7F), Proto.varint(127))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x01), Proto.varint(128))
        assertArrayEquals(byteArrayOf(0xAC.toByte(), 0x02), Proto.varint(300))
    }

    @Test
    fun `reading broken bytes keeps what came before and does not crash`() {
        val good = Proto.int(1, 7)
        val read = Proto.parse(good + byteArrayOf(0x12, 0x7F)) // a field that says it is longer than what is left
        assertEquals(7L, read.long(1))
    }

    @Test
    fun `the certificate is a real one, signed by its own key`() {
        val identity = TvIdentity.create()
        val cert = identity.certificate
        cert.checkValidity()
        cert.verify(cert.publicKey)
        assertTrue(cert.publicKey is RSAPublicKey)
        val probe = Signature.getInstance("SHA256withRSA").run { initSign(identity.key); update(byteArrayOf(1, 2, 3)); sign() }
        assertTrue(Signature.getInstance("SHA256withRSA").run { initVerify(cert.publicKey); update(byteArrayOf(1, 2, 3)); verify(probe) })
    }

    @Test
    fun `the code is checked against the answer both ends can work out`() {
        val client = TvIdentity.create().certificate
        val server = TvIdentity.create().certificate
        // Work the right code out the way the protocol says, then see that it is accepted and a changed one is not.
        val digest = MessageDigest.getInstance("SHA-256")
        for (cert in listOf(client, server)) {
            val key = cert.publicKey as RSAPublicKey
            fun unsigned(b: ByteArray) = if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
            digest.update(unsigned(key.modulus.toByteArray()))
            digest.update(unsigned(key.publicExponent.toByteArray()))
        }
        val tail = byteArrayOf(0x12, 0x34)
        digest.update(tail)
        val hash = digest.digest()
        val code = "%02X1234".format(hash[0].toInt() and 0xFF)
        val answer = TvProtocol.secretFor(client, server, code)
        assertNotNull(answer)
        assertArrayEquals(hash, answer)
        val wrong = "%02X1234".format((hash[0].toInt() + 1) and 0xFF)
        assertNull(TvProtocol.secretFor(client, server, wrong))
        assertNull(TvProtocol.secretFor(client, server, "12"))
        assertNull(TvProtocol.secretFor(client, server, "ZZZZZZ"))
    }

    @Test
    fun `letters digits and a few signs have keys`() {
        assertEquals(29, TvProtocol.keyOf('a'))
        assertEquals(54, TvProtocol.keyOf('z'))
        assertEquals(7, TvProtocol.keyOf('0'))
        assertEquals(16, TvProtocol.keyOf('9'))
        assertEquals(62, TvProtocol.keyOf(' '))
        assertNull(TvProtocol.keyOf('ب'))
    }
}

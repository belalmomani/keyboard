package com.omsingh.beckon.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextBlockCodecTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

    @Test
    fun `size counts utf-8 bytes of every kind of character`() {
        assertEquals(0, TextBlockCodec.utf8Size(""))
        assertEquals(5, TextBlockCodec.utf8Size("hello"))
        assertEquals(8, TextBlockCodec.utf8Size("مرحبا ١"[0].toString().repeat(4)))
        for (s in listOf("é", "ع", "€", "😀", "a😀ع€é\n", "👨‍👩‍👧")) {
            assertEquals(s, bytes(s), TextBlockCodec.utf8Size(s))
        }
    }

    @Test
    fun `joining the pieces gives the text back exactly`() {
        val samples = listOf(
            "short",
            "a".repeat(TextBlockCodec.MAX_CHUNK_BYTES),
            "a".repeat(TextBlockCodec.MAX_CHUNK_BYTES + 1),
            "مرحباً بالعالم ‏(Beckon) ١٢٣ ".repeat(200),
            "😀👨‍👩‍👧".repeat(150),
            "line one\r\nسطر ثانٍ\n\tindented\n".repeat(60),
        )
        for (text in samples) {
            val pieces = TextBlockCodec.split(text)
            assertNotNull(pieces)
            assertEquals(text, pieces!!.joinToString(""))
            assertTrue(pieces.size <= TextBlockCodec.MAX_CHUNKS)
            for (piece in pieces) {
                assertTrue(piece.isNotEmpty())
                assertTrue(bytes(piece) <= TextBlockCodec.MAX_CHUNK_BYTES)
                // No piece starts or ends inside an emoji's two halves.
                assertFalse(Character.isLowSurrogate(piece.first()))
                assertFalse(Character.isHighSurrogate(piece.last()))
            }
        }
    }

    @Test
    fun `an empty text has no pieces and a text over the limit is refused`() {
        assertEquals(emptyList<String>(), TextBlockCodec.split(""))
        assertNotNull(TextBlockCodec.split("a".repeat(TextBlockCodec.MAX_TEXT_BYTES)))
        assertNull(TextBlockCodec.split("a".repeat(TextBlockCodec.MAX_TEXT_BYTES + 1)))
        assertFalse(TextBlockCodec.fits("ع".repeat(TextBlockCodec.MAX_TEXT_BYTES)))
    }

    @Test
    fun `the pieces match what the PC would cut`() {
        // The Rust side cuts at 900 bytes, backing up to a character boundary. 450 two-byte letters fill a piece exactly.
        val pieces = TextBlockCodec.split("ع".repeat(451))!!
        assertEquals(listOf(450, 1), pieces.map { it.length })
        // A four-byte emoji that would cross the line moves whole into the next piece.
        val shifted = TextBlockCodec.split("a".repeat(898) + "😀")!!
        assertEquals(listOf(898, 2), shifted.map { it.length })
    }
}

package com.omsingh.beckon.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextNormalizerTest {

    @Test
    fun `the forms of alef, ya and ta marbuta compare equal`() {
        assertEquals(TextNormalizer.normalize("أحمد"), TextNormalizer.normalize("احمد"))
        assertEquals(TextNormalizer.normalize("إسلام آمن"), TextNormalizer.normalize("اسلام امن"))
        assertEquals(TextNormalizer.normalize("مدرسة"), TextNormalizer.normalize("مدرسه"))
        assertEquals(TextNormalizer.normalize("على"), TextNormalizer.normalize("علي"))
    }

    @Test
    fun `vowel marks and the elongation stroke are ignored`() {
        assertEquals("الصلاه", TextNormalizer.normalize("اَلصَّلاةُ"))
        assertEquals("محمد", TextNormalizer.normalize("مـــحـــمـــد"))
    }

    @Test
    fun `arabic-indic and persian digits become ascii digits`() {
        assertEquals("room 123 and 456", TextNormalizer.normalize("Room ١٢٣ and ۴۵۶"))
    }

    @Test
    fun `direction marks and joiners are dropped but the words stay`() {
        assertEquals("beckon مرحبا", TextNormalizer.normalize("‏Beckon‎ ‫مرحبا‬"))
    }

    @Test
    fun `white space is folded and the ends are trimmed`() {
        assertEquals("a b c", TextNormalizer.normalize("  a \t b\n\nc  "))
        assertTrue(TextNormalizer.tokens("   ").isEmpty())
        assertEquals(listOf("hello", "world"), TextNormalizer.tokens("HELLO   World"))
    }
}

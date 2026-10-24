package com.omsingh.beckon.core.input

import com.omsingh.beckon.core.windows.ActiveWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingModeTest {

    private fun window(process: String) = ActiveWindow(process, "title")

    @Test
    fun `known programs pick their mode whatever the case or extension`() {
        assertEquals(TypingMode.CODE, AppModes.modeFor(window("Code.exe")))
        assertEquals(TypingMode.CODE, AppModes.modeFor(window("WindowsTerminal.exe")))
        assertEquals(TypingMode.CHAT, AppModes.modeFor(window("WhatsApp.exe")))
        assertEquals(TypingMode.CHAT, AppModes.modeFor(window("Telegram.exe")))
        assertEquals(TypingMode.DOCUMENT, AppModes.modeFor(window("WINWORD.EXE")))
        assertEquals(TypingMode.SEARCH, AppModes.modeFor(window("SearchHost.exe")))
    }

    @Test
    fun `a browser or an unknown program does not guess`() {
        assertNull(AppModes.modeFor(window("chrome.exe")))
        assertNull(AppModes.modeFor(window("firefox.exe")))
        assertNull(AppModes.modeFor(window("somethingnew.exe")))
        assertNull(AppModes.modeFor(window("")))
        assertNull(AppModes.modeFor(null))
    }

    @Test
    fun `auto follows the program, manual ignores it`() {
        assertEquals(TypingMode.CODE, AppModes.effective(ModeChoice.Auto, window("Code.exe")))
        assertEquals(TypingMode.GENERAL, AppModes.effective(ModeChoice.Auto, window("chrome.exe")))
        assertEquals(TypingMode.GENERAL, AppModes.effective(ModeChoice.Auto, null))
        assertEquals(TypingMode.CHAT, AppModes.effective(ModeChoice.Manual(TypingMode.CHAT), window("Code.exe")))
    }

    @Test
    fun `the choice survives being saved, and anything unknown means auto`() {
        for (choice in listOf(ModeChoice.Auto) + TypingMode.entries.map { ModeChoice.Manual(it) }) {
            assertEquals(choice, ModeChoice.fromSaved(ModeChoice.toSaved(choice)))
        }
        assertEquals(ModeChoice.Auto, ModeChoice.fromSaved(null))
        assertEquals(ModeChoice.Auto, ModeChoice.fromSaved("FROM_A_NEWER_VERSION"))
    }

    @Test
    fun `the modes really differ in what they ask of the keyboard`() {
        val profiles = TypingMode.entries.map { it.profile }
        assertEquals("every mode is a different setup", profiles.size, profiles.toSet().size)
        assertTrue(!TypingMode.CODE.profile.autoCorrect && !TypingMode.CODE.profile.sentenceCapitals)
        assertEquals(EnterAction.PRESS_ENTER, TypingMode.CHAT.profile.enter)
        assertEquals(EnterAction.NEWLINE, TypingMode.DOCUMENT.profile.enter)
    }
}

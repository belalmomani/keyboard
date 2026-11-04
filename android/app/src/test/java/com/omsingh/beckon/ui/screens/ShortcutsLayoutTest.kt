package com.omsingh.beckon.ui.screens

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.omsingh.beckon.R
import com.omsingh.beckon.core.input.InputEvent
import com.omsingh.beckon.core.input.KeyboardSession
import com.omsingh.beckon.ui.Fixtures
import com.omsingh.beckon.ui.screens.remote.RemoteActions
import com.omsingh.beckon.ui.screens.remote.RemoteScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The shortcuts under Keys are the same list whether the phone's own keyboard is open or not: cards, three to a row,
 * that scroll up and down. (They used to turn into one row to slide sideways when there was not much room.)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi", application = PlainApplication::class)
class ShortcutsLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private val actions = object : RemoteActions {
        override fun send(event: InputEvent) {}
        override fun disconnect() {}
        override fun pasteFromPhone() {}
        override fun copyFromPc() {}
        override fun copyPcClipboardToPhone() {}
        override fun dismissPcClipboard() {}
        override fun mediaVisible(visible: Boolean) {}
    }

    @Before
    fun setUp() = noAnimations()

    private fun openKeys() {
        compose.show { RemoteScreen(Fixtures.remote(), actions, KeyboardSession { }, onGoToDevices = {}) }
        compose.onNodeWithText(string(R.string.remote_mode_keys)).performClick()
        compose.settle()
    }

    /** Where a shortcut's label is, whether or not it is on the screen (not cut down to what shows, as bounds are). */
    private fun top(label: Int) = compose.onNodeWithText(string(label)).fetchSemanticsNode().positionInRoot.y
    private fun left(label: Int) = compose.onNodeWithText(string(label)).fetchSemanticsNode().positionInRoot.x

    private fun assertCardsInRowsOfThree() {
        val labels = listOf(R.string.shortcut_copy, R.string.shortcut_paste, R.string.shortcut_cut, R.string.shortcut_undo)
        val tops = labels.map { top(it).toInt() }
        // Cards sit three to a row: some share a row, and there is more than one row.
        assertTrue("some cards share a row", tops.toSet().size < tops.size)
        assertTrue("there is more than one row", tops.toSet().size >= 2)
        val first = tops.min()
        val sameRow = labels.filter { top(it).toInt() == first }
        assertTrue("cards in a row are side by side", sameRow.map { left(it).toInt() }.toSet().size == sameRow.size)
        assertTrue("the list scrolls up and down", compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `with room to spare the shortcuts are cards in rows that scroll`() {
        openKeys()
        assertCardsInRowsOfThree()
    }

    // The room left above an open phone keyboard (or on a short screen).
    @Test
    @Config(qualifiers = "w411dp-h480dp-420dpi")
    fun `with little room the shortcuts are still cards in rows that scroll, not one row to slide along`() {
        openKeys()
        assertCardsInRowsOfThree()
    }
}

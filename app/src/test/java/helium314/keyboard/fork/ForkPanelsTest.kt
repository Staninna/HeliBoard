// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork

import android.view.KeyEvent
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.utils.ToolbarKey
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ForkPanelsTest {
    @Test fun pinAtPutsTheKeyAtTheDropPosition() {
        val pref = "UNDO:true|COPY:false|REDO:true|PASTE:false"
        assertEquals("UNDO:true|COPY:true|REDO:true|PASTE:false", ForkToolbar.pinAt(pref, ToolbarKey.COPY, 1))
        assertEquals("COPY:true|UNDO:true|REDO:true|PASTE:false", ForkToolbar.pinAt(pref, ToolbarKey.COPY, 0))
    }

    @Test fun pinAtPastTheEndAppendsAfterTheLastPinnedKey() {
        val pref = "UNDO:true|COPY:false|REDO:true|PASTE:false"
        assertEquals("UNDO:true|COPY:false|REDO:true|PASTE:true", ForkToolbar.pinAt(pref, ToolbarKey.PASTE, 5))
    }

    @Test fun pinAtMovesAnAlreadyPinnedKey() {
        assertEquals("REDO:true|UNDO:true", ForkToolbar.pinAt("UNDO:true|REDO:true", ToolbarKey.UNDO, 1))
    }

    @Test fun pinAtWithNothingPinnedPinsFirst() {
        assertEquals("COPY:true|UNDO:false", ForkToolbar.pinAt("UNDO:false|COPY:false", ToolbarKey.COPY, 3))
    }

    @Test fun draggingDownMakesTheKeyboardLower() {
        assertEquals(0.9f, ResizeView.scaleFor(1f, 1000, 100f))
        assertEquals(1.2f, ResizeView.scaleFor(1f, 1000, -200f))
        assertEquals(ResizeView.MIN_SCALE, ResizeView.scaleFor(1f, 1000, 900f))
        assertEquals(ResizeView.MAX_SCALE, ResizeView.scaleFor(1.4f, 1000, -500f))
    }

    @Test fun selectModeShiftsOnlyCursorMoves() {
        ForkKeys.onCodeInput(KeyCode.FORK_SELECT_MODE)
        try {
            assertEquals(KeyEvent.META_SHIFT_ON, ForkKeys.extraMetaState(KeyCode.ARROW_LEFT))
            assertEquals(KeyEvent.META_SHIFT_ON, ForkKeys.extraMetaState(KeyCode.MOVE_END_OF_LINE))
            assertEquals(0, ForkKeys.extraMetaState(KeyCode.CLIPBOARD_COPY))
        } finally {
            ForkKeys.onCodeInput(KeyCode.FORK_SELECT_MODE)
        }
        assertEquals(0, ForkKeys.extraMetaState(KeyCode.ARROW_LEFT))
    }

    @Test fun shiftIsHeldOnlyForShiftedCursorMoves() {
        assert(ForkKeys.needsShiftHeld(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_SHIFT_ON))
        assert(ForkKeys.needsShiftHeld(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_SHIFT_ON or KeyEvent.META_CTRL_ON))
        assert(!ForkKeys.needsShiftHeld(KeyEvent.KEYCODE_DPAD_LEFT, 0))
        assert(!ForkKeys.needsShiftHeld(KeyEvent.KEYCODE_Z, KeyEvent.META_SHIFT_ON or KeyEvent.META_CTRL_ON)) // redo
    }

    @Test fun kaomojiParseGroupsByCategory() {
        val groups = Kaomoji.parse("# header\n(^^)\tsmile\n(T_T)\tsadness\n(^_^)\tsmile\n\n")
        assertEquals(listOf("smile" to listOf("(^^)", "(^_^)"), "sadness" to listOf("(T_T)")), groups)
    }

    @Test fun bundledKaomojiHaveTheFiveMoods() {
        val groups = Kaomoji.parse(File("src/main/assets/${Kaomoji.ASSET}").readText())
        assertEquals(listOf("smile", "sweat", "surprise", "sadness", "displeasure"), groups.map { it.first })
        groups.forEach { (_, kaomoji) -> assert(kaomoji.isNotEmpty()) }
    }
}

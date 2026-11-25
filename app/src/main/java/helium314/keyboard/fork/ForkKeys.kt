// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork

import android.view.KeyEvent
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode

/**
 * Fork: key codes of the fork's panels. [onCodeInput] runs before upstream's handling and returns
 * true for the codes it consumed.
 */
object ForkKeys {
    /** Select mode of the text-editing panel: while on, cursor moves extend the selection. */
    var selectMode = false
        private set

    private val movementCodes = setOf(
        KeyCode.ARROW_LEFT, KeyCode.ARROW_RIGHT, KeyCode.ARROW_UP, KeyCode.ARROW_DOWN,
        KeyCode.WORD_LEFT, KeyCode.WORD_RIGHT, KeyCode.PAGE_UP, KeyCode.PAGE_DOWN,
        KeyCode.MOVE_START_OF_LINE, KeyCode.MOVE_END_OF_LINE, KeyCode.MOVE_START_OF_PAGE, KeyCode.MOVE_END_OF_PAGE,
    )

    @JvmStatic
    fun onCodeInput(code: Int): Boolean {
        if (code != KeyCode.FORK_REWRITE) helium314.keyboard.fork.ai.ForkAi.onKey() // typing on ends undo and proofread offers
        when (code) {
            KeyCode.FORK_REWRITE -> helium314.keyboard.fork.ai.ForkAi.openRewrite()
            KeyCode.FORK_RESIZE -> ForkPanels.showResize()
            KeyCode.FORK_SELECT_MODE -> setSelectMode(!selectMode)
            KeyCode.FORK_KAOMOJI_TAB -> ForkEmoji.showKaomoji(true)
            KeyCode.FORK_EMOJI_TAB -> ForkEmoji.showKaomoji(false)
            else -> return false
        }
        return true
    }

    /** Shift for cursor moves while select mode is on, so the move selects text. */
    @JvmStatic
    fun extraMetaState(code: Int): Int = if (selectMode && code in movementCodes) KeyEvent.META_SHIFT_ON else 0

    private val cursorMoveKeyEvents = setOf(
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
    )

    /** A shifted cursor move only selects text in a text view while a shift key is down. */
    @JvmStatic
    fun needsShiftHeld(keyEventCode: Int, metaState: Int) =
        metaState and KeyEvent.META_SHIFT_ON != 0 && keyEventCode in cursorMoveKeyEvents

    fun setSelectMode(on: Boolean) {
        if (selectMode == on) return
        selectMode = on
        KeyboardSwitcher.getInstance().mainKeyboardView?.updateLockState(KeyCode.FORK_SELECT_MODE, on)
    }
}

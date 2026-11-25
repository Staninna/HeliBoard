// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork

import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.keyboard.KeyboardSwitcher

/**
 * Fork: where the keys of the current keyboard start and end, and the gap between them. The fork's
 * panels take their edges and gaps from here, so they line up with the keys at every key gap and
 * side padding setting.
 */
data class KeyGeometry(val left: Int, val right: Int, val gap: Int) {
    companion object {
        /** Edges of the drawn keys, in pixels from the left and right side of the keyboard. */
        fun of(keyboard: Keyboard?): KeyGeometry? {
            val keyboard = keyboard ?: return null
            val keys = keyboard.sortedKeys.filter { !it.isSpacer && it.drawWidth > 0 }
            if (keys.isEmpty()) return null
            val left = keys.minOf { it.drawX }
            val right = keyboard.mOccupiedWidth - keys.maxOf { it.drawX + it.drawWidth }
            return KeyGeometry(left, right, keys.minOf { it.horizontalGap })
        }

        fun current() = of(KeyboardSwitcher.getInstance().keyboard)
    }
}

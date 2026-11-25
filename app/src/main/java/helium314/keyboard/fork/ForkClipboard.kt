// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork

import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.keyboard.MainKeyboardView
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.dpToPx

/** Fork: clipboard panel with a proper empty state, roomier clips, and its bottom row above the navigation bar. */
object ForkClipboard {
    /** Called each time the clipboard panel opens, after upstream set it up. */
    @JvmStatic
    fun onStart(panel: View, placeholder: TextView) {
        val res = panel.resources
        val colors = Settings.getValues().mColors
        placeholder.apply {
            setText(R.string.fork_clipboard_empty)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(colors.get(ColorType.KEY_HINT_TEXT))
            gravity = Gravity.CENTER
            compoundDrawablePadding = 12.dpToPx(res)
            val icon = ContextCompat.getDrawable(context, R.drawable.sym_keyboard_clipboard_rounded)?.mutate()
            icon?.setBounds(0, 0, 40.dpToPx(res), 40.dpToPx(res))
            icon?.setTint(colors.get(ColorType.KEY_HINT_TEXT))
            setCompoundDrawablesRelative(null, icon, null, null)
        }
        // the keyboard window reaches under the navigation bar (edge to edge); the panel's onMeasure adds this padding
        val navBar = ViewCompat.getRootWindowInsets(panel)?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        panel.updatePadding(bottom = navBar)
        alignToKeys(panel.findViewById(R.id.clipboard_list), panel.findViewById(R.id.bottom_row_keyboard))
    }

    /** Gap between clip cards, -1 for upstream's. Read when a card is created. */
    @JvmStatic
    var itemGap = -1
        private set

    /** Puts the outer card edges on the outer key edges of the bottom row, with the key gap between cards. */
    private fun alignToKeys(list: RecyclerView?, bottomRow: MainKeyboardView?) {
        val keys = KeyGeometry.of(bottomRow?.keyboard) ?: return
        list ?: return
        itemGap = keys.gap
        list.updatePadding(
            left = bottomRow!!.paddingLeft + keys.left - keys.gap / 2,
            right = bottomRow.paddingRight + keys.right - (keys.gap - keys.gap / 2),
        )
        // cards made before the gap was known
        for (i in 0 until list.childCount) list.getChildAt(i).let { child ->
            (child.layoutParams as RecyclerView.LayoutParams).apply {
                topMargin = keys.gap / 2; bottomMargin = keys.gap - keys.gap / 2
                marginStart = keys.gap / 2; marginEnd = keys.gap - keys.gap / 2
            }
            child.requestLayout()
        }
    }
}

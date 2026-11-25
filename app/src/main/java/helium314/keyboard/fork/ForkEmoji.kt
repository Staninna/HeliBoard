// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork

import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.MainKeyboardView
import helium314.keyboard.keyboard.emoji.EmojiSearchActivity
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.dpToPx
import java.lang.ref.WeakReference

/**
 * Fork: Gboard-style emoji panel. A search box sits above the emoji pages, and the bottom row has
 * tabs for emoji and kaomoji. The kaomoji page takes the place of the emoji pages.
 */
object ForkEmoji {
    private const val SEARCH_TAG = "fork_emoji_search"
    private const val KAOMOJI_TAG = "fork_kaomoji"
    private const val PILL_TAG = "fork_emoji_search_pill"
    private var palettes = WeakReference<LinearLayout>(null)

    /** Called each time the emoji panel opens, after upstream sized the emoji pages. */
    @JvmStatic
    fun onStart(palettes: LinearLayout, pager: View, editorInfo: EditorInfo?, listener: KeyboardActionListener) {
        this.palettes = WeakReference(palettes)
        val search = palettes.findViewWithTag(SEARCH_TAG) ?: searchBox(palettes, listener).also { palettes.addView(it, 0) }
        val kaomoji = palettes.findViewWithTag<View>(KAOMOJI_TAG) ?: KaomojiView(palettes.context) { listener.onTextInput(it) }
            .apply { tag = KAOMOJI_TAG }
            .also { palettes.addView(it, palettes.indexOfChild(pager) + 1) }

        // no search box inside the emoji search itself, the search field is right above
        search.isVisible = editorInfo == null || EmojiSearchActivity.decodePrivateImeOptions(editorInfo).height == 0
        alignToKeys(search, kaomoji, palettes.findViewById(R.id.bottom_row_keyboard))
        val pagerHeight = pager.layoutParams.height
        val searchHeight = if (search.isVisible) search.layoutParams.height else 0
        pager.layoutParams = pager.layoutParams.apply { height = pagerHeight - searchHeight }
        kaomoji.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, pagerHeight - searchHeight)
        showKaomoji(false)

        // when the keyboard window reaches under the navigation bar (edge to edge), keep the bottom row above it;
        // the panel's onMeasure adds this padding to its height
        val navBar = ViewCompat.getRootWindowInsets(palettes)?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        palettes.updatePadding(bottom = navBar)
    }

    fun showKaomoji(show: Boolean) {
        val palettes = palettes.get() ?: return
        val kaomoji = palettes.findViewWithTag<View>(KAOMOJI_TAG) ?: return
        palettes.findViewById<View>(R.id.emoji_pager)?.isVisible = !show
        kaomoji.isVisible = show
        // the emoji category tabs and page indicator mean nothing on the kaomoji page; hide them but keep their space
        palettes.findViewById<View>(R.id.emoji_category_page_id_view)?.visibility = if (show) View.INVISIBLE else View.VISIBLE
        KeyboardSwitcher.getInstance().emojiTabStrip?.let { strip ->
            if (show && strip.visibility == View.VISIBLE) strip.visibility = View.INVISIBLE
            else if (!show && strip.visibility == View.INVISIBLE) strip.visibility = View.VISIBLE
        }
        palettes.findViewById<MainKeyboardView>(R.id.bottom_row_keyboard)?.apply {
            updateLockState(KeyCode.FORK_KAOMOJI_TAB, show)
            updateLockState(KeyCode.FORK_EMOJI_TAB, !show)
        }
    }

    private fun searchBox(palettes: LinearLayout, listener: KeyboardActionListener): View {
        val context = palettes.context
        val res = context.resources
        val colors = Settings.getValues().mColors
        // same measures as the bar in the search screen: icon 16 dp in, text at 52 dp
        val pill = TextView(context).apply {
            tag = PILL_TAG
            setText(R.string.fork_emoji_search)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(colors.get(ColorType.EMOJI_SEARCH_TEXT))
            gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = 12.dpToPx(res)
            setPadding(16.dpToPx(res), 0, 16.dpToPx(res), 0)
            val icon = ContextCompat.getDrawable(context, R.drawable.sym_keyboard_search_rounded)?.mutate()
            icon?.setBounds(0, 0, 24.dpToPx(res), 24.dpToPx(res))
            icon?.let { colors.setColor(it, ColorType.EMOJI_SEARCH_TEXT) }
            setCompoundDrawablesRelative(icon, null, null, null)
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(colors.get(ColorType.EMOJI_SEARCH_BACKGROUND))
            }
            setOnClickListener {
                listener.onCodeInput(KeyCode.EMOJI_SEARCH, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
            }
        }
        return FrameLayout(context).apply {
            tag = SEARCH_TAG
            addView(pill, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, SEARCH_BAR_HEIGHT_DP.dpToPx(res), Gravity.CENTER_VERTICAL))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (SEARCH_BAR_HEIGHT_DP + 16).dpToPx(res))
        }
    }

    /** Puts the pill's sides and the kaomoji page's content on the outer edges of the bottom row's keys. */
    private fun alignToKeys(search: View, kaomoji: View, bottomRow: MainKeyboardView?) {
        val keys = KeyGeometry.of(bottomRow?.keyboard) ?: return
        val left = bottomRow!!.left + bottomRow.paddingLeft + keys.left
        val right = keys.right + bottomRow.paddingRight
        search.findViewWithTag<View>(PILL_TAG)?.let { pill ->
            pill.layoutParams = (pill.layoutParams as FrameLayout.LayoutParams).apply { leftMargin = left; rightMargin = right }
        }
        (kaomoji as? ViewGroup)?.getChildAt(0)?.updatePadding(left = left, right = right)
    }

    /** Height of the search pill, shared with the search screen. */
    const val SEARCH_BAR_HEIGHT_DP = 40
}

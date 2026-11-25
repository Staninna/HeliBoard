// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork.online

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import helium314.keyboard.fork.ForkPanels
import helium314.keyboard.fork.PanelKit.dp
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Settings
import java.lang.ref.WeakReference

/**
 * Fork: typing a GIF search with the keyboard itself. While it is on, a search bar covers the
 * suggestion strip and the keys type into it instead of the app. Enter or the search button shows
 * the results; the back arrow goes back to the GIFs.
 */
object GifSearch {
    private val query = StringBuilder()
    private var bar = WeakReference<View>(null)
    private var text = WeakReference<TextView>(null)
    private var submit = WeakReference<View>(null)
    private var caret = WeakReference<View>(null)
    private var blink: ValueAnimator? = null
    private var previous = ""

    val isActive get() = bar.get()?.isAttachedToWindow == true

    /** Opens the search bar over the suggestion strip, starting with [initial]. */
    fun start(context: Context, initial: String) {
        ForkPanels.close()
        val wrapper = KeyboardSwitcher.getInstance().wrapperView ?: return
        val container = (wrapper.parent as? View)?.findViewById<FrameLayout>(R.id.strip_container)
        if (container == null || !container.isShown || container.height == 0) {
            ForkPanels.showGifs(initial) // no strip to type into: stay with the GIFs
            return
        }
        previous = initial
        query.clear().append(initial)
        val view = searchBar(context)
        container.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        bar = WeakReference(view)
        update()
        KeyboardSwitcher.getInstance().updateShiftState(0, null) // lower case: searches don't start sentences
    }

    /** Ends the search without showing results. */
    @JvmStatic
    fun end() {
        blink?.cancel()
        blink = null
        val view = bar.get() ?: return
        bar = WeakReference(null)
        (view.parent as? ViewGroup)?.removeView(view)
    }

    /** A key press while searching. Returns true when the search took it. */
    @JvmStatic
    fun onCode(code: Int): Boolean {
        if (!isActive) return false
        when {
            code == KeyCode.DELETE -> if (query.isNotEmpty()) query.setLength(query.length - Character.charCount(query.codePointBefore(query.length)))
            code == Constants.CODE_ENTER || code == KeyCode.SHIFT_ENTER -> { show(); return true }
            code >= Constants.CODE_SPACE && Character.isValidCodePoint(code) -> {
                if (query.length < MAX_LENGTH) query.appendCodePoint(code)
            }
            else -> return false // shift, symbols and the like work as usual
        }
        update()
        KeyboardSwitcher.getInstance().updateShiftState(0, null)
        return true
    }

    /** Text from a key that types more than one character. Returns true when the search took it. */
    @JvmStatic
    fun onText(value: String?): Boolean {
        if (!isActive) return false
        if (value != null) query.append(value.take(MAX_LENGTH - query.length))
        update()
        return true
    }

    private fun show() {
        val q = query.toString().trim()
        end()
        ForkPanels.showGifs(q)
    }

    private fun back() {
        end()
        ForkPanels.showGifs(previous)
    }

    private fun update() {
        val t = text.get() ?: return
        val colors = Settings.getValues().mColors
        val textColor = colors.get(ColorType.EMOJI_SEARCH_TEXT)
        if (query.isEmpty()) {
            t.setText(R.string.fork_gif_search_hint)
            t.setTextColor(ColorUtils.blendARGB(colors.get(ColorType.EMOJI_SEARCH_BACKGROUND), textColor, 0.65f))
        } else {
            t.text = query
            t.setTextColor(textColor)
        }
        submit.get()?.isVisible = query.isNotBlank()
        // the caret goes before the placeholder and after typed text, like in a text field
        val c = caret.get() ?: return
        val pill = c.parent as? ViewGroup ?: return
        val empty = query.isEmpty()
        if (pill.indexOfChild(c) != pill.indexOfChild(t) + (if (empty) -1 else 1)) {
            pill.removeView(c)
            pill.addView(c, pill.indexOfChild(t) + if (empty) 0 else 1)
        }
        // text starts 8 dp after the back arrow either way; an empty field's caret sits just in front of it
        val gap = 8.dp(t.context)
        (c.layoutParams as LinearLayout.LayoutParams).marginStart = if (empty) gap - c.layoutParams.width else 1.dp(t.context)
        (t.layoutParams as LinearLayout.LayoutParams).marginStart = if (empty) 0 else gap
        pill.requestLayout()
    }

    /** Same pill as the emoji search: back arrow, the search text with a caret, and a search button. */
    private fun searchBar(context: Context): View {
        val colors = Settings.getValues().mColors
        val background = colors.get(ColorType.EMOJI_SEARCH_BACKGROUND)
        val textColor = colors.get(ColorType.EMOJI_SEARCH_TEXT)
        val tint = ColorStateList.valueOf(textColor)

        val pill = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            this.background = GradientDrawable().apply { cornerRadius = 999f; setColor(background) }
        }
        pill.addView(ImageButton(context).apply {
            setImageResource(R.drawable.ic_arrow_back)
            imageTintList = tint
            this.background = null
            contentDescription = context.getString(R.string.spoken_description_action_previous)
            setOnClickListener { back() }
        }, LinearLayout.LayoutParams(40.dp(context), 40.dp(context)).apply { marginStart = 4.dp(context) })
        val label = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START // the end of what you type stays in view
        }
        pill.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = 8.dp(context); weight = 0f })
        val caret = View(context).apply { setBackgroundColor(colors.get(ColorType.ACTION_KEY_BACKGROUND)) }
        pill.addView(caret, LinearLayout.LayoutParams(2.dp(context), 22.dp(context)).apply { marginStart = 1.dp(context) })
        pill.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        val search = ImageButton(context).apply {
            setImageResource(R.drawable.sym_keyboard_search_rounded)
            imageTintList = tint
            this.background = null
            contentDescription = context.getString(R.string.fork_gif_search_hint)
            setOnClickListener { show() }
        }
        pill.addView(search, LinearLayout.LayoutParams(40.dp(context), 40.dp(context)).apply { marginEnd = 4.dp(context) })
        text = WeakReference(label)
        submit = WeakReference(search)
        this.caret = WeakReference(caret)
        blink = ObjectAnimator.ofFloat(caret, View.ALPHA, 1f, 1f, 0f, 0f).apply {
            duration = 1_000
            repeatCount = ValueAnimator.INFINITE
            start()
        }

        val margin = 4.dp(context)
        return FrameLayout(context).apply {
            setBackgroundColor(colors.get(ColorType.MAIN_BACKGROUND))
            isClickable = true
            addView(pill, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                .apply { setMargins(margin, margin, margin, margin) })
        }
    }

    private const val MAX_LENGTH = 50
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork.ai

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import helium314.keyboard.fork.ForkColors
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.dpToPx
import java.lang.ref.WeakReference

/**
 * Fork: one chip at the start of the suggestion bar, Gboard style. It offers a proofread after a
 * pause in typing, or to undo the last rewrite. The suggestions move right while it shows.
 */
object AiChip {
    enum class Kind { PROOFREAD, UNDO }

    private var chip = WeakReference<TextView>(null)
    private var kind: Kind? = null

    val isShowing get() = chip.get()?.visibility == View.VISIBLE

    /** Called when the suggestion bar is created; the chip goes right after the toolbar key. */
    @JvmStatic
    fun attach(wrapper: LinearLayout, afterView: View) {
        val res = wrapper.resources
        val view = TextView(wrapper.context).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER_VERTICAL
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            setPadding(12.dpToPx(res), 0, 14.dpToPx(res), 0)
            compoundDrawablePadding = 6.dpToPx(res)
            setOnClickListener { kind?.let(ForkAi::onChipClicked) }
        }
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 32.dpToPx(res)).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginStart = 4.dpToPx(res)
            marginEnd = 4.dpToPx(res)
        }
        wrapper.addView(view, wrapper.indexOfChild(afterView) + 1, params)
        chip = WeakReference(view)
        kind = null
    }

    fun show(kind: Kind) {
        val view = chip.get() ?: return
        this.kind = kind
        val colors = Settings.getValues().mColors
        val background = colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND)
        val text = ForkColors.readableOn(background, colors.get(ColorType.KEY_TEXT))
        view.background = GradientDrawable().apply { cornerRadius = 999f; setColor(background) }
        view.setTextColor(text)
        view.setText(if (kind == Kind.PROOFREAD) R.string.fork_ai_proofread else R.string.fork_ai_undo)
        val icon = ContextCompat.getDrawable(view.context, if (kind == Kind.PROOFREAD) R.drawable.ic_fork_rewrite else R.drawable.ic_fork_undo)?.mutate()
        val size = 18.dpToPx(view.resources)
        icon?.setBounds(0, 0, size, size)
        icon?.setTint(text)
        view.setCompoundDrawablesRelative(icon, null, null, null)
        view.visibility = View.VISIBLE
    }

    fun hide() {
        kind = null
        chip.get()?.visibility = View.GONE
    }
}

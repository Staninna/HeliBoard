// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.dpToPx

/**
 * Fork: the pieces the panels over the keys are built from (rewrite, translate, GIFs), so they all
 * look alike: 16 dp margins, M3 pill buttons and chips, a rounded card for the answer, all in the
 * keyboard theme's colors with readable text on every background.
 */
object PanelKit {
    const val MARGIN_DP = 16
    private val colors get() = Settings.getValues().mColors

    val textColor get() = colors.get(ColorType.KEY_TEXT)
    val hintColor get() = colors.get(ColorType.KEY_HINT_TEXT)
    val accent get() = colors.get(ColorType.ACTION_KEY_BACKGROUND)

    /** Halfway between the panel background and the function keys: a card that stands out without shouting. */
    val cardColor get() = ColorUtils.blendARGB(colors.get(ColorType.MAIN_BACKGROUND), colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND), 0.6f)

    fun label(context: Context, sizeSp: Float, color: Int, medium: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** M3 button, 48 dp high: filled in the accent color, or tonal. */
    fun pill(context: Context, filled: Boolean, @DrawableRes icon: Int = 0) = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val background = colors.get(if (filled) ColorType.ACTION_KEY_BACKGROUND else ColorType.FUNCTIONAL_KEY_BACKGROUND)
        val text = ForkColors.readableOn(background, textColor, colors.get(ColorType.MAIN_BACKGROUND))
        setTextColor(text)
        setPadding((if (icon != 0) 16 else 24).dp(context), 0, 24.dp(context), 0)
        this.background = GradientDrawable().apply { cornerRadius = 999f; setColor(background) }
        minWidth = 88.dp(context)
        if (icon != 0) setIcon(this, icon, text, 18)
    }

    /** Filter chip: the selected one in the accent color. */
    fun chip(context: Context, text: String, selected: Boolean, onClick: () -> Unit) = TextView(context).apply {
        val background = colors.get(if (selected) ColorType.ACTION_KEY_BACKGROUND else ColorType.FUNCTIONAL_KEY_BACKGROUND)
        this.text = text
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(ForkColors.readableOn(background, textColor, colors.get(ColorType.MAIN_BACKGROUND)))
        setPadding(16.dp(context), 0, 16.dp(context), 0)
        this.background = GradientDrawable().apply { cornerRadius = 999f; setColor(background) }
        isSelected = selected
        contentDescription = text
        setOnClickListener { onClick() }
    }

    /** A horizontally scrolling row for [chip]s: the first chip starts on the margin, and the row scrolls to the edge. */
    fun chipRow(context: Context, row: LinearLayout) = android.widget.HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        setPadding(MARGIN_DP.dp(context), 0, MARGIN_DP.dp(context), 0)
        row.gravity = Gravity.CENTER_VERTICAL
        addView(row)
    }

    fun chipParams(context: Context) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 36.dp(context))
        .apply { marginEnd = 8.dp(context) }

    /** Shows a button as unavailable without taking it out of the layout. */
    fun setReady(view: View, ready: Boolean) {
        view.isEnabled = ready
        view.alpha = if (ready) 1f else 0.38f
    }

    /** Keeps [panel]'s content above the navigation bar when the keyboard reaches under it. */
    fun keepAboveNavBar(panel: View) {
        val navBar = ViewCompat.getRootWindowInsets(panel)?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        panel.updatePadding(bottom = navBar)
    }

    fun setIcon(view: TextView, @DrawableRes icon: Int, color: Int, sizeDp: Int) {
        val drawable = ContextCompat.getDrawable(view.context, icon)?.mutate() ?: return
        val size = sizeDp.dp(view.context)
        drawable.setBounds(0, 0, size, size)
        drawable.setTint(color)
        view.setCompoundDrawablesRelative(drawable, null, null, null)
        view.compoundDrawablePadding = 8.dp(view.context)
    }

    fun Int.dp(context: Context) = dpToPx(context.resources)
}

/**
 * Fork: the rounded card in the middle of a panel. It shows one thing at a time: a spinner with
 * what is happening, a problem with the one button that fixes it, or the answer.
 */
class PanelCard(context: Context) : FrameLayout(context) {
    private val progress = LinearLayout(context)
    private val progressText = PanelKit.label(context, 15f, PanelKit.hintColor)
    private val message = LinearLayout(context)
    private val messageIcon = ImageView(context)
    private val messageText = PanelKit.label(context, 15f, PanelKit.textColor)
    private val messageAction = PanelKit.pill(context, filled = false)
    private val answer = ScrollView(context)
    private val caption = PanelKit.label(context, 13f, PanelKit.hintColor, medium = true)
    val text = PanelKit.label(context, 17f, PanelKit.textColor).apply { setLineSpacing(0f, 1.2f) }

    init {
        with(PanelKit) {
            background = GradientDrawable().apply { cornerRadius = 24.dp(context).toFloat(); setColor(cardColor) }
            clipToOutline = true

            progress.orientation = LinearLayout.VERTICAL
            progress.gravity = Gravity.CENTER
            progress.addView(ProgressBar(context).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(accent)
            }, LinearLayout.LayoutParams(36.dp(context), 36.dp(context)))
            progressText.gravity = Gravity.CENTER
            progress.addView(progressText, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 12.dp(context) })
            addView(progress, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

            message.orientation = LinearLayout.VERTICAL
            message.gravity = Gravity.CENTER
            message.setPadding(24.dp(context), 0, 24.dp(context), 0)
            messageIcon.setImageResource(R.drawable.ic_fork_error)
            messageIcon.imageTintList = ColorStateList.valueOf(hintColor)
            message.addView(messageIcon, LinearLayout.LayoutParams(28.dp(context), 28.dp(context)))
            messageText.gravity = Gravity.CENTER
            message.addView(messageText, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 8.dp(context) })
            message.addView(messageAction, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, 40.dp(context)).apply { topMargin = 16.dp(context) })
            addView(message, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            column.setPadding(20.dp(context), 16.dp(context), 20.dp(context), 16.dp(context))
            column.addView(caption, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = 6.dp(context) })
            column.addView(text)
            answer.isVerticalScrollBarEnabled = true
            answer.addView(column)
            addView(answer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        showProgress(0)
    }

    fun showProgress(@StringRes status: Int) {
        if (status != 0) progressText.setText(status)
        progressText.isVisible = status != 0
        show(progress)
    }

    /** A problem, and optionally the one button that fixes it. Without [icon], a plain hint. */
    fun showMessage(@StringRes status: Int, @StringRes action: Int = 0, icon: Boolean = true, onAction: (() -> Unit)? = null) {
        messageIcon.isVisible = icon
        messageText.setText(status)
        messageAction.isVisible = action != 0 && onAction != null
        if (action != 0) messageAction.setText(action)
        messageAction.setOnClickListener { onAction?.invoke() }
        show(message)
    }

    /** The answer, under a small [captionText] that says what it is. */
    fun showText(captionText: CharSequence?, value: CharSequence) {
        caption.text = captionText
        caption.isVisible = !captionText.isNullOrEmpty()
        text.text = value
        show(answer)
    }

    private fun show(view: View) {
        for (v in listOf(progress, message, answer)) v.isVisible = v == view
    }
}

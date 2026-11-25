// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.updatePadding
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.dpToPx
import kotlin.math.roundToInt

/**
 * Fork: resize mode, laid out like a Material 3 Expressive sheet. Drag the handle at the top down
 * to make the keyboard lower or up to make it taller, or step with − and +. Each change saves the
 * height for the current orientation, the keyboard reloads at that height and this view comes back
 * until Done.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ResizeView(context: Context) : FrameLayout(context) {
    private val theme = Settings.getValues().mColors
    private val startScale = Settings.getValues().mKeyboardHeightScale
    private var scale = startScale
    private var downY = 0f
    private val value = TextView(context)
    private val handle = FrameLayout(context)

    init {
        setBackgroundColor(ColorUtils.setAlphaComponent(theme.get(ColorType.MAIN_BACKGROUND), 0xF2))
        isClickable = true

        // M3 drag handle: a 32 × 4 dp pill in a full-width touch row
        handle.addView(View(context).apply { background = pill(theme.get(ColorType.KEY_HINT_TEXT)) },
            LayoutParams(32.dp, 4.dp, Gravity.CENTER))
        addView(handle, LayoutParams(LayoutParams.MATCH_PARENT, 36.dp, Gravity.TOP))

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }

        // one row of controls, so the sheet fits even a keyboard at half height
        val controls = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        controls.addView(roundButton(resetIcon(), R.string.fork_resize_reset) { ForkPanels.applyHeightScale(null) })
        controls.addView(roundButton("−", R.string.fork_resize_smaller) { step(-STEP) })
        value.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(theme.get(ColorType.KEY_TEXT))
            gravity = Gravity.CENTER
            minWidth = 104.dp
        }
        controls.addView(value)
        controls.addView(roundButton("+", R.string.fork_resize_larger) { step(STEP) })
        controls.addView(pillButton(R.string.fork_resize_done) { ForkPanels.close() })
        column.addView(controls)

        column.addView(TextView(context).apply {
            setText(R.string.fork_resize_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(theme.get(ColorType.KEY_HINT_TEXT))
            gravity = Gravity.CENTER
            setPadding(0, 8.dp, 0, 0)
        })

        addView(column, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            .apply { topMargin = 18.dp })
        // the keyboard area reaches under the navigation bar, center the controls above it
        doOnAttach {
            val navBar = ViewCompat.getRootWindowInsets(it)?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
            updatePadding(bottom = navBar)
        }
        updateValue()
    }

    // the whole sheet is the drag area, the handle follows the finger to show where the keyboard top goes
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> downY = event.rawY
            MotionEvent.ACTION_MOVE -> {
                val dy = event.rawY - downY
                scale = scaleFor(startScale, height, dy)
                handle.translationY = dy.coerceIn(0f, (height - handle.height).toFloat())
                updateValue()
            }
            MotionEvent.ACTION_UP -> if (scale != startScale) ForkPanels.applyHeightScale(scale) else handle.translationY = 0f
            MotionEvent.ACTION_CANCEL -> { scale = startScale; handle.translationY = 0f; updateValue() }
        }
        return true
    }

    private fun step(delta: Float) {
        val next = ((startScale + delta) * 100).roundToInt() / 100f
        ForkPanels.applyHeightScale(next.coerceIn(MIN_SCALE, MAX_SCALE))
    }

    private fun updateValue() {
        value.text = resources.getString(R.string.fork_resize_value, (scale * 100).roundToInt())
    }

    private fun pill(color: Int) = GradientDrawable().apply { cornerRadius = 999f; setColor(color) }

    private fun resetIcon() = ContextCompat.getDrawable(context, R.drawable.ic_fork_reset)?.mutate()
        ?.also { theme.setColor(it, ColorType.KEY_TEXT) }

    /** Round tonal button, 48 dp, with a [symbol]. */
    private fun roundButton(symbol: String, description: Int, onClick: () -> Unit) = TextView(context).apply {
        text = symbol
        contentDescription = context.getString(description) // screen readers say "Smaller", not "minus"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        setTextColor(theme.get(ColorType.KEY_TEXT))
        gravity = Gravity.CENTER
        roundTonal(onClick)
    }

    /** Round tonal button, 48 dp, with an [icon] and its [description]. */
    private fun roundButton(icon: Drawable?, description: Int, onClick: () -> Unit) = ImageView(context).apply {
        setImageDrawable(icon)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = context.getString(description)
        roundTonal(onClick)
    }

    private fun View.roundTonal(onClick: () -> Unit) {
        background = pill(theme.get(ColorType.FUNCTIONAL_KEY_BACKGROUND))
        layoutParams = LinearLayout.LayoutParams(48.dp, 48.dp).apply { setMargins(4.dp, 0, 4.dp, 0) }
        setOnClickListener { onClick() }
    }

    /** Filled full-pill button in the accent color, 48 dp high. */
    private fun pillButton(text: Int, onClick: () -> Unit) = TextView(context).apply {
        setText(text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val accent = theme.get(ColorType.ACTION_KEY_BACKGROUND)
        // the accent is light in dark themes and dark in some light ones: take the first color that reads on it
        setTextColor(ForkColors.readableOn(accent, theme.get(ColorType.MAIN_BACKGROUND), theme.get(ColorType.KEY_TEXT)))
        gravity = Gravity.CENTER
        setPadding(20.dp, 0, 20.dp, 0)
        background = pill(accent)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 48.dp)
            .apply { setMargins(4.dp, 0, 4.dp, 0) }
        setOnClickListener { onClick() }
    }

    private val Int.dp get() = dpToPx(resources)

    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 1.5f
        private const val STEP = 0.05f

        /** New height scale after dragging the top [dy] pixels down on a keyboard [height] pixels high at [startScale]. */
        fun scaleFor(startScale: Float, height: Int, dy: Float): Float {
            if (height <= 0) return startScale
            val scaled = startScale * (height - dy) / height
            return ((scaled * 100).roundToInt() / 100f).coerceIn(MIN_SCALE, MAX_SCALE)
        }
    }
}

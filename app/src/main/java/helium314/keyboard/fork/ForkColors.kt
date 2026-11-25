// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils

/** Fork: picks text and icon colors that stay readable on whatever background a theme gives. */
object ForkColors {
    /** WCAG AA for normal text */
    const val MIN_TEXT_CONTRAST = 4.5

    fun contrast(@ColorInt a: Int, @ColorInt b: Int): Double =
        ColorUtils.calculateContrast(opaque(a), opaque(b))

    /**
     * The first of [candidates] with at least [min] contrast on [background]. If none reads well enough,
     * black or white, whichever contrasts more.
     */
    @ColorInt
    fun readableOn(@ColorInt background: Int, vararg candidates: Int, min: Double = MIN_TEXT_CONTRAST): Int {
        candidates.firstOrNull { contrast(it, background) >= min }?.let { return opaque(it) }
        return if (contrast(Color.BLACK, background) >= contrast(Color.WHITE, background)) Color.BLACK else Color.WHITE
    }

    private fun opaque(@ColorInt color: Int) = ColorUtils.setAlphaComponent(color, 255)
}

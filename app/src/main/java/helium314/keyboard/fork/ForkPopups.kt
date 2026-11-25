// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork

import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.core.graphics.ColorUtils
import helium314.keyboard.keyboard.Key
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.utils.dpToPx

/** Fork: long-press popups and key previews as raised Material 3 surfaces with readable text. */
object ForkPopups {
    private const val ELEVATION_DP = 6

    /** Gives the popup panel inside [container] a shadow, which needs room outside the panel's bounds. */
    @JvmStatic
    fun elevate(container: View) {
        val elevation = ELEVATION_DP.dpToPx(container.resources).toFloat()
        (container as? ViewGroup)?.apply {
            clipChildren = false
            clipToPadding = false
            for (i in 0 until childCount) getChildAt(i).raise(elevation)
        } ?: container.raise(elevation)
    }

    private fun View.raise(elevation: Float) {
        if (background == null) return
        outlineProvider = ViewOutlineProvider.BACKGROUND
        // the keys reach the panel's edge; clipping keeps the panel's round corners instead of the keys' small ones
        clipToOutline = true
        this.elevation = elevation
    }

    /**
     * Text and icon color of a key in a popup. The selected key has the accent behind it, which is light in
     * dark themes, so the usual light text would disappear on it.
     */
    @JvmStatic
    fun popupKeyColor(key: Key, colors: Colors, type: ColorType): Int {
        val color = colors.get(type)
        if (!key.isPressed) return color
        return ForkColors.readableOn(colors.get(ColorType.ACTION_KEY_BACKGROUND), color, colors.get(ColorType.MAIN_BACKGROUND))
    }

    /** Key preview a step lighter (or darker) than the keys, so it stands out from the key below it. */
    @JvmStatic
    fun previewColor(colors: Colors): Int =
        ColorUtils.blendARGB(colors.get(ColorType.KEY_BACKGROUND), colors.get(ColorType.KEY_TEXT), 0.14f)

    @JvmStatic
    fun elevatePreview(preview: View) = preview.raise(ELEVATION_DP.dpToPx(preview.resources).toFloat())
}

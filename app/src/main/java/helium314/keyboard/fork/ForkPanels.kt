// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork

import android.content.ClipData
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.KeyboardWrapperView
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.createPrefKeyForBooleanSettings
import helium314.keyboard.latin.settings.findIndexOfDefaultSetting
import helium314.keyboard.latin.suggestions.SuggestionStripView
import helium314.keyboard.latin.utils.FoldableUtils
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.onClickToolbarKey
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.removePinnedKey

/** A tool being dragged: from the grid to pin it, or from the pinned row to move or unpin it. */
data class ToolDrag(val key: ToolbarKey, val fromPinned: Boolean)

/**
 * Fork: Gboard-style panels that cover the keyboard area. One overlay at a time sits on top of the
 * keyboard in [KeyboardWrapperView]: the tools grid or the resize handle. Any keyboard switch closes it.
 */
object ForkPanels {
    private var overlay: View? = null
    private var strip: SuggestionStripView? = null
    private var toolbarWasVisible = false
    private var reopenResize = false

    val isToolsGridOpen get() = overlay is ToolsGridView

    /** The toolbar's grid button. Returns false when the grid can't open, so upstream's toggle runs instead. */
    fun toggleToolsGrid(strip: SuggestionStripView): Boolean {
        if (isToolsGridOpen) {
            close()
            return true
        }
        val grid = ToolsGridView(strip.context, ::onToolClicked, ::onDroppedOnGrid)
        if (!show(grid)) return false
        this.strip = strip
        toolbarWasVisible = strip.forkToolbarVisible
        strip.setToolbarVisibility(false) // the pinned row is where tiles get dropped
        strip.setOnDragListener(::onDragOverStrip)
        return true
    }

    fun showResize() {
        if (Settings.getValues().mIsFloatingKeyboard) return // floating mode has its own resize handle
        show(ResizeView(KeyboardSwitcher.getInstance().wrapperView?.context ?: return))
    }

    @JvmStatic
    fun close() {
        ForkKeys.setSelectMode(false)
        val view = overlay ?: return
        overlay = null
        (view.parent as? ViewGroup)?.removeView(view)
        strip?.let {
            it.setOnDragListener(null)
            it.setToolbarVisibility(toolbarWasVisible)
        }
        strip = null
    }

    /** Called with every new input view. Brings the resize handle back after the keyboard reloaded at its new height. */
    @JvmStatic
    fun onInputViewCreated(wrapper: KeyboardWrapperView) {
        overlay = null
        strip = null
        if (!reopenResize) return
        reopenResize = false
        wrapper.doOnLayout { wrapper.post { showResize() } }
    }

    /** One-handed mode lays out its children by hand, so the overlay follows the keyboard there. */
    @JvmStatic
    fun layoutOverlay(keyboardView: View) {
        overlay?.layout(keyboardView.left, keyboardView.top, keyboardView.right, keyboardView.bottom)
    }

    /** A long press on a pinned tool while the grid is open starts dragging it. */
    @JvmStatic
    fun onLongClickPinnedKey(view: View): Boolean {
        if (!isToolsGridOpen) return false
        val key = view.tag as? ToolbarKey ?: return false
        startDrag(view, ToolDrag(key, fromPinned = true))
        return true
    }

    fun startDrag(view: View, drag: ToolDrag) {
        ViewCompat.startDragAndDrop(view, ClipData.newPlainText("tool", drag.key.name), View.DragShadowBuilder(view), drag, 0)
    }

    // height scale of the current orientation, the same setting as Appearance > Keyboard height
    fun heightScalePrefKey(): String {
        val landscape = KeyboardSwitcher.getInstance().wrapperView?.resources?.configuration?.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val index = findIndexOfDefaultSetting(landscape, FoldableUtils.isFolded)
        return createPrefKeyForBooleanSettings(Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX, index, 2)
    }

    fun applyHeightScale(scale: Float?) {
        val wrapper = KeyboardSwitcher.getInstance().wrapperView ?: return
        wrapper.context.prefs().edit {
            if (scale == null) remove(heightScalePrefKey()) else putFloat(heightScalePrefKey(), scale)
        }
        reopenResize = true
        close()
        KeyboardSwitcher.getInstance().setThemeNeedsReload()
    }

    private fun show(view: View): Boolean {
        close()
        val switcher = KeyboardSwitcher.getInstance()
        val wrapper = switcher.wrapperView ?: return false
        val keyboard = switcher.visibleKeyboardView ?: return false
        if (keyboard.height == 0) return false
        wrapper.addView(view, FrameLayout.LayoutParams(keyboard.width, keyboard.height))
        view.x = keyboard.left.toFloat()
        overlay = view
        return true
    }

    private fun onToolClicked(tile: View) {
        val listener = KeyboardSwitcher.getInstance().wrapperView?.keyboardActionListener ?: return
        close()
        onClickToolbarKey(tile) {
            listener.onCodeInput(it, Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE, false)
        }
    }

    private fun onDroppedOnGrid(drag: ToolDrag) {
        val strip = strip ?: return
        if (!drag.fromPinned) return
        removePinnedKey(strip.context.prefs(), drag.key)
        strip.forkReloadPinnedKeys()
    }

    private fun onDragOverStrip(view: View, event: android.view.DragEvent): Boolean {
        val drag = event.localState as? ToolDrag ?: return false
        val strip = strip ?: return false
        if (event.action == android.view.DragEvent.ACTION_DROP) {
            ForkToolbar.pinAt(strip.context.prefs(), drag.key, dropIndex(strip.forkPinnedKeys, view, event.x))
            strip.forkReloadPinnedKeys()
        }
        return true
    }

    /** Position among the pinned keys for a drop at [x], in [target]'s coordinates. */
    private fun dropIndex(pinned: ViewGroup, target: View, x: Float): Int {
        val pinnedLocation = IntArray(2).also { pinned.getLocationInWindow(it) }
        val targetLocation = IntArray(2).also { target.getLocationInWindow(it) }
        val xInPinned = x + targetLocation[0] - pinnedLocation[0]
        return (0 until pinned.childCount).count { i ->
            val child = pinned.getChildAt(i)
            child.visibility == View.VISIBLE && (child.left + child.right) / 2f < xInPinned
        }
    }
}

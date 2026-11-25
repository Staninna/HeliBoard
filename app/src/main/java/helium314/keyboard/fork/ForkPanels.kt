// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork

import android.content.ClipData
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.utils.dpToPx
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
    val isOpen get() = overlay != null

    fun showRewrite(prompt: helium314.keyboard.fork.ai.RewritePrompts.Prompt?) {
        val context = KeyboardSwitcher.getInstance().wrapperView?.context ?: return
        show(helium314.keyboard.fork.ai.RewriteView(context, prompt), context.getString(R.string.rewrite))
    }

    fun showTranslate() {
        val context = KeyboardSwitcher.getInstance().wrapperView?.context ?: return
        show(helium314.keyboard.fork.online.TranslateView(context), context.getString(R.string.translate))
    }

    fun showGifs(query: String = "") {
        val context = KeyboardSwitcher.getInstance().wrapperView?.context ?: return
        show(helium314.keyboard.fork.online.GifView(context, query), context.getString(R.string.gif))
    }

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
        strip.forkHideSuggestions()
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
        hideTitleBar()
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
        titleBar = null // the strip container was rebuilt with the input view
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

    /** Shows [view] over the keys. With a [title], a bar with that title and a back arrow covers the suggestion strip. */
    private fun show(view: View, title: String? = null): Boolean {
        close()
        helium314.keyboard.fork.ai.ForkAi.onKey() // a panel covers the keys: no proofread or undo offer behind it
        val switcher = KeyboardSwitcher.getInstance()
        val wrapper = switcher.wrapperView ?: return false
        val keyboard = switcher.visibleKeyboardView ?: return false
        if (keyboard.height == 0) return false
        if (title != null) showTitleBar(wrapper, title) // before the panel attaches, so it knows whether the bar is there
        wrapper.addView(view, FrameLayout.LayoutParams(keyboard.width, keyboard.height))
        view.x = keyboard.left.toFloat()
        overlay = view
        return true
    }

    /** True while the title bar is up, so a panel needs no close button of its own. */
    val hasTitleBar get() = titleBar?.isAttachedToWindow == true
    private var titleBar: View? = null

    private fun showTitleBar(wrapper: View, title: String) {
        val container = (wrapper.parent as? View)?.findViewById<FrameLayout>(R.id.strip_container) ?: return
        if (!container.isShown || container.height == 0) return
        val context = container.context
        val colors = Settings.getValues().mColors
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(colors.get(ColorType.MAIN_BACKGROUND)) // one surface with the panel below
            isClickable = true // nothing in the strip below reacts while the bar covers it
        }
        val back = ImageButton(context).apply {
            setImageResource(R.drawable.ic_arrow_back)
            imageTintList = android.content.res.ColorStateList.valueOf(colors.get(ColorType.TOOL_BAR_KEY))
            background = null
            contentDescription = context.getString(R.string.spoken_description_action_previous)
            setOnClickListener { close() }
        }
        val edge = context.resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_edge_key_width)
        bar.addView(back, LinearLayout.LayoutParams(edge, edge).apply { marginStart = 1.dpToPx(context.resources) })
        bar.addView(TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ForkColors.readableOn(colors.get(ColorType.MAIN_BACKGROUND), colors.get(ColorType.KEY_TEXT)))
            maxLines = 1
            ViewCompat.setAccessibilityHeading(this, true)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 12.dpToPx(context.resources) })
        container.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        titleBar = bar
    }

    private fun hideTitleBar() {
        val bar = titleBar ?: return
        titleBar = null
        (bar.parent as? ViewGroup)?.removeView(bar)
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

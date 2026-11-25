// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.updatePadding
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.dpToPx
import helium314.keyboard.latin.utils.getStringResourceOrName
import helium314.keyboard.latin.utils.prefs

/**
 * Fork: the panel with every tool, like Gboard's. Tap a tile to use the tool, long-press it to drag
 * it onto the pinned row. Pinned tools dragged down onto the grid get unpinned.
 */
@SuppressLint("ViewConstructor")
class ToolsGridView(
    context: Context,
    private val onToolClicked: (View) -> Unit,
    private val onDropped: (ToolDrag) -> Unit,
) : FrameLayout(context) {
    private val theme = Settings.getValues().mColors

    init {
        theme.setBackground(this, ColorType.MAIN_BACKGROUND)
        isClickable = true // keep touches away from the keyboard below
        val pad = 8.dpToPx(resources)

        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        content.addView(TextView(context).apply {
            setText(R.string.fork_tools_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(theme.get(ColorType.KEY_HINT_TEXT))
            setPadding(pad, 0, pad, pad)
        })
        val grid = GridLayout(context).apply { columnCount = COLUMNS }
        ForkToolbar.gridKeys(context.prefs()).forEach { grid.addView(tile(it)) }
        content.addView(grid, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(ScrollView(context).apply { addView(content) }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setOnDragListener(::onDrag)
        // the keyboard area reaches under the navigation bar, keep the last row scrollable above it
        doOnAttach {
            val navBar = ViewCompat.getRootWindowInsets(it)?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
            content.updatePadding(bottom = pad + navBar)
        }
    }

    private fun tile(key: ToolbarKey): View {
        val tileHeight = 64.dpToPx(resources)
        val tile = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            tag = key
            contentDescription = key.name.lowercase().getStringResourceOrName("", context)
            background = GradientDrawable().apply {
                cornerRadius = 16.dpToPx(resources).toFloat()
                setColor(theme.get(ColorType.FUNCTIONAL_KEY_BACKGROUND))
            }
            setOnClickListener(onToolClicked)
            setOnLongClickListener { ForkPanels.startDrag(it, ToolDrag(key, fromPinned = false)); true }
        }
        tile.addView(ImageView(context).apply {
            setImageDrawable(KeyboardIconsSet.instance.getNewDrawable(key.name, context))
            theme.setColor(this, ColorType.TOOL_BAR_KEY)
        }, LinearLayout.LayoutParams(28.dpToPx(resources), 28.dpToPx(resources)))
        tile.addView(TextView(context).apply {
            text = tile.contentDescription
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(theme.get(ColorType.KEY_TEXT))
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(4.dpToPx(resources), 2.dpToPx(resources), 4.dpToPx(resources), 0)
        })
        val margin = 4.dpToPx(resources)
        tile.layoutParams = GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)
        ).apply { width = 0; height = tileHeight; setMargins(margin, margin, margin, margin) }
        return tile
    }

    private fun onDrag(view: View, event: DragEvent): Boolean {
        val drag = event.localState as? ToolDrag ?: return false
        if (event.action == DragEvent.ACTION_DROP) onDropped(drag)
        return true
    }

    private companion object {
        const val COLUMNS = 4
    }
}

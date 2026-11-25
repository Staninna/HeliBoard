// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.dpToPx

/** Fork: kaomoji from the bundled Mozc list, grouped by mood. */
object Kaomoji {
    const val ASSET = "kaomoji/kaomoji.tsv"

    /** Groups of `kaomoji<TAB>category` lines, in the order the categories first appear. */
    fun parse(tsv: String): List<Pair<String, List<String>>> =
        tsv.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line -> line.split('\t').takeIf { it.size >= 2 }?.let { it[1].trim() to it[0] } }
            .groupBy({ it.first }, { it.second })
            .map { (category, kaomoji) -> category to kaomoji.distinct() }

    fun load(context: Context) = parse(context.assets.open(ASSET).bufferedReader().use { it.readText() })
}

@SuppressLint("ViewConstructor")
class KaomojiView(context: Context, private val onPick: (String) -> Unit) : ScrollView(context) {
    private val colors = Settings.getValues().mColors

    init {
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val pad = 8.dpToPx(resources)
        Kaomoji.load(context).forEach { (category, kaomoji) ->
            content.addView(TextView(context).apply {
                text = categoryName(category)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(colors.get(ColorType.KEY_HINT_TEXT))
                setPadding(pad * 2, pad, pad, pad / 2)
            })
            val grid = GridLayout(context).apply { columnCount = COLUMNS }
            kaomoji.forEach { grid.addView(cell(it)) }
            content.addView(grid)
        }
        addView(content)
    }

    private fun cell(kaomoji: String) = TextView(context).apply {
        text = kaomoji
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(colors.get(ColorType.KEY_TEXT))
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        minHeight = 44.dpToPx(resources)
        setOnClickListener { view: View ->
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, view, HapticEvent.KEY_PRESS)
            onPick(kaomoji)
        }
        layoutParams = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f))
            .apply { width = 0 }
    }

    private fun categoryName(category: String) = when (category) {
        "smile" -> context.getString(R.string.fork_kaomoji_smile)
        "sadness" -> context.getString(R.string.fork_kaomoji_sadness)
        "sweat" -> context.getString(R.string.fork_kaomoji_sweat)
        "displeasure" -> context.getString(R.string.fork_kaomoji_displeasure)
        "surprise" -> context.getString(R.string.fork_kaomoji_surprise)
        else -> category
    }

    private companion object {
        const val COLUMNS = 3
    }
}

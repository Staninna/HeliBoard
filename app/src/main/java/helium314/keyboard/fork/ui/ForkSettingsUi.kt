// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork.ui

import android.graphics.drawable.VectorDrawable
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.R
import helium314.keyboard.settings.IconOrImage

/**
 * Fork: the settings look of Android 16 and 17 (Material 3 Expressive). Each group of settings is one rounded
 * card made of segments; screens sit on a tinted background.
 */
object ForkUi {
    /** distance of cards from the screen edges */
    val ScreenPadding = 16.dp
    /** padding inside a row; category titles use the same so they line up with the row content */
    val RowPadding = 16.dp
    val IconContainerSize = 40.dp

    /** true while inside a card: rows then leave the background to the card */
    val LocalInGroup = compositionLocalOf { false }

    val isDark @Composable get() = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    /** Card color, one step brighter than the screen. */
    val cardColor @Composable get() =
        if (isDark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLowest
}

/**
 * Splits upstream's flat settings list into groups: an Int is a category title and starts a group,
 * anything else is a setting key, null for a setting that is hidden right now.
 */
fun settingGroups(settings: List<Any?>): List<Pair<Int?, List<Any?>>> {
    val groups = mutableListOf<Pair<Int?, MutableList<Any?>>>()
    for (item in settings) {
        if (item is Int) groups.add(item to mutableListOf())
        else (groups.lastOrNull() ?: (null to mutableListOf<Any?>()).also { groups.add(it) }).second.add(item)
    }
    return groups
}

/** Title above a group of settings, lined up with the content of the rows below. */
@Composable
fun GroupTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        modifier = modifier.padding(
            start = ForkUi.ScreenPadding + ForkUi.RowPadding, end = ForkUi.ScreenPadding + ForkUi.RowPadding,
            top = 24.dp, bottom = 8.dp
        ),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
    )
}

/**
 * One rounded card holding [items] as segments: large outer corners, small inner ones and a small gap,
 * like the settings app. Null items are left out; items appear and disappear animated.
 */
@Composable
fun SettingsGroup(items: List<Pair<Any, (@Composable () -> Unit)?>>, modifier: Modifier = Modifier) {
    val visible = items.filter { it.second != null }
    Column(
        modifier.fillMaxWidth().padding(horizontal = ForkUi.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        items.forEach { (key, content) ->
            AnimatedVisibility(visible = content != null) {
                if (content != null) {
                    val index = visible.indexOfFirst { it.first == key }
                    GroupSegment(index, visible.size) { content() }
                }
            }
        }
    }
}

/** A single segment at [index] of [count]; also used by screens that build their own groups. */
@Composable
fun GroupSegment(index: Int, count: Int, content: @Composable () -> Unit) {
    val shape = segmentShape(index, count)
    Box(Modifier.fillMaxWidth().clip(shape).background(ForkUi.cardColor)) {
        CompositionLocalProvider(ForkUi.LocalInGroup provides true) { content() }
    }
}

/** Shape of segment [index] of [count]: 24 dp outer corners and 4 dp inner ones, as in Android 16 settings. */
fun segmentShape(index: Int, count: Int): Shape {
    val outer = 24.dp
    val inner = 4.dp
    return RoundedCornerShape(
        topStart = if (index == 0) outer else inner, topEnd = if (index == 0) outer else inner,
        bottomStart = if (index == count - 1) outer else inner, bottomEnd = if (index == count - 1) outer else inner,
    )
}

/** Convenience for a card of fixed rows. */
@Composable
fun SettingsGroup(vararg rows: (@Composable () -> Unit)?, modifier: Modifier = Modifier) =
    SettingsGroup(rows.mapIndexed { i, row -> i to row }, modifier)

/** Settings icons sit in a round tonal container; images (like user icons) are shown as they are. */
@Composable
fun SettingsIcon(@DrawableRes icon: Int, name: String?) {
    val drawable = ContextCompat.getDrawable(LocalContext.current, icon)
    if (drawable !is VectorDrawable) {
        IconOrImage(icon, name, 40)
        return
    }
    Box(
        Modifier.size(ForkUi.IconContainerSize).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(painterResource(icon), name, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** Switch with the check and close thumb icons of Android 16 and later. */
@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
) {
    androidx.compose.material3.Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        thumbContent = {
            Icon(
                painterResource(if (checked) R.drawable.ic_fork_check else R.drawable.ic_fork_close),
                contentDescription = null,
                modifier = Modifier.size(SwitchDefaults.IconSize),
            )
        },
    )
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork

import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.defaultPinnedToolbarPref
import helium314.keyboard.latin.utils.defaultToolbarPref

/**
 * Fork: toolbar prefs for the tools grid. Both prefs are `NAME:true,NAME:false,...` lists where the
 * order is the display order, and `true` means shown.
 */
object ForkToolbar {
    /** Keys that make no sense as a tool tile. */
    private val notTools = setOf(ToolbarKey.CLOSE_HISTORY, ToolbarKey.BACKGROUND_GATHERING)

    /** Every tool, in the order of the toolbar setting, so the user's sorting carries over to the grid. */
    fun gridKeys(prefs: SharedPreferences): List<ToolbarKey> {
        val ordered = prefs.getString(Settings.PREF_TOOLBAR_KEYS, defaultToolbarPref)!!
            .split(Separators.ENTRY)
            .mapNotNull { entry -> ToolbarKey.entries.firstOrNull { it.name == entry.substringBefore(Separators.KV) } }
        val voice = Settings.getValues().mShowsVoiceInputKey
        return (ordered + ToolbarKey.entries).distinct().filterNot { it in notTools || (it == ToolbarKey.VOICE && !voice) }
    }

    /** Pins [key] so it becomes the [index]th pinned key, counting only pinned keys. */
    fun pinAt(prefs: SharedPreferences, key: ToolbarKey, index: Int) {
        val pref = prefs.getString(Settings.PREF_PINNED_TOOLBAR_KEYS, defaultPinnedToolbarPref)!!
        prefs.edit { putString(Settings.PREF_PINNED_TOOLBAR_KEYS, pinAt(pref, key, index)) }
    }

    fun pinAt(pref: String, key: ToolbarKey, index: Int): String {
        val entries = pref.split(Separators.ENTRY).filter { it.isNotEmpty() }
            .filterNot { it.substringBefore(Separators.KV) == key.name }.toMutableList()
        val pinnedPositions = entries.indices.filter { entries[it].endsWith(Separators.KV + "true") }
        val position = pinnedPositions.getOrNull(index.coerceAtLeast(0))
            ?: ((pinnedPositions.lastOrNull() ?: -1) + 1)
        entries.add(position, key.name + Separators.KV + "true")
        return entries.joinToString(Separators.ENTRY)
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork

import android.content.Context
import helium314.keyboard.latin.common.LocaleUtils
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.dictionary.DictionaryInfoUtils
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.locale
import java.util.Locale

/**
 * Fork: emoji search works out of the box and across every enabled language.
 *
 * Upstream only uses emoji dictionaries the user added by hand, and searches the current language
 * plus its secondary languages. The fork bundles emoji_<lang>.dict files in assets/dicts and
 * searches all enabled languages at once, so "hond" and "dog" both find a dog.
 */
object EmojiDicts {
    private var bundled: List<String>? = null

    /** Copies the best-matching bundled emoji dictionary for each locale that has none yet. */
    fun extractMissing(context: Context, locales: Collection<Locale>) {
        val assets = bundled ?: DictionaryInfoUtils.getAssetsDictionaryList(context)
            ?.filter { it.startsWith(Dictionary.TYPE_EMOJI + "_") }.orEmpty()
            .also { bundled = it }
        if (assets.isEmpty()) return
        locales.forEach { locale ->
            if (DictionaryInfoUtils.getCachedDictForLocaleAndType(locale, Dictionary.TYPE_EMOJI, context) != null)
                return@forEach
            val match = LocaleUtils.getBestMatch(locale, assets) {
                DictionaryInfoUtils.extractLocaleFromAssetsDictionaryFile(it)
            } ?: return@forEach
            DictionaryInfoUtils.extractAssetsDictionary(match, locale, context)
        }
    }

    /** Locales of all enabled subtypes, without [except], each language tag once. */
    fun otherEnabledLocales(except: Locale): List<Locale> =
        SubtypeSettings.getEnabledSubtypes(true).map { it.locale() }
            .distinctBy { it.toLanguageTag() }
            .filter { it.toLanguageTag() != except.toLanguageTag() }
}

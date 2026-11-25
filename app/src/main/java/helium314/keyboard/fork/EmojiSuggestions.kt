// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork

import helium314.keyboard.keyboard.emoji.SupportedEmojis
import helium314.keyboard.latin.SingleDictionaryFacilitator
import helium314.keyboard.latin.common.isEmoji
import helium314.keyboard.latin.makedict.WordProperty
import helium314.keyboard.latin.settings.SettingsValues
import helium314.keyboard.latin.suggestions.Suggest
import helium314.keyboard.latin.suggestions.SuggestedWordInfo
import helium314.keyboard.latin.suggestions.SuggestedWords

/**
 * Fork: Gboard-style emoji in the suggestion strip. When the typed word is an emoji keyword in any
 * enabled language ("dog", "hond"), its best emoji gets the third slot of the strip.
 *
 * Emoji dictionaries store each keyword as a not-a-word entry whose shortcut targets are its emoji,
 * so an exact keyword match is a word property with emoji targets. The target with the lowest
 * weight is the best emoji.
 *
 * Index 0 is the typed word and index 1 the auto-correction, so the emoji goes to index 2 at the
 * earliest. That keeps space from ever committing an emoji.
 */
object EmojiSuggestions {
    private const val EMOJI_INDEX = 2

    @JvmStatic
    fun withBestEmoji(words: SuggestedWords, emojiDict: SingleDictionaryFacilitator?, settings: SettingsValues): SuggestedWords {
        if (emojiDict == null || !settings.mSuggestEmojis || words.isPrediction) return words
        if (words.javaClass != SuggestedWords::class.java) return words // keep special subclasses as they are
        val typed = words.typedWordInfo?.word ?: return words
        if (typed.length < 2) return words
        val keyword = typed.lowercase()

        val targets = rankedEmoji(keyword, emojiDict.getWordProperties(keyword))
        if (targets.isEmpty()) return words
        val found = emojiDict.getSuggestions(keyword).filter { it.word in targets }
        val emoji = targets.firstNotNullOfOrNull { target -> found.firstOrNull { it.word == target } } ?: return words
        return placeEmoji(words, Suggest.useDefaultEmojiSkinTone(emoji))
    }

    /**
     * Emoji for an exact [keyword] match, best first. Lower shortcut weight means a closer match:
     * "dog" gives 🐶 weight 1 and 🌭 weight 13. [properties] come current language first, and the
     * stable sort keeps that order on ties.
     */
    fun rankedEmoji(keyword: String, properties: List<WordProperty>): List<String> =
        properties.filter { it.mWord == keyword }
            .flatMap { it.mShortcutTargets.orEmpty() }
            .filter { isEmoji(it.mWord) && !SupportedEmojis.isUnsupported(it.mWord) }
            .sortedBy { it.probability }
            .map { it.mWord }
            .distinct()

    /** Puts [emoji] in the third slot and drops upstream's weaker emoji matches after the auto-correction. */
    fun placeEmoji(words: SuggestedWords, emoji: SuggestedWordInfo): SuggestedWords {
        val current = words.suggestedWordInfoList
        if (current.getOrNull(1)?.word == emoji.word) return words // already the auto-correction, leave it
        val list = current.filterIndexed { i, info -> i < EMOJI_INDEX || !info.isEmoji }.toMutableList()
        if (list.size < EMOJI_INDEX) return words // nothing to show next to; never make the emoji the auto-correction
        list.add(EMOJI_INDEX, emoji)
        return SuggestedWords(list, words.rawSuggestions, words.typedWordInfo, words.typedWordValid,
            words.willAutoCorrect, words.isObsoleteSuggestions, words.inputStyle, words.sequenceNumber)
    }
}

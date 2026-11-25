// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork

import helium314.keyboard.latin.InputStyle
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.makedict.ProbabilityInfo
import helium314.keyboard.latin.makedict.WeightedString
import helium314.keyboard.latin.makedict.WordProperty
import helium314.keyboard.latin.suggestions.SuggestedWordInfo
import helium314.keyboard.latin.suggestions.SuggestedWords
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
class EmojiSuggestionsTest {
    private fun keyword(word: String, vararg emoji: Pair<String, Int>) = WordProperty(
        word, ProbabilityInfo(100),
        ArrayList(emoji.map { (e, weight) -> WeightedString(e, weight) }), null, true, false
    )

    private fun info(word: String) = SuggestedWordInfo(word, "", 0, 0, Dictionary.DICTIONARY_USER_TYPED, 0, 0)

    private fun suggestions(vararg words: String) = SuggestedWords(
        words.map { info(it) }, null, info(words[0]), true, true, false, InputStyle.TYPING, 0
    )

    @Test fun lowestWeightWins() {
        val dog = keyword("dog", "🌭" to 13, "🐶" to 1, "🦮" to 4)
        assertEquals(listOf("🐶", "🦮", "🌭"), EmojiSuggestions.rankedEmoji("dog", listOf(dog)))
    }

    @Test fun keywordsFromEveryLanguageCount() {
        // the English dictionary knows "hond" only as an entry without emoji, the Dutch one has the dog
        val english = keyword("hond")
        val dutch = keyword("hond", "🐶" to 1)
        assertEquals(listOf("🐶"), EmojiSuggestions.rankedEmoji("hond", listOf(english, dutch)))
    }

    @Test fun onlyExactKeywordMatches() {
        assertEquals(emptyList(), EmojiSuggestions.rankedEmoji("do", listOf(keyword("dog", "🐶" to 1))))
    }

    @Test fun emojiTakesTheThirdSlotAndKeepsTheAutoCorrection() {
        val result = EmojiSuggestions.placeEmoji(suggestions("dog", "dog", "dot", "fog"), info("🐶"))
        assertEquals(listOf("dog", "dog", "🐶", "dot", "fog"), result.suggestedWordInfoList.map { it.word })
        assertEquals(true, result.willAutoCorrect)
    }

    @Test fun replacesWeakerEmojiAfterTheAutoCorrection() {
        val result = EmojiSuggestions.placeEmoji(suggestions("dog", "dog", "🌭", "fog"), info("🐶"))
        assertEquals(listOf("dog", "dog", "🐶", "fog"), result.suggestedWordInfoList.map { it.word })
    }

    @Test fun neverBecomesTheAutoCorrection() {
        val onlyTyped = suggestions("dog")
        assertSame(onlyTyped, EmojiSuggestions.placeEmoji(onlyTyped, info("🐶")))
    }
}

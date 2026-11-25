// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class EmojiFuzzyTest {
    private val index = EmojiIndex(mapOf(
        "pizza" to listOf("🍕" to 1),
        "lizard" to listOf("🦎" to 1),
        "wizard" to listOf("🧙" to 1),
        "dog" to listOf("🐶" to 1, "🌭" to 13),
        "hotdog" to listOf("🌭" to 1),
        "heart" to listOf("❤️" to 1),
        "red" to listOf("❤️" to 3, "🍎" to 5),
        "the" to listOf("🫖" to 20),
        "thermometer" to listOf("🌡️" to 1),
    ))

    @Test fun typoFindsTheRightEmoji() {
        assertEquals("🍕", index.search("piza").first())
        assertEquals("❤️", index.search("hart").first())
    }

    @Test fun prefixFindsCompletions() {
        assertEquals("🍕", index.search("piz").first())
    }

    @Test fun exactBeatsPrefixAndWeightBreaksTies() {
        assertEquals(listOf("🐶", "🌭"), index.search("dog").take(2))
    }

    @Test fun everyWordHasToMatch() {
        assertEquals(listOf("❤️"), index.search("red heart"))
    }

    @Test fun stripTakesExactWordsAndCloseMatchesFromFourLetters() {
        assertEquals("🐶", index.bestForTypedWord("dog"))
        assertEquals("🍕", index.bestForTypedWord("piza"))
        assertEquals("🍕", index.bestForTypedWord("pizz"))
        assertNull(index.bestForTypedWord("piz")) // too short to guess
        assertEquals("🫖", index.bestForTypedWord("the")) // exact keyword still counts
        assertNull(index.bestForTypedWord("ther")) // "thermometer" is a completion but far off
    }

    @Test fun distanceCountsSwapsAsOneEdit() {
        assertEquals(1, EmojiIndex.distance("dgo", "dog", 2))
        assertEquals(3, EmojiIndex.distance("cat", "dog", 2))
    }
}

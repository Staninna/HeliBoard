// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork

import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.dictionary.DictionaryInfoUtils
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class EmojiDictsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun emojiDict(locale: Locale) =
        DictionaryInfoUtils.getCachedDictForLocaleAndType(locale, Dictionary.TYPE_EMOJI, context)

    @Test fun extractsBundledEmojiDictsForMatchingLanguages() {
        val de = Locale.forLanguageTag("de")
        EmojiDicts.extractMissing(context, listOf(Locale.forLanguageTag("en-US"), Locale.forLanguageTag("nl"), de))

        assertNotNull(emojiDict(Locale.forLanguageTag("en-US")), "en-US gets the bundled emoji_en.dict")
        assertNotNull(emojiDict(Locale.forLanguageTag("nl")), "nl gets the bundled emoji_nl.dict")
        assertNull(emojiDict(de), "nothing is bundled for German")
    }

    @Test fun keepsAnExistingEmojiDict() {
        val nl = Locale.forLanguageTag("nl")
        EmojiDicts.extractMissing(context, listOf(nl))
        val file = emojiDict(nl)!!
        file.writeText("user's own dictionary")

        EmojiDicts.extractMissing(context, listOf(nl))

        assertEquals("user's own dictionary", emojiDict(nl)!!.readText())
    }
}

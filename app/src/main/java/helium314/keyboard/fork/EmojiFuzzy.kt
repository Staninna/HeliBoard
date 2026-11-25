// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork

import android.content.Context
import com.android.inputmethod.latin.BinaryDictionary
import helium314.keyboard.keyboard.emoji.SupportedEmojis
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.common.isEmoji
import helium314.keyboard.latin.suggestions.SuggestedWordInfo
import helium314.keyboard.latin.suggestions.SuggestedWords
import helium314.keyboard.latin.suggestions.SuggestionResults
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.dictionary.DictionaryInfoUtils
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Fork: fuzzy emoji search. The dictionary lookup corrects typos by key position, which suits words
 * but not emoji keywords ("piza" found a lizard and a wizard, no pizza). This index holds every
 * keyword of the enabled languages' emoji dictionaries and ranks matches itself.
 */
class EmojiIndex(keywords: Map<String, List<Pair<String, Int>>>) {
    /** keyword to its emoji with weights, lower weight is a closer match */
    private val entries = keywords.entries.map { (keyword, emoji) -> keyword to emoji.sortedBy { it.second } }

    val size get() = entries.size

    /**
     * Emoji for [query], best first. Every word of the query has to match a keyword of the emoji.
     * A keyword matches exactly, by prefix, by containing the word, or with a typo or two.
     */
    fun search(query: String, limit: Int = 60): List<String> {
        val words = query.lowercase(Locale.ROOT).split(' ', '_', '-').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        var scores: Map<String, Double>? = null
        for (word in words) {
            val forWord = HashMap<String, Double>()
            for ((keyword, emoji) in entries) {
                val match = matchScore(word, keyword) ?: continue
                for ((e, weight) in emoji) {
                    val score = match + weight * WEIGHT_FACTOR
                    if (score < (forWord[e] ?: Double.MAX_VALUE)) forWord[e] = score
                }
            }
            scores = scores?.let { previous -> previous.filterKeys { it in forWord }.mapValues { it.value + forWord[it.key]!! } } ?: forWord
            if (scores.isEmpty()) return emptyList()
        }
        return scores!!.entries.sortedBy { it.value }.take(limit).map { it.key }
    }

    /** The single best emoji for a typed word in the suggestion strip, or null if nothing is close enough. */
    fun bestForTypedWord(word: String): String? {
        val lower = word.lowercase(Locale.ROOT)
        if (lower.length < 2) return null
        var best: Pair<String, Double>? = null
        for ((keyword, emoji) in entries) {
            val match = matchScore(lower, keyword) ?: continue
            // while typing, take exact keywords; from 4 letters on also near completions (at most two letters
            // missing, "pizz") and single typos ("piza")
            val nearCompletion = match <= PREFIX + 2 * 0.05 + 1e-9
            // a typo only counts for longer keywords: "ther" is no typo of "the" worth an emoji
            val singleTypo = match == TYPO + 1 && keyword.length >= 5
            val closeEnough = match == EXACT || (lower.length >= 4 && (nearCompletion || singleTypo))
            if (!closeEnough) continue
            val score = match + emoji.first().second * WEIGHT_FACTOR
            if (best == null || score < best.second) best = emoji.first().first to score
        }
        return best?.first
    }

    companion object {
        private const val EXACT = 0.0
        private const val PREFIX = 1.0
        private const val CONTAINS = 3.0
        private const val TYPO = 4.0
        private const val WEIGHT_FACTOR = 0.01
        /** a typo in the start of a longer keyword ranks below a typo in the whole keyword */
        private const val START_PENALTY = 0.5

        /** Lower is better, null for no match. */
        fun matchScore(word: String, keyword: String): Double? {
            if (keyword == word) return EXACT
            if (keyword.startsWith(word)) return PREFIX + (keyword.length - word.length) * 0.05
            if (word.length >= 3 && keyword.contains(word)) return CONTAINS
            if (word.length < 3) return null
            val allowed = if (word.length >= 6) 2 else 1
            // compare with the whole keyword and with its start, so "piz" and "piza" both find "pizza"
            val whole = distance(word, keyword, allowed)
            if (whole <= allowed) return TYPO + whole
            val start = distance(word, keyword.take(word.length), allowed)
            return if (start <= allowed) TYPO + start + START_PENALTY else null
        }

        /** Optimal string alignment distance, cut off above [max]. */
        fun distance(a: String, b: String, max: Int): Int {
            if (kotlin.math.abs(a.length - b.length) > max) return max + 1
            val d = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in 0..a.length) d[i][0] = i
            for (j in 0..b.length) d[0][j] = j
            for (i in 1..a.length) {
                var rowMin = Int.MAX_VALUE
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = min(v, d[i - 2][j - 2] + 1)
                    d[i][j] = v
                    rowMin = min(rowMin, v)
                }
                if (rowMin > max) return max + 1
            }
            return d[a.length][b.length]
        }
    }
}

object EmojiFuzzy {
    private const val TAG = "EmojiFuzzy"
    // daemon thread: an idle index builder must never keep a process (or the unit test JVM) alive
    private val executor = Executors.newSingleThreadExecutor { Thread(it, TAG).apply { isDaemon = true } }
    @Volatile private var files: List<String> = emptyList()
    @Volatile var index: EmojiIndex? = null
        private set

    /** Builds the index for [locales] in the background, unless it already covers the same dictionaries. */
    fun prepare(context: Context, locales: Collection<Locale>) {
        val dictFiles = locales.mapNotNull {
            DictionaryInfoUtils.getCachedDictForLocaleAndType(it, Dictionary.TYPE_EMOJI, context)
        }.distinctBy { it.absolutePath }
        val key = dictFiles.map { "${it.absolutePath}:${it.lastModified()}" }
        if (key == files) return
        files = key
        executor.execute {
            val built = build(dictFiles)
            if (files == key) index = built
        }
    }

    /** Search results shaped like the dictionary's, or null while the index is still being built. */
    fun suggestions(words: List<String>, dict: Dictionary): SuggestionResults? {
        val index = index ?: return null
        val emoji = index.search(words.joinToString(" "), SuggestedWords.MAX_SUGGESTIONS)
        val results = SuggestionResults(SuggestedWords.MAX_SUGGESTIONS, NgramContext.EMPTY_PREV_WORDS_INFO, false)
        emoji.forEachIndexed { rank, e -> results.add(wordInfo(e, dict, SuggestedWordInfo.MAX_SCORE - rank)) }
        return results
    }

    fun wordInfo(emoji: String, dict: Dictionary, score: Int = SuggestedWordInfo.MAX_SCORE) = SuggestedWordInfo(
        emoji, "", score, SuggestedWordInfo.KIND_SHORTCUT, dict, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE
    )

    private fun build(dictFiles: List<File>): EmojiIndex {
        val keywords = HashMap<String, MutableList<Pair<String, Int>>>()
        for (file in dictFiles) {
            try {
                val dict = BinaryDictionary(file.absolutePath, 0, file.length(), false, Locale.ROOT, Dictionary.TYPE_EMOJI, false)
                try {
                    var token = 0
                    do {
                        val next = dict.getNextWordProperty(token)
                        val property = next.mWordProperty
                        val emoji = property?.mShortcutTargets.orEmpty()
                            .filter { isEmoji(it.mWord) && !SupportedEmojis.isUnsupported(it.mWord) }
                        if (property != null && emoji.isNotEmpty()) {
                            val list = keywords.getOrPut(property.mWord.lowercase(Locale.ROOT)) { mutableListOf() }
                            emoji.forEach { target -> list.add(target.mWord to target.probability) }
                        }
                        token = next.mNextToken
                    } while (token != 0)
                } finally {
                    dict.close()
                }
            } catch (e: Throwable) { // also UnsatisfiedLinkError when the native library is missing (unit tests)
                Log.w(TAG, "could not read emoji keywords from ${file.name}", e)
            }
        }
        return EmojiIndex(keywords.mapValues { (_, emoji) -> emoji.groupBy { it.first }.map { (e, w) -> e to w.minOf { it.second } } })
    }
}

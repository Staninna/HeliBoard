// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork.ai

import android.content.Context
import android.view.textclassifier.TextClassificationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Fork: the language a text is written in, from Android's own on-device language detection, so the
 * rewrite prompt can name it. A model told "keep the language" in an English prompt still drifts to
 * English; told "write it in Dutch", it doesn't.
 */
object TextLanguage {
    /** The English name of [text]'s language, or null when Android isn't sure. */
    suspend fun of(context: Context, text: String): String? = withContext(Dispatchers.Default) {
        runCatching {
            val classifier = context.getSystemService(TextClassificationManager::class.java)?.textClassifier
                ?: return@runCatching null
            val result = classifier.detectLanguage(android.view.textclassifier.TextLanguage.Request.Builder(text).build())
            if (result.localeHypothesisCount == 0) return@runCatching null
            val locale = result.getLocale(0)
            if (result.getConfidenceScore(locale) < MIN_CONFIDENCE) return@runCatching null
            nameOf(locale.language)
        }.getOrNull()
    }

    fun nameOf(code: String): String? =
        code.takeIf { it.isNotBlank() && it != "und" }?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH) }
            ?.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }

    private const val MIN_CONFIDENCE = 0.6f
}

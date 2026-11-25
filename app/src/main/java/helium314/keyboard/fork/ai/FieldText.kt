// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork.ai

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/** Fork: the part of the app's text field that a rewrite reads and replaces. */
object FieldText {
    const val MAX_CHARS = 4000
    const val AROUND_CURSOR = 2000

    enum class Scope { SELECTION, FIELD, AROUND_CURSOR }

    /**
     * [text] sits at [start]..[start]+length in the field. [start] is -1 when the field did not
     * tell its positions; then [before] and [after] say how far the text reaches around the cursor.
     */
    data class Target(val text: String, val scope: Scope, val start: Int, val before: Int = 0, val after: Int = 0)

    /** The selection, else the whole field, else the text around the cursor. Null when there is nothing. */
    fun read(ic: InputConnection): Target? {
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        val full = extracted?.text?.toString()
        if (extracted != null && full != null) {
            val base = extracted.startOffset
            val a = minOf(extracted.selectionStart, extracted.selectionEnd)
            val b = maxOf(extracted.selectionStart, extracted.selectionEnd)
            if (a in 0 until b && b <= full.length) {
                val selected = full.substring(a, b)
                if (selected.isNotBlank() && selected.length <= MAX_CHARS) return Target(selected, Scope.SELECTION, base + a)
            } else if (base == 0 && full.length <= MAX_CHARS) {
                return full.takeIf { it.isNotBlank() }?.let { Target(it, Scope.FIELD, 0) }
            }
        }
        // the field is too long or does not share its text: only the part around the cursor
        val before = ic.getTextBeforeCursor(AROUND_CURSOR, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(AROUND_CURSOR, 0)?.toString().orEmpty()
        val text = before + after
        if (text.isBlank()) return null
        val cursor = extracted?.let { it.startOffset + it.selectionEnd } ?: -1
        return Target(text, Scope.AROUND_CURSOR, if (cursor >= 0) cursor - before.length else -1, before.length, after.length)
    }

    /** True while the field still holds [target]'s text where it was read. */
    fun unchanged(ic: InputConnection, target: Target): Boolean {
        if (target.start < 0) {
            val before = ic.getTextBeforeCursor(target.before, 0)?.toString()
            val after = ic.getTextAfterCursor(target.after, 0)?.toString()
            return before + after == target.text
        }
        return textAt(ic, target.start, target.text.length) == target.text
    }

    /** Replaces [target] with [replacement] in one batch edit. Returns false when the field changed meanwhile. */
    fun replace(ic: InputConnection, target: Target, replacement: String): Boolean {
        ic.beginBatchEdit()
        try {
            ic.finishComposingText()
            if (!unchanged(ic, target)) return false
            if (target.start >= 0) {
                ic.setSelection(target.start, target.start + target.text.length)
                ic.commitText(replacement, 1)
            } else {
                ic.deleteSurroundingText(target.before, target.after)
                ic.commitText(replacement, 1)
            }
            return true
        } finally {
            ic.endBatchEdit()
        }
    }

    private fun textAt(ic: InputConnection, start: Int, length: Int): String? {
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return null
        val text = extracted.text?.toString() ?: return null
        val from = start - extracted.startOffset
        if (from < 0 || from + length > text.length) return null
        return text.substring(from, from + length)
    }
}

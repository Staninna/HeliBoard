// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import helium314.keyboard.latin.suggestions.SuggestionStripView

/**
 * Fork: keeps a password manager's autofill chips (Bitwarden and the like) for the field they came
 * for. Android sends them once, and the keyboard used to wipe them when it reset the suggestion bar
 * right after, or hid them behind the toolbar in an empty field. Now an empty bar shows them again
 * until the user closes them or moves to another field.
 */
object AutofillChips {
    private var chips: View? = null
    private var field: String? = null
    private var currentField: String? = null

    /** A new field: chips from the last one no longer apply. */
    @JvmStatic
    fun onStartInput(info: EditorInfo?) {
        currentField = info?.let { "${it.packageName}/${it.fieldId}/${it.inputType}" }
        if (currentField != field) clear()
    }

    /** Android sent chips for the current field. */
    @JvmStatic
    fun onChips(view: View) {
        chips = view
        field = currentField
    }

    @JvmStatic
    fun clear() {
        chips = null
        field = null
    }

    /** Shows the kept chips in [strip] instead of an empty bar. Returns false when there are none. */
    @JvmStatic
    fun showInstead(strip: SuggestionStripView?): Boolean {
        val view = chips ?: return false
        if (strip == null || field != currentField) return false
        (view.parent as? ViewGroup)?.removeView(view)
        strip.setExternalSuggestionView(view, true)
        strip.setToolbarVisibility(false) // the chips live in the suggestion row, which the toolbar covers
        return true
    }
}

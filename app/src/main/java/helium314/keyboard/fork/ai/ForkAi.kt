// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork.ai

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import helium314.keyboard.fork.ForkPanels
import helium314.keyboard.fork.ForkSettings
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import java.lang.ref.WeakReference

/**
 * Fork: rewrite and proofread with SmolChat Server. The keyboard calls the hooks below; the rewrite
 * panel does the work, and [AiChip] at the start of the suggestion bar offers proofread and undo.
 */
object ForkAi {
    private const val PROOFREAD_IDLE_MS = 2_000L
    private const val PROOFREAD_MIN_WORDS = 5

    private var ime = WeakReference<InputMethodService>(null)
    private var editorInfo: EditorInfo? = null
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val proofreadCheck = Runnable { offerProofread() }

    /** The last replacement, so the chip can put the original back until the user types on. */
    private var undo: Undo? = null

    private class Undo(val inserted: FieldText.Target, val original: String)

    val inputConnection: InputConnection? get() = ime.get()?.currentInputConnection

    @JvmStatic
    fun onCreate(service: InputMethodService) {
        ime = WeakReference(service)
    }

    @JvmStatic
    fun onStartInputView(info: EditorInfo?) {
        editorInfo = info
        clearChip()
    }

    @JvmStatic
    fun onFinishInputView() {
        clearChip()
    }

    /** Every key press: the user typed on, so undo and proofread are no longer offered. */
    @JvmStatic
    fun onKey() {
        if (ime.get() == null) return // no keyboard running
        if (AiChip.isShowing) clearChip()
        handler.removeCallbacks(proofreadCheck)
    }

    /** The cursor or the text changed: look again for a proofread after a pause. */
    @JvmStatic
    fun onUpdateSelection() {
        handler.removeCallbacks(proofreadCheck)
        if (undo == null && proofreadAllowed()) handler.postDelayed(proofreadCheck, PROOFREAD_IDLE_MS)
    }

    fun openRewrite(prompt: RewritePrompts.Prompt? = null) {
        clearChip()
        ForkPanels.showRewrite(prompt)
    }

    /** Replaces [target] with [replacement] and offers to undo it. */
    fun accept(target: FieldText.Target, replacement: String): Boolean {
        val ic = inputConnection ?: return false
        if (!FieldText.replace(ic, target, replacement)) return false
        val start = target.start
        undo = Undo(
            if (start >= 0) FieldText.Target(replacement, target.scope, start)
            else FieldText.Target(replacement, target.scope, -1, before = replacement.length, after = 0),
            target.text,
        )
        AiChip.show(AiChip.Kind.UNDO)
        return true
    }

    fun onChipClicked(kind: AiChip.Kind) {
        when (kind) {
            AiChip.Kind.PROOFREAD -> openRewrite(RewritePrompts.proofread)
            AiChip.Kind.UNDO -> {
                val u = undo ?: return clearChip()
                inputConnection?.let { FieldText.replace(it, u.inserted, u.original) }
                clearChip()
            }
        }
    }

    private fun clearChip() {
        undo = null
        handler.removeCallbacks(proofreadCheck)
        AiChip.hide()
    }

    private fun offerProofread() {
        if (!proofreadAllowed() || ForkPanels.isOpen) return
        val before = inputConnection?.getTextBeforeCursor(FieldText.MAX_CHARS, 0)?.toString() ?: return
        if (before.split(Regex("\\s+")).count { it.any(Char::isLetter) } >= PROOFREAD_MIN_WORDS) AiChip.show(AiChip.Kind.PROOFREAD)
    }

    private fun proofreadAllowed(): Boolean {
        val service = ime.get() ?: return false
        val info = editorInfo ?: return false
        if (!service.prefs().getBoolean(ForkSettings.PREF_PROOFREAD_CHIP, ForkSettings.DEFAULT_PROOFREAD_CHIP)) return false
        if (!LocalAiPairing.isPaired(service)) return false
        return !isPrivate(info)
    }

    /** Passwords and fields that ask not to learn from what is typed, and incognito mode. */
    fun isPrivate(info: EditorInfo): Boolean {
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val password = (cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )) || (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val noLearning = info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        return password || noLearning || Settings.getValues().mIncognitoModeEnabled
    }

    fun context(): Context? = ime.get()
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork.online

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.doOnAttach
import androidx.core.view.isVisible
import helium314.keyboard.fork.ForkPanels
import helium314.keyboard.fork.PanelCard
import helium314.keyboard.fork.PanelKit
import helium314.keyboard.fork.PanelKit.dp
import helium314.keyboard.fork.ai.FieldText
import helium314.keyboard.fork.ai.ForkAi
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SettingsActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Fork: the translate panel over the keys. The chips pick the language to translate into; the
 * user's LibreTranslate server detects the language of the text. Replace puts the translation into
 * the text field in one edit, and the chip in the suggestion bar can undo it.
 */
@SuppressLint("ViewConstructor")
class TranslateView(context: Context) : FrameLayout(context) {
    private val prefs = context.prefs()
    private val scope = MainScope()
    private var job: Job? = null

    private var languages = OnlineSettings.languages(prefs)
    private var targetCode: String? = null
    private var target: FieldText.Target? = null
    private var answer: String? = null

    private val chipRow = LinearLayout(context)
    private val card = PanelCard(context)
    private val retry = PanelKit.pill(context, filled = false, icon = R.drawable.ic_fork_refresh)
    private val cancel = PanelKit.pill(context, filled = false)
    private val accept = PanelKit.pill(context, filled = true)

    init {
        setBackgroundColor(Settings.getValues().mColors.get(ColorType.MAIN_BACKGROUND))
        isClickable = true // the keys below stay out of reach
        val margin = PanelKit.MARGIN_DP.dp(context)

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(PanelKit.chipRow(context, chipRow), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 48.dp(context))
            .apply { topMargin = 4.dp(context) })
        column.addView(card, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            .apply { setMargins(margin, 8.dp(context), margin, 0) })

        val buttons = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        retry.setText(R.string.fork_ai_retry)
        retry.setOnClickListener { start() }
        buttons.addView(retry, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, 48.dp(context)))
        buttons.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        cancel.setText(R.string.fork_ai_cancel)
        cancel.setOnClickListener { ForkPanels.close() }
        buttons.addView(cancel, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, 48.dp(context)).apply { marginEnd = 8.dp(context) })
        accept.setText(R.string.fork_ai_accept)
        accept.setOnClickListener { onAccept() }
        buttons.addView(accept, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, 48.dp(context)))
        column.addView(buttons, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 48.dp(context))
            .apply { setMargins(margin, 12.dp(context), margin, 12.dp(context)) })

        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        doOnAttach {
            PanelKit.keepAboveNavBar(this)
            cancel.isVisible = !ForkPanels.hasTitleBar
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope.cancel()
    }

    private fun buildChips() {
        chipRow.removeAllViews()
        for (language in OnlineSettings.targetOrder(languages)) {
            chipRow.addView(PanelKit.chip(context, language.name, language.code == targetCode) {
                if (language.code == targetCode && job?.isActive == true) return@chip
                targetCode = language.code
                OnlineSettings.setLastTarget(prefs, language.code)
                buildChips()
                start()
            }, PanelKit.chipParams(context))
        }
    }

    /** Reads the field again and translates it into [targetCode]. */
    private fun start() {
        job?.cancel()
        answer = null
        setButtons(working = true)
        if (ForkAi.isPassword()) {
            card.showMessage(R.string.fork_translate_password)
            setButtons(working = false)
            retry.isVisible = false
            return
        }
        val read = ForkAi.inputConnection?.let { FieldText.read(it) }
        target = read
        if (read == null) {
            card.showMessage(R.string.fork_translate_empty)
            setButtons(working = false)
            retry.isVisible = false
            return
        }
        card.showProgress(R.string.fork_translate_working)
        job = scope.launch {
            try {
                run(read)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LibreTranslate.Failure) {
                showProblem(e.problem)
            } catch (e: Exception) {
                showProblem(LibreTranslate.Problem.FAILED)
            }
        }
    }

    private suspend fun run(read: FieldText.Target) {
        val on = OnlineSettings.translateOn(context)
        val server = OnlineSettings.translateServer(context)
        if (!on || server.isBlank()) throw LibreTranslate.Failure(LibreTranslate.Problem.NOT_SET_UP)
        if (languages.isEmpty()) {
            languages = LibreTranslate.languages(server, on)
            OnlineSettings.saveLanguages(prefs, languages)
        }
        if (targetCode == null || languages.none { it.code == targetCode }) targetCode = lastTarget()
        buildChips()
        val code = targetCode
        if (code == null) { // first time: nothing is sent until a language is picked
            card.showMessage(R.string.fork_translate_pick, icon = false)
            setButtons(working = false)
            retry.isVisible = false
            return
        }
        val result = LibreTranslate.translate(server, OnlineSettings.translateKey(context), read.text, code, on)
        if (result.text.isBlank()) throw LibreTranslate.Failure(LibreTranslate.Problem.FAILED)
        answer = result.text
        card.showText(caption(result.detected, read.scope), result.text)
        setButtons(working = false)
    }

    /** The last language used, if the server still has it. */
    private fun lastTarget(): String? =
        OnlineSettings.lastTarget(prefs)?.takeIf { code -> languages.any { it.code == code } }

    private fun caption(detected: String?, scope: FieldText.Scope): String {
        val from = detected?.let { code -> languages.firstOrNull { it.code == code }?.name ?: code }
        val parts = listOfNotNull(
            from?.let { context.getString(R.string.fork_translate_from, it) },
            when (scope) {
                FieldText.Scope.SELECTION -> context.getString(R.string.fork_translate_selection)
                FieldText.Scope.AROUND_CURSOR -> context.getString(R.string.fork_translate_partial)
                FieldText.Scope.FIELD -> null
            },
        )
        return parts.joinToString(" · ")
    }

    private fun onAccept() {
        val t = target ?: return
        val a = answer ?: return
        if (ForkAi.accept(t, a)) {
            ForkPanels.close()
        } else {
            answer = null
            card.showMessage(R.string.fork_translate_changed)
            setButtons(working = false)
        }
    }

    private fun showProblem(problem: LibreTranslate.Problem) {
        answer = null
        val text = when (problem) {
            LibreTranslate.Problem.NOT_SET_UP -> R.string.fork_translate_err_setup
            LibreTranslate.Problem.UNREACHABLE -> R.string.fork_translate_err_unreachable
            LibreTranslate.Problem.KEY -> R.string.fork_translate_err_key
            LibreTranslate.Problem.BUSY -> R.string.fork_translate_err_busy
            LibreTranslate.Problem.TOO_LONG -> R.string.fork_translate_err_too_long
            LibreTranslate.Problem.FAILED -> R.string.fork_translate_err_failed
        }
        val needsSettings = problem == LibreTranslate.Problem.NOT_SET_UP || problem == LibreTranslate.Problem.KEY
        card.showMessage(text, if (needsSettings) R.string.fork_ai_action_pair else 0, onAction = if (needsSettings) { {
            ForkPanels.close()
            runCatching { context.startActivity(SettingsActivity.onlineIntent(context)) }
            Unit
        } } else null)
        setButtons(working = false)
        retry.isVisible = !needsSettings && problem != LibreTranslate.Problem.TOO_LONG
    }

    private fun setButtons(working: Boolean) {
        retry.isVisible = true
        PanelKit.setReady(retry, !working)
        PanelKit.setReady(accept, !working && answer != null)
    }
}

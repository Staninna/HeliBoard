// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork.ai

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SettingsActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.ConnectException

/**
 * Fork: the rewrite panel over the keys. Pick what to do with the chips at the top; SmolChat's
 * answer streams into the card below, and Replace puts it into the app's text field in one edit.
 */
@SuppressLint("ViewConstructor")
class RewriteView(context: Context, firstPrompt: RewritePrompts.Prompt?) : FrameLayout(context) {
    private val prefs = context.prefs()
    private val scope = MainScope()
    private var job: Job? = null

    private val prompts = RewritePrompts.all(prefs)
    private var prompt = firstPrompt ?: RewritePrompts.last(prefs)
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
        buildChips()
        doOnAttach {
            PanelKit.keepAboveNavBar(this)
            cancel.isVisible = !ForkPanels.hasTitleBar // the title bar's back arrow closes the panel
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope.cancel()
    }

    private fun buildChips() {
        chipRow.removeAllViews()
        for (p in prompts) {
            chipRow.addView(PanelKit.chip(context, p.name, p == prompt) {
                if (p == prompt && job?.isActive == true) return@chip
                prompt = p
                RewritePrompts.remember(prefs, p)
                buildChips()
                start()
            }, PanelKit.chipParams(context))
        }
    }

    /** Reads the field again and asks SmolChat for [prompt]. */
    private fun start() {
        job?.cancel()
        answer = null
        setButtons(working = true)
        val ic = ForkAi.inputConnection
        val read = ic?.let { FieldText.read(it) }
        target = read
        if (read == null) {
            card.showMessage(R.string.fork_ai_empty)
            setButtons(working = false)
            retry.isVisible = false
            return
        }
        card.showProgress(R.string.fork_ai_reading)
        job = scope.launch {
            try {
                run(read)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SmolChat.Failure) {
                showProblem(e.problem)
            } catch (e: ConnectException) {
                showProblem(SmolChat.Problem.NOT_RUNNING)
            } catch (e: Exception) {
                showProblem(SmolChat.Problem.FAILED)
            }
        }
    }

    private suspend fun run(read: FieldText.Target) {
        if (!SmolChat.isInstalled(context)) throw SmolChat.Failure(SmolChat.Problem.NOT_INSTALLED)
        val pairing = LocalAiPairing.get(context) ?: throw SmolChat.Failure(SmolChat.Problem.NOT_PAIRED)
        var health = SmolChat.health(pairing.baseUrl)
        if (health == null) {
            card.showProgress(R.string.fork_ai_starting)
            when (SmolChat.wake(context)) {
                SmolChat.Wake.STARTED -> Unit
                SmolChat.Wake.NOT_PAIRED -> throw SmolChat.Failure(SmolChat.Problem.NOT_PAIRED)
                SmolChat.Wake.NO_MODEL -> throw SmolChat.Failure(SmolChat.Problem.NO_MODEL)
                else -> throw SmolChat.Failure(SmolChat.Problem.NOT_RUNNING)
            }
            for (attempt in 1..WAKE_POLLS) {
                health = SmolChat.health(pairing.baseUrl)
                if (health != null) break
                delay(WAKE_POLL_MS)
            }
            if (health == null) throw SmolChat.Failure(SmolChat.Problem.NOT_RUNNING)
        }
        if (health == "no_model") throw SmolChat.Failure(SmolChat.Problem.NO_MODEL)
        card.showProgress(if (health == "loading" || health == "idle") R.string.fork_ai_loading else R.string.fork_ai_reading)

        // give up when nothing arrives for 90 seconds; once text flows, the stream's own timeout applies
        var started = false
        val watchdog = scope.launch {
            delay(FIRST_TOKEN_TIMEOUT_MS)
            if (!started) {
                job?.cancel()
                showProblem(null)
            }
        }
        val caption = when (read.scope) {
            FieldText.Scope.SELECTION -> context.getString(R.string.fork_ai_selection)
            FieldText.Scope.AROUND_CURSOR -> context.getString(R.string.fork_ai_partial)
            FieldText.Scope.FIELD -> prompt.name
        }
        try {
            SmolChat.stream(pairing, RewritePrompts.messages(prompt, read.text), RewritePrompts.maxTokens(read.text)).collect { text ->
                started = true
                card.showText(caption, RewritePrompts.clean(text))
            }
        } finally {
            watchdog.cancel()
        }
        val cleaned = RewritePrompts.clean(card.text.text.toString())
        if (cleaned.isEmpty()) throw SmolChat.Failure(SmolChat.Problem.FAILED)
        answer = cleaned
        setButtons(working = false)
    }

    private fun onAccept() {
        val t = target ?: return
        val a = answer ?: return
        if (ForkAi.accept(t, a)) {
            ForkPanels.close()
        } else {
            answer = null
            card.showMessage(R.string.fork_ai_changed)
            setButtons(working = false)
        }
    }

    /** [problem] null means the 90 seconds ran out. */
    private fun showProblem(problem: SmolChat.Problem?) {
        answer = null
        val text = when (problem) {
            null -> R.string.fork_ai_err_timeout
            SmolChat.Problem.NOT_INSTALLED -> R.string.fork_ai_err_not_installed
            SmolChat.Problem.NOT_PAIRED -> R.string.fork_ai_err_not_paired
            SmolChat.Problem.NO_MODEL -> R.string.fork_ai_err_no_model
            SmolChat.Problem.NOT_RUNNING -> R.string.fork_ai_err_not_running
            SmolChat.Problem.BUSY -> R.string.fork_ai_err_busy
            SmolChat.Problem.TOO_LONG -> R.string.fork_ai_err_too_long
            SmolChat.Problem.FAILED -> R.string.fork_ai_err_failed
        }
        val (label, intent) = when (problem) {
            SmolChat.Problem.NOT_INSTALLED -> R.string.fork_ai_action_get to
                Intent(Intent.ACTION_VIEW, Uri.parse(SERVER_RELEASES)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            SmolChat.Problem.NOT_PAIRED -> R.string.fork_ai_action_pair to SettingsActivity.localAiIntent(context)
            SmolChat.Problem.NO_MODEL, SmolChat.Problem.NOT_RUNNING -> R.string.fork_ai_action_open to SmolChat.launchIntent(context)
            else -> 0 to null
        }
        card.showMessage(text, label, onAction = intent?.let { { ForkPanels.close(); runCatching { context.startActivity(it) }; Unit } })
        setButtons(working = false)
        retry.isVisible = problem != SmolChat.Problem.NOT_INSTALLED && problem != SmolChat.Problem.NOT_PAIRED && problem != SmolChat.Problem.TOO_LONG
    }

    private fun setButtons(working: Boolean) {
        retry.isVisible = true
        PanelKit.setReady(retry, !working)
        PanelKit.setReady(accept, !working && answer != null)
    }

    private companion object {
        const val FIRST_TOKEN_TIMEOUT_MS = 90_000L
        const val WAKE_POLLS = 20
        const val WAKE_POLL_MS = 500L
        const val SERVER_RELEASES = "https://github.com/Staninna/SmolChatServer/releases"
    }
}

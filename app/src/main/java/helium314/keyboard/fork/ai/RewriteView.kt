// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork.ai

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.updatePadding
import helium314.keyboard.fork.ForkColors
import helium314.keyboard.fork.ForkPanels
import helium314.keyboard.fork.KeyGeometry
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.dpToPx
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
 * answer streams in below, and Replace puts it into the app's text field in one edit.
 */
@SuppressLint("ViewConstructor")
class RewriteView(context: Context, firstPrompt: RewritePrompts.Prompt?) : FrameLayout(context) {
    private val colors = Settings.getValues().mColors
    private val prefs = context.prefs()
    private val scope = MainScope()
    private var job: Job? = null

    private val prompts = RewritePrompts.all(prefs)
    private var prompt = firstPrompt ?: RewritePrompts.last(prefs)
    private var target: FieldText.Target? = null
    private var answer: String? = null

    private val chipRow = LinearLayout(context)
    private val scopeLine = label(13f, ColorType.KEY_HINT_TEXT)
    private val status = label(15f, ColorType.KEY_HINT_TEXT)
    private val result = label(17f, ColorType.KEY_TEXT).apply { setLineSpacing(0f, 1.15f); setTextIsSelectable(false) }
    private val action = pill(filled = false)
    private val retry = pill(filled = false)
    private val accept = pill(filled = true)

    init {
        setBackgroundColor(colors.get(ColorType.MAIN_BACKGROUND))
        isClickable = true // the keys below stay out of reach

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        chipRow.gravity = Gravity.CENTER_VERTICAL
        column.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(chipRow)
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 48.dp).apply { topMargin = 8.dp })

        column.addView(scopeLine, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 4.dp })

        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(status)
        body.addView(result)
        body.addView(action, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, 40.dp).apply { topMargin = 12.dp })
        column.addView(ScrollView(context).apply { isVerticalScrollBarEnabled = true; addView(body) },
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = 8.dp })

        val buttons = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
        buttons.addView(pill(filled = false).apply { setText(R.string.fork_ai_cancel); setOnClickListener { ForkPanels.close() } },
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, 48.dp))
        retry.setText(R.string.fork_ai_retry)
        retry.setOnClickListener { start() }
        buttons.addView(retry, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, 48.dp).apply { marginStart = 8.dp })
        accept.setText(R.string.fork_ai_accept)
        accept.setOnClickListener { onAccept() }
        buttons.addView(accept, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, 48.dp).apply { marginStart = 8.dp })
        column.addView(buttons, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 48.dp).apply { topMargin = 8.dp; bottomMargin = 8.dp })

        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        buildChips()
        doOnAttach {
            // line the content up with the outer key edges, and keep it above the navigation bar
            val keyboardView = KeyboardSwitcher.getInstance().visibleKeyboardView
            val keys = KeyGeometry.current()
            val side = if (keyboardView != null && keys != null) keyboardView.paddingLeft + keys.left else 12.dp
            val end = if (keyboardView != null && keys != null) keyboardView.paddingRight + keys.right else 12.dp
            val navBar = ViewCompat.getRootWindowInsets(it)?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
            updatePadding(left = side, right = end, bottom = navBar)
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
            val selected = p == prompt
            val accent = colors.get(ColorType.ACTION_KEY_BACKGROUND)
            val background = if (selected) accent else colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND)
            chipRow.addView(TextView(context).apply {
                text = p.name
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(ForkColors.readableOn(background, colors.get(ColorType.KEY_TEXT), colors.get(ColorType.MAIN_BACKGROUND)))
                setPadding(16.dp, 0, 16.dp, 0)
                this.background = GradientDrawable().apply { cornerRadius = 999f; setColor(background) }
                isSelected = selected
                contentDescription = p.name
                setOnClickListener {
                    if (p == prompt && job?.isActive == true) return@setOnClickListener
                    prompt = p
                    RewritePrompts.remember(prefs, p)
                    buildChips()
                    start()
                }
            }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, 40.dp).apply { marginEnd = 8.dp })
        }
    }

    /** Reads the field again and asks SmolChat for [prompt]. */
    private fun start() {
        job?.cancel()
        answer = null
        result.text = ""
        action.visibility = GONE
        setButtons(working = true)
        val ic = ForkAi.inputConnection
        val read = ic?.let { FieldText.read(it) }
        target = read
        if (read == null) {
            showStatus(R.string.fork_ai_empty)
            setButtons(working = false)
            retry.visibility = GONE
            return
        }
        scopeLine.visibility = if (read.scope == FieldText.Scope.FIELD) GONE else VISIBLE
        scopeLine.setText(if (read.scope == FieldText.Scope.SELECTION) R.string.fork_ai_selection else R.string.fork_ai_partial)
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
            showStatus(R.string.fork_ai_starting)
            when (SmolChat.wake(context)) {
                SmolChat.Wake.STARTED -> Unit
                SmolChat.Wake.NOT_PAIRED -> throw SmolChat.Failure(SmolChat.Problem.NOT_PAIRED)
                SmolChat.Wake.NO_MODEL -> throw SmolChat.Failure(SmolChat.Problem.NO_MODEL)
                else -> throw SmolChat.Failure(SmolChat.Problem.NOT_RUNNING)
            }
            repeat(20) {
                health = SmolChat.health(pairing.baseUrl)
                if (health != null) return@repeat
                delay(500)
            }
            if (health == null) throw SmolChat.Failure(SmolChat.Problem.NOT_RUNNING)
        }
        if (health == "no_model") throw SmolChat.Failure(SmolChat.Problem.NO_MODEL)
        showStatus(if (health == "loading" || health == "idle") R.string.fork_ai_loading else R.string.fork_ai_reading)

        // give up when nothing arrives for 90 seconds; once text flows, the stream's own timeout applies
        var started = false
        val watchdog = scope.launch {
            delay(FIRST_TOKEN_TIMEOUT_MS)
            if (!started) {
                job?.cancel()
                showProblem(null)
            }
        }
        try {
            SmolChat.stream(pairing, RewritePrompts.messages(prompt, read.text), RewritePrompts.maxTokens(read.text)).collect { text ->
                started = true
                status.visibility = GONE
                result.text = RewritePrompts.clean(text)
            }
        } finally {
            watchdog.cancel()
        }
        val cleaned = RewritePrompts.clean(result.text.toString())
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
            showStatus(R.string.fork_ai_changed)
            setButtons(working = false)
        }
    }

    private fun showStatus(text: Int) {
        status.setText(text)
        status.visibility = VISIBLE
    }

    /** [problem] null means the 90 seconds ran out. */
    private fun showProblem(problem: SmolChat.Problem?) {
        answer = null
        result.text = ""
        showStatus(when (problem) {
            null -> R.string.fork_ai_err_timeout
            SmolChat.Problem.NOT_INSTALLED -> R.string.fork_ai_err_not_installed
            SmolChat.Problem.NOT_PAIRED -> R.string.fork_ai_err_not_paired
            SmolChat.Problem.NO_MODEL -> R.string.fork_ai_err_no_model
            SmolChat.Problem.NOT_RUNNING -> R.string.fork_ai_err_not_running
            SmolChat.Problem.BUSY -> R.string.fork_ai_err_busy
            SmolChat.Problem.TOO_LONG -> R.string.fork_ai_err_too_long
            SmolChat.Problem.FAILED -> R.string.fork_ai_err_failed
        })
        val (label, intent) = when (problem) {
            SmolChat.Problem.NOT_INSTALLED -> R.string.fork_ai_action_get to
                Intent(Intent.ACTION_VIEW, Uri.parse(SERVER_RELEASES)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            SmolChat.Problem.NOT_PAIRED -> R.string.fork_ai_action_pair to SettingsActivity.localAiIntent(context)
            SmolChat.Problem.NO_MODEL, SmolChat.Problem.NOT_RUNNING -> R.string.fork_ai_action_open to SmolChat.launchIntent(context)
            else -> 0 to null
        }
        if (intent != null) {
            action.setText(label)
            action.setOnClickListener {
                ForkPanels.close()
                runCatching { context.startActivity(intent) }
            }
            action.visibility = VISIBLE
        }
        setButtons(working = false)
        retry.visibility = if (problem == SmolChat.Problem.NOT_INSTALLED || problem == SmolChat.Problem.NOT_PAIRED || problem == SmolChat.Problem.TOO_LONG) GONE else VISIBLE
    }

    private fun setButtons(working: Boolean) {
        retry.visibility = if (working) GONE else VISIBLE
        val ready = !working && answer != null
        accept.isEnabled = ready
        accept.alpha = if (ready) 1f else 0.38f
    }

    private fun label(size: Float, color: ColorType) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(colors.get(color))
    }

    /** M3 button: filled in the accent color, or tonal. */
    private fun pill(filled: Boolean) = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val background = colors.get(if (filled) ColorType.ACTION_KEY_BACKGROUND else ColorType.FUNCTIONAL_KEY_BACKGROUND)
        setTextColor(ForkColors.readableOn(background, colors.get(ColorType.KEY_TEXT), colors.get(ColorType.MAIN_BACKGROUND)))
        setPadding(20.dp, 0, 20.dp, 0)
        this.background = GradientDrawable().apply { cornerRadius = 999f; setColor(background) }
        minWidth = 64.dp
    }

    private val Int.dp get() = dpToPx(resources)

    private companion object {
        const val FIRST_TOKEN_TIMEOUT_MS = 90_000L
        const val SERVER_RELEASES = "https://github.com/Staninna/SmolChatServer/releases"
    }
}

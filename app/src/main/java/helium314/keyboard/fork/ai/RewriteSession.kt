// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork.ai

import android.content.Context
import android.view.inputmethod.EditorInfo
import helium314.keyboard.latin.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.ConnectException

/**
 * Fork: one rewrite, from the request to the answer. It lives outside the rewrite panel, so the
 * keyboard can hide (another app, the screen going off) without losing the work: the panel comes
 * back on the same text field and shows where the rewrite is. Closing the panel or starting another
 * rewrite ends it; visiting other fields in between doesn't.
 */
object RewriteSession {
    sealed interface State {
        /** Waiting, with what the keyboard is waiting for. */
        data class Working(val message: Int) : State
        /** SmolChat's answer so far; [done] once the stream ended. */
        data class Answer(val caption: String, val text: String, val done: Boolean) : State
        /** [problem] null means nothing arrived within 90 seconds. */
        data class Failed(val problem: SmolChat.Problem?) : State
    }

    private val scope = MainScope()
    private var job: Job? = null
    private var field: String? = null

    private val mutableState = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = mutableState

    var prompt: RewritePrompts.Prompt? = null
        private set
    var target: FieldText.Target? = null
        private set

    val isWorking get() = when (val s = state.value) {
        is State.Working -> true
        is State.Answer -> !s.done
        else -> false
    }

    /** The finished answer, ready to replace the text with. */
    val answer get() = (state.value as? State.Answer)?.takeIf { it.done }?.text

    /** A rewrite was started and hasn't been closed. */
    val isActive get() = field != null

    /** True when a rewrite was started on [info]'s field and hasn't been closed. */
    fun belongsTo(info: EditorInfo?) = (field != null && field == key(info))

    /** Starts [prompt] on [read], replacing any rewrite still running. */
    fun start(context: Context, prompt: RewritePrompts.Prompt, read: FieldText.Target, info: EditorInfo?) {
        job?.cancel()
        this.prompt = prompt
        target = read
        field = key(info)
        mutableState.value = State.Working(R.string.fork_ai_reading)
        val app = context.applicationContext
        job = scope.launch {
            try {
                run(app, prompt, read)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SmolChat.Failure) {
                mutableState.value = State.Failed(e.problem)
            } catch (e: ConnectException) {
                mutableState.value = State.Failed(SmolChat.Problem.NOT_RUNNING)
            } catch (e: Exception) {
                mutableState.value = State.Failed(SmolChat.Problem.FAILED)
            }
        }
    }

    /** Stops the rewrite and forgets it. */
    fun end() {
        job?.cancel()
        job = null
        field = null
        prompt = null
        target = null
        mutableState.value = null
    }

    private suspend fun run(context: Context, prompt: RewritePrompts.Prompt, read: FieldText.Target) {
        if (!SmolChat.isInstalled(context)) throw SmolChat.Failure(SmolChat.Problem.NOT_INSTALLED)
        val pairing = LocalAiPairing.get(context) ?: throw SmolChat.Failure(SmolChat.Problem.NOT_PAIRED)
        var health = SmolChat.health(pairing.baseUrl)
        if (health == null) {
            mutableState.value = State.Working(R.string.fork_ai_starting)
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
        mutableState.value = State.Working(if (health == "loading" || health == "idle") R.string.fork_ai_loading else R.string.fork_ai_reading)

        // give up when nothing arrives for 90 seconds; once text flows, the stream's own timeout applies
        var started = false
        val running = job
        val watchdog = scope.launch {
            delay(FIRST_TOKEN_TIMEOUT_MS)
            if (!started) {
                running?.cancel()
                mutableState.value = State.Failed(null)
            }
        }
        val caption = when (read.scope) {
            FieldText.Scope.SELECTION -> context.getString(R.string.fork_ai_selection)
            FieldText.Scope.AROUND_CURSOR -> context.getString(R.string.fork_ai_partial)
            FieldText.Scope.FIELD -> prompt.name
        }
        var text = ""
        try {
            SmolChat.stream(pairing, RewritePrompts.messages(prompt, read.text), RewritePrompts.maxTokens(read.text)).collect {
                started = true
                text = RewritePrompts.answerFor(read.text, it)
                mutableState.value = State.Answer(caption, text, done = false)
            }
        } finally {
            watchdog.cancel()
        }
        if (text.isEmpty()) throw SmolChat.Failure(SmolChat.Problem.FAILED)
        mutableState.value = State.Answer(caption, text, done = true)
    }

    private fun key(info: EditorInfo?) = info?.let { "${it.packageName}/${it.fieldId}/${it.inputType}" }

    private const val FIRST_TOKEN_TIMEOUT_MS = 90_000L
    private const val WAKE_POLLS = 20
    private const val WAKE_POLL_MS = 500L
}

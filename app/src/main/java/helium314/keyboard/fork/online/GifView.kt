// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork.online

import android.annotation.SuppressLint
import android.content.ClipDescription
import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.Outline
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.view.doOnAttach
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import helium314.keyboard.fork.ForkPanels
import helium314.keyboard.fork.PanelCard
import helium314.keyboard.fork.PanelKit
import helium314.keyboard.fork.PanelKit.dp
import helium314.keyboard.fork.ai.ForkAi
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.settings.SettingsActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * Fork: GIFs from KLIPY over the keys. The search pill at the top opens [GifSearch], which types
 * into it with the keyboard; the grid shows trending GIFs or the results. A tap sends the GIF to the
 * app, the same way the clipboard sends images.
 */
@SuppressLint("ViewConstructor")
class GifView(context: Context, private val query: String) : FrameLayout(context) {
    private val scope = MainScope()
    private val colors = Settings.getValues().mColors
    private val gifs = mutableListOf<Klipy.Gif>()
    /** Previews already downloaded while this panel is open, so scrolling back needs no new request. */
    private val previews = HashMap<String, ByteArray>()
    private var page = 0
    private var hasNext = true
    private var loading: Job? = null
    private var sending: Job? = null

    private val grid = RecyclerView(context)
    private val card = PanelCard(context)
    private val adapter = Adapter()
    private val spans = if (resources.configuration.screenWidthDp >= 600) 4 else 3

    init {
        setBackgroundColor(colors.get(ColorType.MAIN_BACKGROUND))
        isClickable = true
        val margin = PanelKit.MARGIN_DP.dp(context)

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(searchPill(), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 40.dp(context))
            .apply { setMargins(margin, 8.dp(context), margin, 0) })

        val body = FrameLayout(context)
        grid.layoutManager = StaggeredGridLayoutManager(spans, StaggeredGridLayoutManager.VERTICAL)
        grid.adapter = adapter
        grid.clipToPadding = false
        grid.setPadding(margin - GAP_DP.dp(context) / 2, GAP_DP.dp(context) / 2, margin - GAP_DP.dp(context) / 2, margin)
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!recyclerView.canScrollVertically(1)) loadMore()
            }
        })
        body.addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        body.addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply { setMargins(margin, 0, margin, margin) })
        column.addView(body, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = 8.dp(context) })
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        doOnAttach {
            PanelKit.keepAboveNavBar(this)
            loadMore()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope.cancel()
        previews.clear()
    }

    /** Looks like the emoji search bar; tapping it types the search with the keyboard. */
    private fun searchPill(): View = TextView(context).apply {
        val background = colors.get(ColorType.EMOJI_SEARCH_BACKGROUND)
        val textColor = colors.get(ColorType.EMOJI_SEARCH_TEXT)
        text = query.ifBlank { context.getString(R.string.fork_gif_search_hint) }
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        // the placeholder dimmed like in the search bar, a search in full color
        setTextColor(if (query.isBlank()) androidx.core.graphics.ColorUtils.blendARGB(background, textColor, 0.65f) else textColor)
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        setPadding(16.dp(context), 0, 16.dp(context), 0)
        PanelKit.setIcon(this, R.drawable.sym_keyboard_search_rounded, textColor, 24)
        compoundDrawablePadding = 12.dp(context)
        this.background = GradientDrawable().apply { cornerRadius = 999f; setColor(background) }
        contentDescription = context.getString(R.string.fork_gif_search_hint)
        setOnClickListener { GifSearch.start(context, query) }
    }

    private fun loadMore() {
        if (loading?.isActive == true || sending?.isActive == true || !hasNext) return
        if (gifs.isEmpty()) card.showProgress(0)
        loading = scope.launch {
            try {
                val on = OnlineSettings.gifsOn(context)
                val key = OnlineSettings.gifKey(context)
                if (!on || key.isBlank()) throw Klipy.Failure(Klipy.Problem.NOT_SET_UP)
                val next = Klipy.page(key, query, page + 1, on)
                page++
                hasNext = next.hasNext && next.gifs.isNotEmpty()
                val start = gifs.size
                gifs += next.gifs
                adapter.notifyItemRangeInserted(start, next.gifs.size)
                if (gifs.isEmpty()) card.showMessage(R.string.fork_gif_none) else card.isVisible = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Klipy.Failure) {
                if (gifs.isEmpty()) showProblem(e.problem)
            } catch (e: Exception) {
                if (gifs.isEmpty()) showProblem(Klipy.Problem.FAILED)
            }
        }
    }

    private fun showProblem(problem: Klipy.Problem) {
        card.isVisible = true
        val text = when (problem) {
            Klipy.Problem.NOT_SET_UP -> R.string.fork_gif_err_setup
            Klipy.Problem.KEY -> R.string.fork_gif_err_key
            Klipy.Problem.UNREACHABLE -> R.string.fork_gif_err_unreachable
            Klipy.Problem.BUSY -> R.string.fork_gif_err_busy
            Klipy.Problem.FAILED -> R.string.fork_gif_err_failed
        }
        if (problem == Klipy.Problem.NOT_SET_UP || problem == Klipy.Problem.KEY) {
            card.showMessage(text, R.string.fork_ai_action_pair) {
                ForkPanels.close()
                runCatching { context.startActivity(SettingsActivity.onlineIntent(context)) }
            }
        } else {
            card.showMessage(text, R.string.fork_ai_retry) { loadMore() }
        }
    }

    /** Downloads [gif] and hands it to the app. */
    private fun send(gif: Klipy.Gif) {
        if (sending?.isActive == true) return
        val accepted = ForkAi.currentEditorInfo?.let { EditorInfoCompat.getContentMimeTypes(it) }.orEmpty()
        // an app that lists the content it takes but no GIFs would ignore it; an app that lists nothing gets it pasted
        if (accepted.isNotEmpty() && accepted.none { ClipDescription.compareMimeTypes(GIF_MIME, it) }) {
            card.showMessage(R.string.fork_gif_not_supported, R.string.fork_ai_cancel) { card.isVisible = false }
            card.isVisible = true
            return
        }
        card.showProgress(R.string.fork_gif_sending)
        card.isVisible = true
        sending = scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { GifFiles.fresh(context, gif.slug) }
                Klipy.download(gif.send, file, OnlineSettings.gifsOn(context))
                val uri = FileProvider.getUriForFile(context, GifFiles.authority(context), file)
                val content = InputContentInfoCompat(uri, ClipDescription(gif.title.ifBlank { "GIF" }, arrayOf(GIF_MIME)), null)
                val listener = KeyboardSwitcher.getInstance().wrapperView?.keyboardActionListener
                ForkPanels.close()
                listener?.onContent(content)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                card.showMessage(R.string.fork_gif_err_send, R.string.fork_ai_retry) { send(gif) }
            }
        }
    }

    private inner class Holder(val image: ImageView) : RecyclerView.ViewHolder(image) {
        var job: Job? = null
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = gifs.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val gap = GAP_DP.dp(context)
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { setColor(PanelKit.cardColor) }
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) =
                        outline.setRoundRect(0, 0, view.width, view.height, 12.dp(context).toFloat())
                }
                clipToOutline = true
                layoutParams = StaggeredGridLayoutManager.LayoutParams(LayoutParams.MATCH_PARENT, 100.dp(context))
                    .apply { setMargins(gap / 2, gap / 2, gap / 2, gap / 2) }
            }
            return Holder(image)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val gif = gifs[position]
            val gap = GAP_DP.dp(context)
            val columnWidth = (grid.width - grid.paddingLeft - grid.paddingRight) / spans - gap
            holder.image.layoutParams = holder.image.layoutParams.apply {
                height = (columnWidth.toLong() * gif.preview.height / gif.preview.width).toInt().coerceIn(60.dp(context), columnWidth * 2)
            }
            holder.image.contentDescription = gif.title.ifBlank { context.getString(R.string.fork_gif) }
            holder.image.setImageDrawable(null)
            holder.image.setOnClickListener { send(gif) }
            holder.job?.cancel()
            holder.job = scope.launch {
                try {
                    val bytes = previews[gif.slug] ?: Klipy.bytes(gif.preview, OnlineSettings.gifsOn(context)).also { previews[gif.slug] = it }
                    val drawable = withContext(Dispatchers.Default) {
                        ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                            // decode no bigger than the cell
                            val scale = columnWidth.toFloat() / info.size.width
                            if (scale < 1f) decoder.setTargetSize(columnWidth, (info.size.height * scale).toInt().coerceAtLeast(1))
                        }
                    }
                    holder.image.setImageDrawable(drawable)
                    (drawable as? AnimatedImageDrawable)?.start()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // the cell keeps its placeholder color
                }
            }
        }

        override fun onViewRecycled(holder: Holder) {
            holder.job?.cancel()
            (holder.image.drawable as? AnimatedImageDrawable)?.stop()
            holder.image.setImageDrawable(null)
        }
    }

    private companion object {
        const val GIF_MIME = "image/gif"
        const val GAP_DP = 6
    }
}

/**
 * Fork: the GIF being sent, in a cache folder only the GIF provider shares. Only the last one is
 * kept: the app may read it a moment after the keyboard sent it.
 */
object GifFiles {
    private const val DIR = "fork_gifs"

    /** Matches the provider in the manifest, `${applicationId}.fork.gifs`. */
    fun authority(context: Context) = context.packageName + ".fork.gifs"

    fun fresh(context: Context, slug: String): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        return File(dir, slug.filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "gif" } + ".gif")
    }
}

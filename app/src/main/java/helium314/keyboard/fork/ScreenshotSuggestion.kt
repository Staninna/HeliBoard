// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork

import android.Manifest
import android.content.ClipDescription
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import helium314.keyboard.fork.ai.ForkAi
import helium314.keyboard.fork.online.GifFiles
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.databinding.ClipboardSuggestionBinding
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.suggestions.SuggestionStripView
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.dpToPx
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.preferences.SwitchPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference

/**
 * Fork: offers a screenshot taken in the last 10 minutes in the empty suggestion bar, where the
 * clipboard suggestion shows, with the same look. Tapping it sends the image to the app; the close
 * button dismisses it. Only in fields that take images, never in password or incognito fields.
 * Needs the photo permission, asked for when the setting is turned on.
 */
object ScreenshotSuggestion {
    private const val WINDOW_MS = 10 * 60 * 1000L
    private const val DIR = "fork_screens" // matches xml/fork_gif_provider_path.xml
    /** Id of the last screenshot sent or dismissed, so it isn't offered again. */
    private const val PREF_HANDLED = "fork_screenshot_handled"

    private class Shot(val id: Long, val uri: Uri, val mime: String, val addedMs: Long)

    private var ime = WeakReference<LatinIME>(null)
    private var info: EditorInfo? = null
    private var latest: Shot? = null
    private var thumbnail: Bitmap? = null
    private val scope = MainScope()
    private var query: Job? = null
    private var observer: ContentObserver? = null

    private val permission
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun allowed(context: Context) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun enabled(context: Context) =
        context.prefs().getBoolean(ForkSettings.PREF_SCREENSHOT_SUGGESTION, ForkSettings.DEFAULT_SCREENSHOT_SUGGESTION) && allowed(context)

    /** The keyboard came up: look for a new screenshot, and keep looking while it is open. */
    fun onShown(context: Context, info: EditorInfo?) {
        val service = context as? LatinIME ?: return
        ime = WeakReference(service)
        this.info = info
        if (!enabled(service)) {
            stopWatching(service)
            latest = null
            thumbnail = null
            return
        }
        if (observer == null) {
            observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = refresh()
            }.also { service.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, it) }
        }
        refresh()
    }

    fun onHidden() {
        ime.get()?.let { stopWatching(it) }
        query?.cancel()
    }

    private fun stopWatching(context: Context) {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
    }

    private fun refresh() {
        val service = ime.get() ?: return
        query?.cancel()
        query = scope.launch {
            val shot = withContext(Dispatchers.IO) { runCatching { newest(service.contentResolver) }.getOrNull() }
            if (shot?.id == latest?.id) return@launch
            thumbnail = shot?.let {
                withContext(Dispatchers.IO) { runCatching { service.contentResolver.loadThumbnail(it.uri, Size(256, 256), null) }.getOrNull() }
            }
            latest = shot
            // show it right away, but only in an empty bar, never over the suggestions for a word being typed
            val typing = service.currentInputConnection?.getTextBeforeCursor(1, 0)?.isNotEmpty() ?: true
            if (shot != null && offered(service, shot) && !typing) service.setNeutralSuggestionStrip()
        }
    }

    private fun newest(resolver: ContentResolver): Shot? {
        val since = (System.currentTimeMillis() - WINDOW_MS) / 1000
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Images.Media.DATE_ADDED} >= ? AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(since.toString(), "%Screenshots%"))
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
        }
        val columns = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.MIME_TYPE)
        resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, columns, args, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val id = c.getLong(0)
            return Shot(
                id,
                Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()),
                c.getString(2) ?: "image/png",
                c.getLong(1) * 1000,
            )
        }
        return null
    }

    /** Recent, not sent or dismissed yet, and the field takes this kind of image. */
    private fun offered(context: Context, shot: Shot): Boolean {
        if (System.currentTimeMillis() - shot.addedMs > WINDOW_MS) return false
        if (context.prefs().getLong(PREF_HANDLED, -1) == shot.id) return false
        val field = info ?: return false
        if (ForkAi.isPrivate(field)) return false
        return EditorInfoCompat.getContentMimeTypes(field).any { ClipDescription.compareMimeTypes(shot.mime, it) }
    }

    /** Shows the screenshot in [strip] instead of an empty bar. Returns false when there is none to offer. */
    @JvmStatic
    fun showInstead(strip: SuggestionStripView?): Boolean {
        val service = ime.get() ?: return false
        val shot = latest ?: return false
        if (strip == null || !enabled(service) || !offered(service, shot)) return false

        val binding = ClipboardSuggestionBinding.inflate(LayoutInflater.from(service), strip, false)
        binding.root.updatePaddingRelative(start = 6.dpToPx(service.resources))
        binding.clipboardSuggestionText.isGone = true
        binding.clipboardSuggestionImage.apply {
            isVisible = true
            thumbnail?.let { setImageBitmap(it) } ?: setImageURI(shot.uri)
            contentDescription = service.getString(R.string.fork_screenshot_send)
            setOnClickListener { send(service, shot) }
        }
        binding.clipboardSuggestionClose.apply {
            setImageDrawable(KeyboardIconsSet.instance.getIconDrawable(ToolbarKey.CLOSE_HISTORY.name.lowercase()))
            scaleX = 0.8f
            scaleY = 0.8f
            setOnClickListener {
                handled(service, shot)
                service.setNeutralSuggestionStrip()
            }
        }
        val colors = Settings.getValues().mColors
        colors.setColor(binding.clipboardSuggestionClose, ColorType.REMOVE_SUGGESTION_ICON)
        colors.setBackground(binding.root, ColorType.CLIPBOARD_SUGGESTION_BACKGROUND)
        strip.setExternalSuggestionView(binding.root, false)
        return true
    }

    private fun handled(context: Context, shot: Shot) = context.prefs().edit { putLong(PREF_HANDLED, shot.id) }

    /** Copies the screenshot into the cache and hands it to the app, the same way GIFs go out. */
    private fun send(service: LatinIME, shot: Shot) {
        handled(service, shot)
        service.setNeutralSuggestionStrip()
        scope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(service.cacheDir, DIR).apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(shot.mime) ?: "png"
                    val file = File(dir, "screenshot_${shot.id}.$ext")
                    service.contentResolver.openInputStream(shot.uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                        ?: error("screenshot is gone")
                    FileProvider.getUriForFile(service, GifFiles.authority(service), file)
                }.getOrNull()
            }
            if (uri == null) {
                Toast.makeText(service, R.string.fork_screenshot_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val content = InputContentInfoCompat(uri, ClipDescription(service.getString(R.string.fork_screenshot), arrayOf(shot.mime)), null)
            KeyboardSwitcher.getInstance().wrapperView?.keyboardActionListener?.onContent(content)
        }
    }

    /** The switch in Text correction, next to the clipboard suggestion. Turning it on asks for the photo permission. */
    @JvmStatic
    fun setting(context: Context) = Setting(
        context, ForkSettings.PREF_SCREENSHOT_SUGGESTION, R.string.fork_screenshot_suggestion, R.string.fork_screenshot_suggestion_summary
    ) { setting ->
        val activity = LocalContext.current.getActivity() ?: return@Setting
        var granted by remember { mutableStateOf(allowed(activity)) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            granted = it
            if (granted) activity.prefs().edit { putBoolean(setting.key, true) }
        }
        SwitchPreference(setting, ForkSettings.DEFAULT_SCREENSHOT_SUGGESTION,
            allowCheckedChange = {
                if (it && !granted) {
                    launcher.launch(permission)
                    false
                } else true
            }
        )
    }
}

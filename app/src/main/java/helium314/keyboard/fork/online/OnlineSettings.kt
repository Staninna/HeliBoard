// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork.online

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.latin.utils.prefs
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Fork: settings of the two features that use the internet, translate and GIF search. Both are off
 * until the user turns them on. Keys live in the no-backup folder, so they are never part of an
 * Android backup or of HeliBoard's settings export.
 */
object OnlineSettings {
    const val PREF_TRANSLATE = "fork_translate"
    const val PREF_TRANSLATE_SERVER = "fork_translate_server"
    private const val PREF_TRANSLATE_LANGUAGES = "fork_translate_languages"
    private const val PREF_TRANSLATE_TARGET = "fork_translate_recent"
    const val PREF_GIFS = "fork_gifs"

    private const val TRANSLATE_KEY_FILE = "fork_translate_key.txt"
    private const val GIF_KEY_FILE = "fork_gif_key.txt"

    private val json = Json { ignoreUnknownKeys = true }

    fun translateOn(context: Context) = context.prefs().getBoolean(PREF_TRANSLATE, false)
    fun translateServer(context: Context) = context.prefs().getString(PREF_TRANSLATE_SERVER, "").orEmpty()
    fun translateKey(context: Context) = readKey(context, TRANSLATE_KEY_FILE)
    fun setTranslateKey(context: Context, key: String) = writeKey(context, TRANSLATE_KEY_FILE, key)

    fun gifsOn(context: Context) = context.prefs().getBoolean(PREF_GIFS, false)
    fun gifKey(context: Context) = readKey(context, GIF_KEY_FILE)
    fun setGifKey(context: Context, key: String) = writeKey(context, GIF_KEY_FILE, key)

    /** The server's languages from the last successful check, so the panel opens without a request. */
    fun languages(prefs: SharedPreferences): List<LibreTranslate.Language> =
        runCatching { json.decodeFromString<List<LibreTranslate.Language>>(prefs.getString(PREF_TRANSLATE_LANGUAGES, "").orEmpty()) }
            .getOrDefault(emptyList())

    fun saveLanguages(prefs: SharedPreferences, languages: List<LibreTranslate.Language>) = prefs.edit {
        putString(PREF_TRANSLATE_LANGUAGES, json.encodeToString(kotlinx.serialization.builtins.ListSerializer(LibreTranslate.Language.serializer()), languages))
    }

    /** The target language picked last. Older versions kept a comma-separated list, newest first. */
    fun lastTarget(prefs: SharedPreferences): String? =
        prefs.getString(PREF_TRANSLATE_TARGET, "").orEmpty().substringBefore(',').takeIf { it.isNotBlank() }

    fun setLastTarget(prefs: SharedPreferences, code: String) = prefs.edit { putString(PREF_TRANSLATE_TARGET, code) }

    /** The targets to offer, always by name, so the chips stay where they are when one is picked. */
    fun targetOrder(languages: List<LibreTranslate.Language>): List<LibreTranslate.Language> =
        languages.sortedBy { it.name.lowercase() }

    private fun readKey(context: Context, name: String) = runCatching { file(context, name).readText().trim() }.getOrDefault("")

    private fun writeKey(context: Context, name: String, key: String) {
        val f = file(context, name)
        if (key.isBlank()) f.delete() else f.writeText(key.trim())
    }

    private fun file(context: Context, name: String) = File(context.applicationContext.noBackupFilesDir, name)
}

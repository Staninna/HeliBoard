// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork.ai

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Fork: the pairing with SmolChat Server. The token lives in the app's no-backup folder, so it is
 * never part of an Android backup or of HeliBoard's settings export.
 */
object LocalAiPairing {
    @Serializable
    data class Pairing(val token: String, val baseUrl: String)

    private const val FILE = "fork_local_ai_pairing.json"
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var cached: Pairing? = null
    @Volatile private var loaded = false

    fun get(context: Context): Pairing? {
        if (!loaded) {
            cached = runCatching { json.decodeFromString<Pairing>(file(context).readText()) }.getOrNull()
            loaded = true
        }
        return cached
    }

    fun isPaired(context: Context) = get(context) != null

    fun save(context: Context, pairing: Pairing) {
        file(context).writeText(json.encodeToString(Pairing.serializer(), pairing))
        cached = pairing
        loaded = true
    }

    fun clear(context: Context) {
        file(context).delete()
        cached = null
        loaded = true
    }

    private fun file(context: Context) = File(context.applicationContext.noBackupFilesDir, FILE)
}

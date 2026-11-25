// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork.online

import helium314.keyboard.fork.net.NetworkGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder

/**
 * Fork: GIF search with KLIPY (https://docs.klipy.com), with the user's own key. Sends only the
 * search words and the page; no user id, no share tracking, no ads.
 */
object Klipy {
    private const val API = "https://api.klipy.com/api/v1/"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 20_000
    const val PER_PAGE = 24
    /** Bigger files take long to send and some apps refuse them. */
    private const val MAX_SEND_BYTES = 6_000_000L

    private val json = Json { ignoreUnknownKeys = true }

    data class Media(val url: String, val width: Int, val height: Int, val size: Long)
    data class Gif(val slug: String, val title: String, val preview: Media, val send: Media)
    data class Page(val gifs: List<Gif>, val hasNext: Boolean)

    enum class Problem { NOT_SET_UP, KEY, UNREACHABLE, BUSY, FAILED }

    class Failure(val problem: Problem, message: String? = null) : IOException(message ?: problem.name)

    /** Trending when [query] is blank, search results otherwise. */
    suspend fun page(key: String, query: String, page: Int, enabled: Boolean): Page = withContext(Dispatchers.IO) {
        if (key.isBlank()) throw Failure(Problem.NOT_SET_UP)
        val path = if (query.isBlank()) "gifs/trending" else "gifs/search"
        val q = if (query.isBlank()) "" else "&q=" + URLEncoder.encode(query.trim(), "UTF-8")
        val url = API + URLEncoder.encode(key.trim(), "UTF-8") + "/" + path + "?page=$page&per_page=$PER_PAGE&content_filter=medium" + q
        parsePage(String(get(url, enabled)))
    }

    /** Downloads [media] into [file]. */
    suspend fun download(media: Media, file: File, enabled: Boolean) = withContext(Dispatchers.IO) {
        file.writeBytes(get(media.url, enabled))
    }

    /** The bytes of a preview, for the grid. */
    suspend fun bytes(media: Media, enabled: Boolean): ByteArray = withContext(Dispatchers.IO) { get(media.url, enabled) }

    private fun get(url: String, enabled: Boolean): ByteArray {
        val c = try {
            NetworkGate.open(NetworkGate.Feature.GIFS, url, enabled)
        } catch (e: NetworkGate.Refused) {
            throw Failure(Problem.NOT_SET_UP, e.message)
        }
        try {
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = READ_TIMEOUT_MS
            val code = try {
                c.responseCode
            } catch (e: IOException) {
                throw Failure(Problem.UNREACHABLE, e.message)
            }
            if (code != HttpURLConnection.HTTP_OK) throw failureFor(code, c.errorStream?.bufferedReader()?.readText())
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    fun parsePage(body: String): Page {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw Failure(Problem.FAILED, "unexpected answer")
        }
        if (root["result"]?.jsonPrimitive?.booleanOrNull == false) throw Failure(Problem.FAILED, root["errors"]?.toString())
        val data = root["data"] as? JsonObject ?: throw Failure(Problem.FAILED, "no data")
        val items = (data["data"] as? JsonArray).orEmpty()
        val gifs = items.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            if (o["type"]?.jsonPrimitive?.contentOrNull == "ad") return@mapNotNull null // ads stay out
            val file = o["file"] as? JsonObject ?: return@mapNotNull null
            val preview = media(file, "sm", "webp") ?: media(file, "sm", "gif") ?: media(file, "xs", "gif") ?: return@mapNotNull null
            val send = listOf("md", "sm", "xs").firstNotNullOfOrNull { size ->
                media(file, size, "gif")?.takeIf { it.size <= MAX_SEND_BYTES } // 0: size not given
            } ?: return@mapNotNull null
            Gif(o["slug"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null, o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(), preview, send)
        }
        return Page(gifs, data["has_next"]?.jsonPrimitive?.booleanOrNull == true)
    }

    private fun media(file: JsonObject, size: String, format: String): Media? {
        val m = (file[size] as? JsonObject)?.get(format) as? JsonObject ?: return null
        val url = m["url"]?.jsonPrimitive?.contentOrNull ?: return null
        val width = m["width"]?.jsonPrimitive?.intOrNull ?: return null
        val height = m["height"]?.jsonPrimitive?.intOrNull ?: return null
        if (width <= 0 || height <= 0) return null
        return Media(url, width, height, m["size"]?.jsonPrimitive?.longOrNull ?: 0L)
    }

    fun failureFor(status: Int, body: String?): Failure {
        val invalidKey = runCatching { json.parseToJsonElement(body ?: "").jsonObject["errors"]?.jsonObject?.get("message")?.jsonArray?.toString() }
            .getOrNull().orEmpty().contains("api key", ignoreCase = true)
        return when {
            invalidKey || status == 401 || status == 403 -> Failure(Problem.KEY)
            status == 429 -> Failure(Problem.BUSY)
            else -> Failure(Problem.FAILED, "HTTP $status")
        }
    }
}

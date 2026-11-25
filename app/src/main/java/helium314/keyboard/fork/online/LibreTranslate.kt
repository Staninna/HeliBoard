// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork.online

import helium314.keyboard.fork.net.NetworkGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection

/**
 * Fork: client of a LibreTranslate server the user chose (https://docs.libretranslate.com).
 * Only the text being translated and the optional API key are sent.
 */
object LibreTranslate {
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 30_000

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Serializable
    data class Language(val code: String, val name: String, val targets: List<String> = emptyList())

    data class Translation(val text: String, val detected: String?)

    /** What went wrong, each with one thing the user can do about it. */
    enum class Problem { NOT_SET_UP, UNREACHABLE, KEY, BUSY, TOO_LONG, FAILED }

    class Failure(val problem: Problem, message: String? = null) : IOException(message ?: problem.name)

    @Serializable
    private data class Request(
        val q: String,
        val source: String,
        val target: String,
        val format: String = "text",
        @SerialName("api_key") val apiKey: String? = null,
    )

    @Serializable
    private data class Detected(val language: String? = null)

    @Serializable
    private data class Response(
        val translatedText: String? = null,
        val detectedLanguage: Detected? = null,
    )

    /** `GET /languages`: the languages the server translates from, each with its targets. */
    suspend fun languages(server: String, enabled: Boolean): List<Language> = withContext(Dispatchers.IO) {
        request(server, "/languages", enabled) { it }.let(::parseLanguages)
    }

    /** `POST /translate`, with the source language detected by the server. */
    suspend fun translate(server: String, apiKey: String?, text: String, target: String, enabled: Boolean): Translation =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(Request.serializer(), Request(text, "auto", target, apiKey = apiKey?.takeIf { it.isNotBlank() }))
            parseTranslation(request(server, "/translate", enabled, body) { it })
        }

    private fun <T> request(server: String, path: String, enabled: Boolean, body: String? = null, read: (String) -> T): T {
        if (server.isBlank()) throw Failure(Problem.NOT_SET_UP)
        val c = try {
            NetworkGate.open(NetworkGate.Feature.TRANSLATE, server.trimEnd('/') + path, enabled)
        } catch (e: NetworkGate.Refused) {
            throw Failure(Problem.NOT_SET_UP, e.message)
        }
        try {
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = READ_TIMEOUT_MS
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = try {
                c.responseCode
            } catch (e: IOException) {
                throw Failure(Problem.UNREACHABLE, e.message)
            }
            if (code != HttpURLConnection.HTTP_OK) throw failureFor(code, c.errorStream?.bufferedReader()?.readText())
            return read(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }

    fun parseLanguages(body: String): List<Language> = try {
        json.decodeFromString<List<Language>>(body).filter { it.code.isNotBlank() }
    } catch (e: Exception) {
        throw Failure(Problem.FAILED, "not a LibreTranslate server")
    }

    fun parseTranslation(body: String): Translation {
        val response = try {
            json.decodeFromString(Response.serializer(), body)
        } catch (e: Exception) {
            throw Failure(Problem.FAILED, "unexpected answer")
        }
        val text = response.translatedText ?: throw Failure(Problem.FAILED, "no translation")
        return Translation(text, response.detectedLanguage?.language)
    }

    /** LibreTranslate answers errors as `{"error": "…"}`; the status says what kind. */
    fun failureFor(status: Int, body: String?): Failure {
        val message = runCatching { json.parseToJsonElement(body ?: "").jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
        val lower = message.orEmpty().lowercase()
        return when {
            status == 429 -> Failure(Problem.BUSY, message)
            status == 403 || "api key" in lower || "api_key" in lower -> Failure(Problem.KEY, message)
            "limit" in lower && ("text" in lower || "character" in lower) -> Failure(Problem.TOO_LONG, message)
            status == 404 -> Failure(Problem.NOT_SET_UP, message)
            else -> Failure(Problem.FAILED, message ?: "HTTP $status")
        }
    }
}

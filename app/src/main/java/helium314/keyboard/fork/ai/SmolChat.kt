// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork.ai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import helium314.keyboard.fork.net.NetworkGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection

/**
 * Fork: client of SmolChat Server, the on-device model server. Contract v3:
 * https://github.com/Staninna/SmolChatServer/blob/my-main/docs/LOCAL_LLM_API.md
 */
object SmolChat {
    const val PACKAGE = "io.shubham0204.smollmandroid"
    const val ACTION_PAIR = "$PACKAGE.action.PAIR"
    const val EXTRA_CLIENT_NAME = "client_name"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_BASE_URL = "base_url"
    private const val ACTION_WAKE = "$PACKAGE.action.WAKE"
    private const val WAKE_DESCRIPTOR = "io.shubham0204.smollmandroid.IWake"
    private const val CONNECT_TIMEOUT_MS = 3_000
    // the server sends a keep-alive every 5 s while it reads the prompt or writes
    private const val READ_TIMEOUT_MS = 30_000

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    enum class Wake { STARTED, NOT_PAIRED, NO_MODEL, NOT_ALLOWED, UNAVAILABLE }

    /** What went wrong, each with one thing the user can do about it. */
    enum class Problem { NOT_INSTALLED, NOT_PAIRED, NO_MODEL, NOT_RUNNING, BUSY, TOO_LONG, FAILED }

    class Failure(val problem: Problem, message: String? = null) : IOException(message ?: problem.name)

    fun isInstalled(context: Context) =
        context.packageManager.getLaunchIntentForPackage(PACKAGE) != null

    fun launchIntent(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(PACKAGE)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** `GET /health`; null when nothing listens. */
    suspend fun health(baseUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val c = NetworkGate.open(NetworkGate.Feature.LOCAL_AI, "$baseUrl/health", true)
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = CONNECT_TIMEOUT_MS
            try {
                if (c.responseCode != 200) return@withContext null
                json.parseToJsonElement(c.inputStream.bufferedReader().readText()).jsonObject["status"]?.jsonPrimitive?.content
            } finally {
                c.disconnect()
            }
        } catch (_: IOException) {
            null
        }
    }

    /** Starts a stopped server (contract §3.5): bind, one call, unbind. */
    suspend fun wake(context: Context): Wake {
        val app = context.applicationContext
        val connection = object : ServiceConnection {
            val binder = CompletableDeferred<IBinder?>()
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { binder.complete(service) }
            override fun onServiceDisconnected(name: ComponentName?) {}
            override fun onNullBinding(name: ComponentName?) { binder.complete(null) }
            override fun onBindingDied(name: ComponentName?) { binder.complete(null) }
        }
        val bound = try {
            app.bindService(Intent(ACTION_WAKE).setPackage(PACKAGE), connection, Context.BIND_AUTO_CREATE)
        } catch (_: SecurityException) {
            false
        }
        try {
            if (!bound) return Wake.UNAVAILABLE
            val binder = withTimeoutOrNull(5_000) { connection.binder.await() } ?: return Wake.UNAVAILABLE
            return withContext(Dispatchers.IO) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(WAKE_DESCRIPTOR)
                    binder.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)
                    reply.readException()
                    when (reply.readInt()) {
                        0 -> Wake.STARTED
                        1 -> Wake.NOT_PAIRED
                        2 -> Wake.NO_MODEL
                        3 -> Wake.NOT_ALLOWED
                        else -> Wake.UNAVAILABLE
                    }
                } catch (_: Exception) {
                    Wake.UNAVAILABLE
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }
        } finally {
            runCatching { app.unbindService(connection) }
        }
    }

    @Serializable
    data class Message(val role: String, val content: String)

    @Serializable
    private data class ChatRequest(
        val messages: List<Message>,
        val stream: Boolean = true,
        @SerialName("max_tokens") val maxTokens: Int,
        val temperature: Float = 0.3f,
        @SerialName("chat_template_kwargs") val templateArgs: Map<String, Boolean> = mapOf("enable_thinking" to false),
    )

    /**
     * Streams a chat completion (contract §2.3, `stream: true`) and emits the text as it grows.
     * Streaming keeps the text off the server's disk, unlike background jobs.
     */
    fun stream(pairing: LocalAiPairing.Pairing, messages: List<Message>, maxTokens: Int): Flow<String> = flow {
        val c = NetworkGate.open(NetworkGate.Feature.LOCAL_AI, pairing.baseUrl + "/v1/chat/completions", true)
        try {
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = READ_TIMEOUT_MS
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer " + pairing.token)
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "text/event-stream")
            c.outputStream.use { it.write(json.encodeToString(ChatRequest.serializer(), ChatRequest(messages, maxTokens = maxTokens)).toByteArray()) }
            val code = c.responseCode
            if (code != 200) throw failureFor(code, c.errorStream?.bufferedReader()?.readText())
            val text = StringBuilder()
            c.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    currentCoroutineContext().ensureActive()
                    if (!line.startsWith("data:")) continue // blank lines and ": keep-alive"
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val event = json.parseToJsonElement(data).jsonObject
                    event["error"]?.let { throw failureFor(500, event.toString()) }
                    val delta = event["choices"]?.let { (it as? kotlinx.serialization.json.JsonArray)?.firstOrNull() }
                        ?.jsonObject?.get("delta")?.jsonObject?.get("content") as? JsonPrimitive
                    if (delta != null && delta.content.isNotEmpty()) {
                        text.append(delta.content)
                        emit(text.toString())
                    }
                }
            }
        } finally {
            c.disconnect() // also tells the server to stop generating
        }
    }.flowOn(Dispatchers.IO)

    private fun failureFor(status: Int, body: String?): Failure {
        val code = runCatching {
            json.parseToJsonElement(body ?: "").jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
        }.getOrNull()
        return when {
            status == 401 -> Failure(Problem.NOT_PAIRED)
            status == 429 -> Failure(Problem.BUSY)
            status == 503 || code == "model_not_loaded" -> Failure(Problem.NO_MODEL)
            code == "context_length_exceeded" -> Failure(Problem.TOO_LONG)
            else -> Failure(Problem.FAILED, code)
        }
    }
}

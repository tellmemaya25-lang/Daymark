package com.sabihon.todo.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HttpsURLConnection

/**
 * One HTTPS call to the Anthropic Messages API.
 *
 * Small on purpose. Everything that can be wrong without a network - the request body,
 * escaping, model choice, error wording, which content blocks count as the answer - lives
 * in [ClaudeMessages] where it is compiled and tested. What is left here is the shape
 * already proven by `FirebaseAiTaskService.callGoogleAiStudioRest`: open a connection,
 * write the body, read 2xx or throw with the status and a snippet.
 *
 * Two behaviours are worth stating because they are not obvious:
 *
 *  - **Model ids are resolved once per process.** Anthropic retires ids on a schedule, so a
 *    404 advances to the next candidate and the winner is remembered in
 *    [resolvedModel]. A bad key (401/403) does *not* advance: four failed requests would
 *    just be four rejections.
 *  - **`stop_reason == "max_tokens"` is a failure, not a short answer.** The reply would be
 *    JSON cut mid-object, which parses to nothing and would look like the model "returned
 *    garbage" rather than "we did not give it room".
 */
@Singleton
class ClaudeAiTransport @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var resolvedModel: String? = null

    /**
     * @param preferredModel optional override from Admin > AI & Config; null uses the
     *   built-in candidates.
     * @return the model's answer as text, already stripped of thinking blocks.
     * @throws IllegalStateException with a user-actionable message on any failure.
     */
    suspend fun generate(
        apiKey: String,
        prompt: String,
        preferredModel: String? = null,
        maxTokens: Int = ClaudeMessages.MAX_TOKENS
    ): String = withContext(Dispatchers.IO) {
        val candidates = ClaudeMessages.modelCandidates(preferredModel)
            .let { list -> resolvedModel?.let { list.filter { id -> id == it } + list.filter { id -> id != it } } ?: list }

        var lastError: Exception? = null

        for (model in candidates) {
            try {
                val text = callOnce(apiKey, model, prompt, maxTokens)
                resolvedModel = model
                return@withContext text
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastError = e
                val code = (e as? ClaudeHttpException)?.statusCode ?: -1
                val body = (e as? ClaudeHttpException)?.body.orEmpty()
                // A rejected key fails the same way on every model id, so stop immediately.
                if (code > 0 && ClaudeMessages.isAuthError(code)) throw e
                val retryable = code > 0 && ClaudeMessages.isRetryableModelError(code, body)
                if (!retryable) throw e
                // A retired id: note it and try the next candidate.
                android.util.Log.w(
                    "ClaudeAiTransport",
                    "model=$model unavailable, trying the next candidate"
                )
            }
        }
        throw lastError ?: IllegalStateException("No Claude model available")
    }

    /** True once a model has answered, so the caller can show which one is in use. */
    fun activeModel(): String? = resolvedModel

    private fun callOnce(
        apiKey: String,
        model: String,
        prompt: String,
        maxTokens: Int
    ): String {
        val body = ClaudeMessages.requestBody(model, prompt, maxTokens)
        val conn = (URL(ClaudeMessages.ENDPOINT).openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 20_000
            doOutput = true
            ClaudeMessages.headers(apiKey).forEach { (name, value) ->
                setRequestProperty(name, value)
            }
        }

        try {
            conn.outputStream.use { out ->
                out.write(body.toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val errorBody = conn.errorStream
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    .orEmpty()
                throw ClaudeHttpException(
                    statusCode = code,
                    body = errorBody,
                    message = ClaudeMessages.friendlyError(code, errorBody)
                )
            }

            val raw = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return parseReply(raw, model)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Turns one Messages reply into text.
     *
     * Decoded into [MessagesReply] rather than walked as a JSON tree: the shape is fixed and
     * `ignoreUnknownKeys` absorbs whatever Anthropic adds next. Thinking blocks arrive in the
     * same `content` array as the answer, so the filtering rule lives in
     * [ClaudeMessages.textFromBlocks] where it is unit tested.
     */
    private fun parseReply(raw: String, model: String): String {
        val reply = runCatching { json.decodeFromString<MessagesReply>(raw) }
            .getOrElse { throw IllegalStateException("Claude sent a reply that is not JSON") }

        // The API reports failures inside a 2xx envelope far more often than one would like.
        reply.error?.message?.let { message ->
            throw IllegalStateException("Claude error: ${message.take(160)}")
        }

        if (ClaudeMessages.isTruncated(reply.stopReason)) {
            throw IllegalStateException(
                "Claude ran out of output room on $model; raise max_tokens or shorten the prompt"
            )
        }

        return ClaudeMessages.textFromBlocks(
            reply.content.map { block -> ClaudeMessages.Block(type = block.type, text = block.text) }
        ) ?: throw IllegalStateException("Claude returned no text on $model")
    }

    @Serializable
    private data class MessagesReply(
        val content: List<ContentBlock> = emptyList(),
        @SerialName("stop_reason") val stopReason: String? = null,
        val error: ApiError? = null
    )

    @Serializable
    private data class ContentBlock(
        val type: String = "",
        val text: String? = null
    )

    @Serializable
    private data class ApiError(val message: String? = null)

    /** Carries the status code so the caller can decide whether another model is worth it. */
    private class ClaudeHttpException(
        val statusCode: Int,
        val body: String,
        message: String
    ) : IllegalStateException(message)
}

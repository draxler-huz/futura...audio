package com.futura.ai

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Asks Gemini, with the user's own key, to research a song and describe it.
 * The prompt forbids quoting or reproducing lyrics, so the reply is a summary.
 * Only the title and artist are sent.
 */
@Singleton
class SongSummaryService @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val keys: AiKeyStore
) {

    suspend fun summarize(title: String, artist: String?): Result<String> = withContext(Dispatchers.IO) {
        val key = keys.geminiKey()
            ?: return@withContext Result.failure(IllegalStateException("Add your Gemini API key first."))
        try {
            Result.success(request(key, title, artist))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Result.failure(IOException("Could not reach Gemini. Check your connection and try again."))
        } catch (e: JSONException) {
            Result.failure(IOException("Gemini returned an unexpected reply."))
        }
    }

    private fun request(key: String, title: String, artist: String?): String {
        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt(title, artist))))
                )
            )
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            .toString()

        val request = Request.Builder()
            .url("$ENDPOINT/$MODEL:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Gemini returned HTTP ${response.code}.")
            val parts = JSONObject(text)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
            return buildString {
                for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text"))
            }.trim()
        }
    }

    private fun prompt(title: String, artist: String?): String {
        val who = if (artist.isNullOrBlank()) "\"$title\"" else "\"$title\" by $artist"
        return "Research the song $who. Write a short summary of its meaning, mood, themes and story, " +
            "in under 120 words. Do not quote, reproduce or paraphrase any of its lyrics line by line. " +
            "If you cannot find reliable information about this song, say so."
    }

    private companion object {
        const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"
        const val MODEL = "gemini-2.5-flash"
    }
}

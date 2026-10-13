package com.webtoon.translator.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * API-mode translation for providers that expose official endpoints.
 * The WebView chatbot mode is implemented in the activity layer; both feed
 * the same Prompts.buildPrompt / Prompts.parseResponse pipeline.
 *
 * API keys come only from EncryptedSharedPreferences and are never logged.
 */
class TranslationEngine(private val baseUrl: String, private val apiKey: String, private val model: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * OpenAI-compatible /chat/completions call. Works for OpenAI and any
     * compatible endpoint (DeepSeek API, Groq, local OpenAI-style servers).
     */
    fun chat(system: String, user: String): Result<String> {
        return try {
            val body = JSONObject()
                .put("model", model)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user))
                )
                .put("temperature", 0.3)
            val req = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return Result.failure(Exception("HTTP ${resp.code}: ${text.take(300)}"))
                val json = JSONObject(text)
                val content = json.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content")
                Result.success(content)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Gemini generateContent call. */
    fun gemini(system: String, user: String): Result<String> {
        return try {
            val body = JSONObject()
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put("role", "user").put(
                            "parts", JSONArray().put(JSONObject().put("text", user))
                        )
                    )
                )
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("generationConfig", JSONObject().put("temperature", 0.3))
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return Result.failure(Exception("HTTP ${resp.code}: ${text.take(300)}"))
                val content = JSONObject(text)
                    .getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                    .getJSONObject(0).getString("text")
                Result.success(content)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

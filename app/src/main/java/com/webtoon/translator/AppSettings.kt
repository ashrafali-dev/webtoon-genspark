package com.webtoon.translator

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Central settings store. Regular preferences live in a normal SharedPreferences;
 * secrets (API keys) live in EncryptedSharedPreferences and are never logged.
 */
object AppSettings {
    private const val FILE = "wt_settings"
    private lateinit var prefs: SharedPreferences
    private lateinit var secure: SharedPreferences

    fun init(context: Context) {
        if (this::prefs.isInitialized) return
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val mk = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        secure = EncryptedSharedPreferences.create(
            context, "wt_secure", mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun String.toSecret(): String = if (length > 8) "••••••••" + takeLast(4) else "••••••"

    // ------- generic -------
    var homeUrl: String
        get() = prefs.getString("homeUrl", "https://www.google.com/")!!
        set(v) = prefs.edit().putString("homeUrl", v).apply()

    var sourceLang: String
        get() = prefs.getString("sourceLang", "auto")!!
        set(v) = prefs.edit().putString("sourceLang", v).apply()

    var targetLang: String
        get() = prefs.getString("targetLang", "Bengali")!!
        set(v) = prefs.edit().putString("targetLang", v).apply()

    /** Chatbot id currently used for WebView automation. */
    var activeBot: String
        get() = prefs.getString("activeBot", "chatgpt")!!
        set(v) = prefs.edit().putString("activeBot", v).apply()

    /** Whether the bottom pane hosts the chatbot webview ("webview") or an API client ("api"). */
    var botMode: String
        get() = prefs.getString("botMode", "webview")!!
        set(v) = prefs.edit().putString("botMode", v).apply()

    /** OCR script engine key, see OcrScript. */
    var ocrScript: String
        get() = prefs.getString("ocrScript", "auto")!!
        set(v) = prefs.edit().putString("ocrScript", v).apply()

    var overlayOn: Boolean
        get() = prefs.getBoolean("overlayOn", true)
        set(v) = prefs.edit().putBoolean("overlayOn", v).apply()

    var overlayFontScale: Float
        get() = prefs.getFloat("overlayFontScale", 1.0f)
        set(v) = prefs.edit().putFloat("overlayFontScale", v).apply()

    var overlayOpacity: Float
        get() = prefs.getFloat("overlayOpacity", 0.85f)
        set(v) = prefs.edit().putFloat("overlayOpacity", v).apply()

    /** User's custom bot list persisted as JSON: [{id,name,url}] */
    var customBots: String
        get() = prefs.getString("customBots", "[]")!!
        set(v) = prefs.edit().putString("customBots", v).apply()

    fun customBotsList(): JSONArray = try { JSONArray(customBots) } catch (_: Exception) { JSONArray() }

    // ------- secrets (never logged, never passed to page JS) -------
    var apiKeyOpenAi: String
        get() = secure.getString("apiKeyOpenAi", "") ?: ""
        set(v) = secure.edit().putString("apiKeyOpenAi", v).apply()

    var apiKeyGemini: String
        get() = secure.getString("apiKeyGemini", "") ?: ""
        set(v) = secure.edit().putString("apiKeyGemini", v).apply()

    var apiBaseUrl: String
        get() = secure.getString("apiBaseUrl", "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
        set(v) = secure.edit().putString("apiBaseUrl", v).apply()

    var apiModel: String
        get() = secure.getString("apiModel", "gpt-4o-mini") ?: "gpt-4o-mini"
        set(v) = secure.edit().putString("apiModel", v).apply()

    fun secretSummary(key: String): String = secure.getString(key, "")!!.toSecret()
}

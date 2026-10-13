package com.webtoon.translator.core

import org.json.JSONArray
import java.io.File
import java.security.MessageDigest

class TranslationCache(private val dir: File) {
    init { dir.mkdirs() }
    private fun key(parts: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        val h = md.digest(parts.joinToString("\u0001").toByteArray())
        return h.joinToString("") { "%02x".format(it) }
    }
    fun get(contentHash: String, ocrEngine: String, sourceLang: String, targetLang: String, provider: String, promptVersion: String = Prompts.PROMPT_VERSION): List<TranslationItem>? {
        val f = File(dir, key(listOf(contentHash, ocrEngine, sourceLang, targetLang, provider, promptVersion)) + ".json")
        if (!f.exists()) return null
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TranslationItem(o.getString("imageId"), o.getString("regionId"), o.getString("translatedText"))
            }
        } catch (_: Exception) { null }
    }
    fun put(items: List<TranslationItem>, contentHash: String, ocrEngine: String, sourceLang: String, targetLang: String, provider: String, promptVersion: String = Prompts.PROMPT_VERSION) {
        if (items.isEmpty()) return
        val f = File(dir, key(listOf(contentHash, ocrEngine, sourceLang, targetLang, provider, promptVersion)) + ".json")
        try { val arr = JSONArray(); for (it in items) arr.put(it.toJson()); f.writeText(arr.toString()) } catch (_: Exception) {}
    }
    fun clear(): Int {
        val files = dir.listFiles() ?: return 0
        val n = files.size
        files.forEach { it.delete() }
        return n
    }
}
object ContentHash {
    fun of(texts: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        val h = md.digest(texts.joinToString("\u000C").toByteArray())
        return h.take(16).joinToString("") { "%02x".format(it) }
    }
}

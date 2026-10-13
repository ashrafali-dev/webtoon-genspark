package com.webtoon.translator.core

import org.json.JSONArray
import org.json.JSONObject

/** Prompt construction and structured-response parsing. */
object Prompts {
    const val PROMPT_VERSION = "2025-08-b1"
    private val styleRules = listOf(
        "Translate the text into natural, fluent {TARGET} for a literary reader.",
        "Preserve original meaning, character names, titles and forms of address.",
        "Keep emotional tone, humor and dialogue style. Do not invent or drop content.",
        "Translate each numbered item independently; never merge items.",
        "Return ONLY a JSON object, no commentary, no code fences.",
        "Required format: {\"translations\":[{\"imageId\":\"...\",\"regionId\":\"...\",\"translatedText\":\"...\"}]}"
    )

    fun systemInstruction(sourceLang: String, targetLang: String): String {
        val src = if (sourceLang == "auto") "auto-detect the source language" else "source language is $sourceLang"
        return listOf(
            "You are a professional literary translator for webtoons, manga, manhua, manhwa and web novels.",
            src,
            *styleRules.map { it.replace("{TARGET}", targetLang) }.toTypedArray()
        ).joinToString("\n")
    }

    fun buildPrompt(items: List<TextRegion>, sourceLang: String, targetLang: String, extraContext: String = ""): String {
        val sb = StringBuilder()
        sb.append(systemInstruction(sourceLang, targetLang)).append("\n\n")
        if (extraContext.isNotBlank()) sb.append("Story context (do not translate, use only to keep terminology consistent):\n")
            .append(extraContext.trim()).append("\n\n")
        sb.append("Translate these ").append(items.size).append(" items into ").append(targetLang).append(":\n\n")
        for (item in items) sb.append(Ids.marker(item.regionId)).append('\n').append(item.text.trim()).append("\n\n")
        sb.append("Answer with the JSON object only. Every marker ID must appear exactly once in the output.")
        return sb.toString()
    }

    fun parseResponse(raw: String): List<TranslationItem> {
        if (raw.isBlank()) return emptyList()
        parseJson(raw)?.let { return it }
        return parseMarkers(raw)
    }

    fun parseJson(raw: String): List<TranslationItem>? {
        val ticks = 96.toChar().toString().repeat(3)
        val text = raw.trim()
            .removePrefix(ticks + "json").removePrefix(ticks)
            .removeSuffix(ticks).trim()
        parseJsonObject(text)?.let { return it }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) parseJsonObject(text.substring(start, end + 1))?.let { return it }
        return null
    }

    private fun parseJsonObject(candidate: String): List<TranslationItem>? {
        return try {
            val arr = JSONObject(candidate).optJSONArray("translations") ?: return null
            val out = ArrayList<TranslationItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val rid = o.optString("regionId", o.optString("id", ""))
                val txt = o.optString("translatedText", o.optString("text", o.optString("t", "")))
                if (rid.isNotBlank() && txt.isNotBlank()) {
                    out.add(TranslationItem(o.optString("imageId", "novel"), rid, txt))
                }
            }
            out.takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }

    fun parseMarkers(raw: String): List<TranslationItem> {
        val out = ArrayList<TranslationItem>()
        val matches = Regex("<<<([A-Za-z0-9_-]+)>>>", RegexOption.MULTILINE).findAll(raw).toList()
        for (i in matches.indices) {
            val m = matches[i]
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else raw.length
            val body = raw.substring(m.range.last + 1, end).trim()
            if (body.isNotEmpty()) out.add(TranslationItem("novel", m.groupValues[1], body))
        }
        return out
    }

    fun looksLikeFailure(raw: String, minItems: Int): Boolean {
        val t = raw.trim()
        if (t.isEmpty() || parseResponse(t).size < minItems.coerceAtLeast(1)) return true
        val lower = t.lowercase()
        return listOf("i cannot translate", "i can't translate", "i'm unable", "i am unable", "against my policies")
            .any { lower.contains(it) } && parseResponse(t).isEmpty()
    }
}

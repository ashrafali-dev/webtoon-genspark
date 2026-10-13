package com.webtoon.translator.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Core data model shared by OCR, translation and insertion layers.
 * Everything that crosses a process/webview boundary is JSON with stable IDs.
 */

data class TextRegion(
    val imageId: String,
    val regionId: String,
    val text: String,
    /** Bounding box in the ORIGINAL decoded bitmap coordinate system. */
    val x: Float, val y: Float, val w: Float, val h: Float,
    /** 0-based reading order within the image. */
    val order: Int,
    /** e.g. "en", "zh-Hans", "ja", "ko"; empty when unknown. */
    val language: String = "",
    /** 0..1, -1 when the engine provides no confidence. */
    val confidence: Float = -1f,
    /** "horizontal" | "vertical" | "" */
    val orientation: String = "horizontal"
) {
    fun toJson(): JSONObject = JSONObject()
        .put("imageId", imageId).put("regionId", regionId)
        .put("x", x.toDouble()).put("y", y.toDouble())
        .put("w", w.toDouble()).put("h", h.toDouble())
        .put("order", order).put("text", text)
        .put("language", language).put("confidence", confidence.toDouble())
        .put("orientation", orientation)

    companion object {
        fun fromJson(o: JSONObject): TextRegion = TextRegion(
            imageId = o.optString("imageId", ""),
            regionId = o.optString("regionId", ""),
            text = o.optString("text", ""),
            x = o.optDouble("x", 0.0).toFloat(),
            y = o.optDouble("y", 0.0).toFloat(),
            w = o.optDouble("w", 0.0).toFloat(),
            h = o.optDouble("h", 0.0).toFloat(),
            order = o.optInt("order", 0),
            language = o.optString("language", ""),
            confidence = o.optDouble("confidence", -1.0).toFloat(),
            orientation = o.optString("orientation", "horizontal")
        )
    }
}

data class OcrImage(
    val imageId: String,
    val index: Int,
    val src: String,
    val naturalWidth: Int,
    val naturalHeight: Int,
    val regions: MutableList<TextRegion> = mutableListOf(),
    var state: String = "DISCOVERED", // DISCOVERED, DOWNLOADED, DECODED, OCR_RUNNING, OCR_SUCCESS, OCR_EMPTY, LOW_TEXT, OCR_FAILED, CANCELLED
    var error: String = "",
    var retries: Int = 0,
    var ocrMs: Long = 0,
    var ocrEngine: String = ""
) {
    val regionCount: Int get() = regions.size
    val charCount: Int get() = regions.sumOf { it.text.length }
}

data class TranslationItem(
    val imageId: String,
    val regionId: String,
    val translatedText: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("imageId", imageId).put("regionId", regionId).put("translatedText", translatedText)
}

object Ids {
    fun imageId(index: Int): String = "image-%03d".format(index)
    fun regionId(imageIndex: Int, regionIndex: Int): String = "r-%03d-%03d".format(imageIndex, regionIndex)

    /** Markdown-safe region marker used inside chatbot prompts. */
    fun marker(regionId: String): String = "<<<$regionId>>>"
}

/** Pure-Kotlin stable-ID <-> translation matching used by the validator. */
object TranslationMatcher {

    data class MatchResult(
        val matched: List<TranslationItem>,
        val unknownIds: List<String>,
        val missingIds: List<String>,
        val duplicateIds: List<String>
    ) {
        val complete: Boolean get() = unknownIds.isEmpty() && missingIds.isEmpty() && duplicateIds.isEmpty()
    }

    /**
     * Matches a parsed translation list against the expected set of regionIds.
     * Never matches by array position — only by ID.
     */
    fun match(expectedRegionIds: List<String>, parsed: List<TranslationItem>): MatchResult {
        val expected = LinkedHashSet(expectedRegionIds)
        val seen = HashMap<String, Int>() // id -> occurrences
        val matched = ArrayList<TranslationItem>()
        val unknown = ArrayList<String>()
        val duplicates = ArrayList<String>()

        for (item in parsed) {
            val id = item.regionId
            seen[id] = (seen[id] ?: 0) + 1
            if (seen[id]!! > 1) { duplicates.add(id); continue }
            if (expected.contains(id)) matched.add(item) else unknown.add(id)
        }
        val missing = expectedRegionIds.filter { (seen[it] ?: 0) == 0 }
        // Preserve SOURCE ordering: output follows the expected ID order,
        // never the order the chatbot happened to return.
        val ordered = matched.sortedBy { expectedRegionIds.indexOf(it.regionId) }
        return MatchResult(ordered, unknown, missing, duplicates.distinct())
    }
}

package com.webtoon.translator

import com.webtoon.translator.core.OcrImage
import com.webtoon.translator.core.Prompts
import com.webtoon.translator.core.TextRegion
import com.webtoon.translator.core.TranslationItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

data class PreparedParagraph(val index: Int, val text: String)

data class PreparedChapter(
    val url: String,
    val createdAt: Long,
    val sourceLang: String,
    val targetLang: String,
    val ocrScript: String,
    val promptVersion: String,
    val botId: String,
    val novelMode: Boolean,
    val paragraphs: List<PreparedParagraph>,
    val images: List<OcrImage>,
    val translations: List<TranslationItem>
) {
    fun compatible(source: String, target: String, script: String): Boolean =
        sourceLang == source && targetLang == target && ocrScript == script &&
            promptVersion == Prompts.PROMPT_VERSION && translations.isNotEmpty()

    fun toJson(): JSONObject {
        val ps = JSONArray()
        paragraphs.forEach { ps.put(JSONObject().put("index", it.index).put("text", it.text)) }
        val ims = JSONArray()
        images.forEach { image ->
            val regions = JSONArray()
            image.regions.forEach { regions.put(it.toJson()) }
            ims.put(JSONObject()
                .put("imageId", image.imageId).put("index", image.index).put("src", image.src)
                .put("naturalWidth", image.naturalWidth).put("naturalHeight", image.naturalHeight)
                .put("state", image.state).put("error", image.error).put("retries", image.retries)
                .put("ocrMs", image.ocrMs).put("ocrEngine", image.ocrEngine).put("regions", regions))
        }
        val ts = JSONArray()
        translations.forEach { ts.put(it.toJson()) }
        return JSONObject()
            .put("url", ChapterTranslationCache.normalizeUrl(url)).put("createdAt", createdAt)
            .put("sourceLang", sourceLang).put("targetLang", targetLang).put("ocrScript", ocrScript)
            .put("promptVersion", promptVersion).put("botId", botId).put("novelMode", novelMode)
            .put("paragraphs", ps).put("images", ims).put("translations", ts)
    }

    companion object {
        fun fromJson(root: JSONObject): PreparedChapter {
            val paragraphs = ArrayList<PreparedParagraph>()
            root.optJSONArray("paragraphs")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    paragraphs.add(PreparedParagraph(p.optInt("index"), p.optString("text")))
                }
            }
            val images = ArrayList<OcrImage>()
            root.optJSONArray("images")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val image = OcrImage(
                        imageId = o.optString("imageId"), index = o.optInt("index"),
                        src = o.optString("src"), naturalWidth = o.optInt("naturalWidth"),
                        naturalHeight = o.optInt("naturalHeight"), state = o.optString("state", "OCR_SUCCESS"),
                        error = o.optString("error"), retries = o.optInt("retries"),
                        ocrMs = o.optLong("ocrMs"), ocrEngine = o.optString("ocrEngine")
                    )
                    o.optJSONArray("regions")?.let { regions ->
                        for (j in 0 until regions.length()) image.regions.add(TextRegion.fromJson(regions.getJSONObject(j)))
                    }
                    images.add(image)
                }
            }
            val translations = ArrayList<TranslationItem>()
            root.optJSONArray("translations")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val t = arr.getJSONObject(i)
                    translations.add(TranslationItem(t.optString("imageId"), t.optString("regionId"), t.optString("translatedText")))
                }
            }
            return PreparedChapter(
                url = ChapterTranslationCache.normalizeUrl(root.optString("url")),
                createdAt = root.optLong("createdAt"), sourceLang = root.optString("sourceLang"),
                targetLang = root.optString("targetLang"), ocrScript = root.optString("ocrScript"),
                promptVersion = root.optString("promptVersion"), botId = root.optString("botId"),
                novelMode = root.optBoolean("novelMode"), paragraphs = paragraphs,
                images = images, translations = translations
            )
        }
    }
}

class ChapterTranslationCache(private val dir: File, private val maxEntries: Int = 8) {
    init { dir.mkdirs() }

    @Synchronized
    fun get(url: String, sourceLang: String, targetLang: String, ocrScript: String): PreparedChapter? {
        val f = fileFor(url)
        if (!f.exists()) return null
        return try {
            val chapter = PreparedChapter.fromJson(JSONObject(f.readText()))
            if (chapter.url != normalizeUrl(url) || !chapter.compatible(sourceLang, targetLang, ocrScript)) null
            else chapter
        } catch (_: Exception) { null }
    }

    @Synchronized
    fun put(chapter: PreparedChapter) {
        if (chapter.translations.isEmpty()) return
        dir.mkdirs()
        val target = fileFor(chapter.url)
        val temp = File(dir, target.name + ".tmp")
        try {
            temp.writeText(chapter.toJson().toString())
            if (!temp.renameTo(target)) {
                target.writeText(temp.readText())
                temp.delete()
            }
            prune()
        } catch (_: Exception) { temp.delete() }
    }

    @Synchronized
    fun clear(): Int {
        val files = dir.listFiles() ?: return 0
        var count = 0
        files.forEach { if (it.isFile && it.delete()) count++ }
        return count
    }

    private fun fileFor(url: String): File {
        val bytes = MessageDigest.getInstance("SHA-256").digest(normalizeUrl(url).toByteArray(Charsets.UTF_8))
        return File(dir, bytes.joinToString("") { "%02x".format(it) } + ".json")
    }

    private fun prune() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(maxEntries.coerceAtLeast(1)).forEach { it.delete() }
    }

    companion object {
        fun normalizeUrl(value: String): String = try {
            val u = URL(value.substringBefore("#"))
            val scheme = u.protocol.lowercase(Locale.US)
            if (scheme != "http" && scheme != "https") {
                value.substringBefore("#")
            } else {
                val host = u.host.lowercase(Locale.US)
                val port = when {
                    u.port == -1 -> ""
                    scheme == "http" && u.port == 80 -> ""
                    scheme == "https" && u.port == 443 -> ""
                    else -> ":" + u.port
                }
                val path = u.path.ifBlank { "/" }
                val normalizedPath = if (path.length > 1) path.trimEnd('/').ifBlank { "/" } else path
                scheme + "://" + host + port + normalizedPath + (u.query?.let { "?" + it } ?: "")
            }
        } catch (_: Exception) { value.substringBefore("#").trim() }
    }
}

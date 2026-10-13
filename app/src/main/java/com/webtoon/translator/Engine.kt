package com.webtoon.translator

import android.graphics.Bitmap
import android.util.Base64
import android.webkit.WebView
import com.webtoon.translator.core.Ids
import com.webtoon.translator.core.OcrImage
import com.webtoon.translator.core.Prompts
import com.webtoon.translator.core.TextRegion
import com.webtoon.translator.core.TranslationItem
import com.webtoon.translator.core.TranslationMatcher
import com.webtoon.translator.core.TranslationCache
import com.webtoon.translator.ocr.ImageDecoder
import com.webtoon.translator.ocr.MlKitOcrProvider
import com.webtoon.translator.web.ChatbotHelper
import com.webtoon.translator.web.ImageBridge
import com.webtoon.translator.web.WebScripts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

data class JobUiState(
    val jobId: String = "",
    val phase: String = "idle",       // idle | scanning | ocr | translating | inserting | done | failed | cancelled
    val status: String = "",
    val images: List<OcrImage> = listOf(),
    val novelParas: Int = 0,
    val novelMode: Boolean = false,
    val translationsReady: Int = 0,
    val translationsTotal: Int = 0,
    val insertedCount: Int = 0,
    val diagnostics: List<String> = listOf()
)

/**
 * The engine coordinates the whole pipeline and holds ONE active chapter job.
 * Every callback carries the jobId it belongs to; stale ones are dropped.
 */
class Engine(
    private val readerWv: () -> WebView?,
    private val scope: CoroutineScope,
    private val onLog: (String) -> Unit,
    private val chatbotMutex: Mutex = Mutex()
) {
    private val _state = MutableStateFlow(JobUiState())
    val state: StateFlow<JobUiState> = _state

    private var activeJob: Job? = null
    @Volatile private var jobToken: String = ""

    private var cacheDir: File = File("/tmp/wt")
    private var lastTranslations: List<TranslationItem> = emptyList()
    private var lastMatchComplete: Boolean = false
    fun setCacheDir(path: File) { cacheDir = path }

    private val pendingFetches = ConcurrentHashMap<String, (String) -> Unit>()
    private val bridge = ImageBridge { reqId, json ->
        pendingFetches.remove(reqId)?.invoke(json)
    }
    fun bridgeInstance(): ImageBridge = bridge

    private val providers = HashMap<String, MlKitOcrProvider>()
    private fun provider(script: String): MlKitOcrProvider =
        providers.getOrPut(script) { MlKitOcrProvider(script) }

    // ================================================================ scan

    private fun detachActiveJobForCancellation(): Pair<String, Job?> {
        val old = jobToken
        jobToken = ""
        _state.value = _state.value.copy(phase = "cancelled", status = "Cancelled")
        val previous = activeJob
        activeJob = null
        previous?.cancel()
        return old to previous
    }

    fun cancelAll() {
        val (old, previous) = detachActiveJobForCancellation()
        scope.launch {
            previous?.cancelAndJoin()
            if (old.isNotEmpty()) onLog("[$old] cancelled")
        }
    }

    suspend fun cancelAllAndWait() {
        val (old, previous) = detachActiveJobForCancellation()
        previous?.cancelAndJoin()
        if (old.isNotEmpty()) onLog("[$old] cancelled")
    }

    fun scanChapter() {
        val jobId = "job-" + System.currentTimeMillis().toString(36)
        jobToken = jobId
        val token = jobId
        lastTranslations = emptyList()
        lastMatchComplete = false
        novelItems = emptyList()
        _state.value = JobUiState(jobId = jobId, phase = "scanning", status = "Starting scan…")
        activeJob = scope.launch {
            onLog("[$jobId] scan started")
            try {
                val wv = readerWv() ?: run { fail(token, "Reader webview not ready"); return@launch }
                val url = withContext(Dispatchers.Main) { wv.url } ?: ""
                onLog("[$jobId] scanning $url")

                // 1) novel (DOM text) chapter?
                val novelJson = eval(wv, WebScripts.EXTRACT_NOVEL)
                val novelObj = try { JSONObject(novelJson) } catch (_: Exception) { JSONObject() }
                if (novelObj.optBoolean("ok")) {
                    handleNovelScan(token, novelObj)
                    return@launch
                }
                onLog("[$jobId] novel extraction: ${novelObj.optString("reason", "no")} → image mode")

                // 2) progressive image discovery + lazy-load scrolling
                val discovered = discoverImages(wv, token)
                if (discovered.length() == 0) {
                    fail(token, "No chapter images found — the site may need its native reader or login")
                    return@launch
                }
                onLog("[$jobId] discovered ${discovered.length()} images")

                val images: List<OcrImage> = (0 until discovered.length()).map { i ->
                    val o = discovered.getJSONObject(i)
                    OcrImage(
                        imageId = Ids.imageId(i), index = i, src = o.optString("url"),
                        naturalWidth = o.optInt("nw"), naturalHeight = o.optInt("nh")
                    )
                }
                pushImages(token, images)

                // 3) download + decode + OCR with bounded concurrency
                val sem = Semaphore(2)
                val jobs: List<Job> = images.map { img ->
                    launch {
                        sem.withPermit {
                            if (jobToken == token) processImage(token, wv, img)
                        }
                    }
                }
                jobs.joinAll()
                val ok = images.count { it.state == "OCR_SUCCESS" }
                markPhase(token, "OCR done — $ok/${images.size} images with text", "ocr")
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (jobToken == token) _state.value = _state.value.copy(phase = "cancelled", status = "Cancelled")
                throw e
            } catch (e: Exception) {
                fail(token, "Scan failed: ${e.message}")
            }
        }
    }

    private suspend fun discoverImages(wv: WebView, token: String): JSONArray {
        val all = LinkedHashMap<String, JSONObject>()
        var stableRounds = 0
        var round = 0
        val maxRounds = 60
        while (jobToken == token && round < maxRounds) {
            round++
            val json = eval(wv, WebScripts.EXTRACT_IMAGES)
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val u = o.optString("url")
                    if (u.isNotEmpty() && !all.containsKey(u)) all[u] = o
                }
            } catch (_: Exception) {}
            _state.value = _state.value.copy(
                status = "Scanning page… ${all.size} images (scroll $round)",
                images = all.values.mapIndexed { i, o ->
                    OcrImage(
                        imageId = Ids.imageId(i), index = i, src = o.optString("url"),
                        naturalWidth = o.optInt("nw"), naturalHeight = o.optInt("nh")
                    )
                }
            )
            val res = eval(wv, WebScripts.SCROLL_STEP, "700")
            val done = try { JSONObject(res).optBoolean("done") } catch (_: Exception) { false }
            if (done) {
                stableRounds++
                if (stableRounds >= 2) break
            } else stableRounds = 0
            delay(350)
        }
        return JSONArray(ArrayList(all.values))
    }

    private suspend fun processImage(token: String, wv: WebView, img: OcrImage) {
        updateImage(token, img.imageId) { it.state = "DOWNLOADED"; it.error = "" }
        try {
            val bytes = fetchImageBytes(wv, img.src)
            if (bytes == null) {
                updateImage(token, img.imageId) { it.state = "OCR_FAILED"; it.error = "download failed" }
                return
            }
            updateImage(token, img.imageId) { it.state = "DECODED" }
            val decoded = withContext(Dispatchers.Default) { ImageDecoder.decode(bytes) }
            if (decoded == null) {
                updateImage(token, img.imageId) { it.state = "OCR_FAILED"; it.error = "decode failed" }
                return
            }
            val (bmp, sample) = decoded
            updateImage(token, img.imageId) { it.state = "OCR_RUNNING" }
            val outcome = provider(AppSettings.ocrScript).recognize(bmp, img.index)
            bmp.recycle()
            updateImage(token, img.imageId) {
                it.ocrEngine = outcome.engine
                it.ocrMs = outcome.ms
                when (outcome.status) {
                    "ok" -> {
                        // bbox from decode space back to reported natural space
                        val scale = sample.toFloat()
                        it.regions.clear()
                        it.regions.addAll(outcome.regions.map { r ->
                            if (scale > 1f) r.copy(x = r.x * scale, y = r.y * scale, w = r.w * scale, h = r.h * scale) else r
                        })
                        it.state = if (it.regions.isEmpty()) "OCR_EMPTY" else "OCR_SUCCESS"
                        if (it.state == "OCR_EMPTY") it.error = "no text recognized"
                    }
                    "empty" -> { it.state = "OCR_EMPTY"; it.error = "no text recognized" }
                    else -> { it.state = "OCR_FAILED"; it.error = outcome.error }
                }
            }
            _state.value = _state.value.copy(status = "OCR ${img.index + 1}: ${outcome.status} (${outcome.ms}ms)")
        } catch (e: Exception) {
            updateImage(token, img.imageId) { it.state = "OCR_FAILED"; it.error = e.message ?: "error" }
        }
    }

    /**
     * Fetch image bytes using the live page first (supports blob/data URLs and session cookies),
     * then fall back to native HTTP with the WebView cookie jar and page Referer. This mirrors
     * Novel Studio's proven fetch strategy and avoids CORS-only failures on image CDNs.
     */
    private suspend fun fetchImageBytes(wv: WebView, url: String): ByteArray? {
        // data: URLs are self-contained and do not need network access.
        if (url.startsWith("data:", ignoreCase = true)) {
            return try {
                val comma = url.indexOf(',')
                if (comma < 0) null else {
                    val head = url.substring(0, comma)
                    val body = url.substring(comma + 1)
                    if (head.contains(";base64", ignoreCase = true)) Base64.decode(body, Base64.DEFAULT)
                    else java.net.URLDecoder.decode(body, "UTF-8").toByteArray(Charsets.ISO_8859_1)
                }
            } catch (_: Exception) { null }
        }

        // HTTP(S) first, matching Novel Studio. Native requests are not subject to browser CORS.
        // Carry the live WebView's cookies, User-Agent and page Referer.
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            onLog("fetch failed: unsupported image URL scheme")
            return null
        }
        // WebView properties must only be read on the main thread. Snapshot them
        // before entering Dispatchers.IO; otherwise every native request throws.
        val requestContext = withContext(Dispatchers.Main) {
            Triple(wv.settings.userAgentString, wv.url.orEmpty(), android.webkit.CookieManager.getInstance().getCookie(url))
        }
        val nativeBytes = withContext(Dispatchers.IO) {
            var connection: java.net.HttpURLConnection? = null
            try {
                connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 25_000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", requestContext.first)
                    setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                    val referer = requestContext.second
                    if (referer.isNotBlank()) setRequestProperty("Referer", referer)
                    val cookie = requestContext.third
                    if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
                }
                val code = connection.responseCode
                if (code !in 200..299) throw java.io.IOException("HTTP $code")
                val maxBytes = 25L * 1024L * 1024L
                if (connection.contentLengthLong > maxBytes) throw java.io.IOException("image too large")
                connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw java.io.IOException("image too large")
                        output.write(buffer, 0, n)
                    }
                    output.toByteArray().takeIf { it.isNotEmpty() }
                }
            } catch (e: Exception) {
                onLog("native image fetch failed: ${e.message ?: e.javaClass.simpleName}")
                null
            } finally {
                connection?.disconnect()
            }
        }

        if (nativeBytes != null && nativeBytes.isNotEmpty()) return nativeBytes

        // Browser-context fallback for sites requiring page fetch / service-worker handling.
        // This must run after the native request fails; do not return early above.
        val pageBytes = fetchViaPage(wv, url)
        if (pageBytes != null && pageBytes.isNotEmpty()) return pageBytes
        return null
    }

    private suspend fun fetchViaPage(wv: WebView, url: String): ByteArray? {
        // blob: URLs cannot be opened by HttpURLConnection; let the page resolve them.
        val reqId = "f" + System.nanoTime().toString(36)
        return try {
            val json = suspendCoroutine<String> { cont ->
                var resumed = false
                pendingFetches[reqId] = { r ->
                    if (!resumed) { resumed = true; cont.resume(r) }
                }
                wv.post {
                    wv.evaluateJavascript(
                        "WebToonBridge.fetch(" + JSONObject.quote(reqId) + "," + JSONObject.quote(url) + ");",
                        null
                    )
                }
                scope.launch {
                    delay(12_000)
                    pendingFetches.remove(reqId)?.invoke("{\"ok\":false,\"reason\":\"timeout\"}")
                }
            }
            val o = JSONObject(json)
            if (!o.optBoolean("ok")) {
                onLog("page image fetch failed: ${o.optString("reason")}")
                null
            } else Base64.decode(o.getString("base64"), Base64.DEFAULT)
        } catch (e: Exception) {
            onLog("page image fetch failed: ${e.message ?: e.javaClass.simpleName}")
            null
        } finally {
            pendingFetches.remove(reqId)
        }
    }

    // ================================================================ novel mode

    private var novelItems: List<Pair<Int, String>> = emptyList()

    private fun handleNovelScan(token: String, obj: JSONObject) {
        val items = ArrayList<Pair<Int, String>>()
        val arr = obj.optJSONArray("items")
        arr?.let {
            for (i in 0 until it.length()) {
                val o = it.getJSONObject(i)
                items.add(Pair(o.optInt("idx"), o.optString("text")))
            }
        }
        novelItems = items
        _state.value = _state.value.copy(
            phase = "scanned", novelMode = true, novelParas = items.size,
            status = "Novel chapter: ${items.size} paragraphs",
            diagnostics = listOf("novel ok: ${items.size} paragraphs")
        )
        onLog("[$token] novel mode: ${items.size} paragraphs")
    }

    // ================================================================ translation

    /**
     * Sends the chapter (novel paragraphs or OCR regions) to the chatbot
     * WebView using the adapter for the CURRENT chatbot page, then inserts
     * the validated translations back into the reader automatically.
     */
    fun translateViaChatbot(botWv: WebView, adapter: com.webtoon.translator.core.BotAdapter, chatHelper: ChatbotHelper) {
        val token = jobToken
        if (token.isEmpty()) { onLog("No active scan — press Scan Chapter first"); _state.value = _state.value.copy(phase = "failed", status = "No active scan"); return }
        activeJob = scope.launch {
            chatbotMutex.withLock {
            if (jobToken != token) return@withLock
            _state.value = _state.value.copy(phase = "translating", status = "Building prompt…")
            try {
                val prompt = buildPromptForChapter()
                if (prompt == null) { fail(token, "Nothing to translate — scan a chapter first"); return@launch }
                onLog("[$token] prompt ${prompt.length} chars → ${adapter.name}")

                val ins = chatHelper.insert(botWv, adapter, prompt)
                if (!ins.ok) { fail(token, "Chatbot editor insertion failed: ${ins.reason}"); return@launch }
                _state.value = _state.value.copy(status = "Prompt inserted — sending…")

                val resp = chatHelper.sendAndWait(botWv, adapter, timeoutMs = 150_000)
                if (resp == null) { fail(token, "No chatbot response (timeout, not logged in, or send failed)"); return@launch }
                onLog("[$token] response ${resp.length} chars")

                val parsed = Prompts.parseResponse(resp)
                val expected = expectedRegionIds()
                val match = TranslationMatcher.match(expected, parsed)
                if (match.matched.isEmpty()) {
                    fail(token, "Chatbot response unusable — unknown=${match.unknownIds.size} missing=${match.missingIds.size} dup=${match.duplicateIds.size}")
                    return@launch
                }
                if (match.missingIds.isNotEmpty()) onLog("[$token] warning: ${match.missingIds.size} IDs missing from response")
                lastTranslations = match.matched.toList()
                lastMatchComplete = match.complete

                _state.value = _state.value.copy(
                    phase = "inserting",
                    translationsReady = match.matched.size,
                    translationsTotal = expected.size
                )
                val inserted = insertTranslations(readerWv() ?: return@launch, match.matched)
                _state.value = _state.value.copy(
                    phase = "done",
                    status = "Inserted $inserted/${match.matched.size} translations",
                    insertedCount = inserted
                )
                onLog("[$token] insertion: $inserted items")
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = _state.value.copy(phase = "cancelled")
            } catch (e: Exception) {
                fail(token, "Translate failed: ${e.message}")
            }
            }
        }
    }

    /** Export only a fully matched translation; incomplete chatbot responses are not cached. */
    fun exportPreparedChapter(chapterUrl: String, botId: String): PreparedChapter? {
        val st = _state.value
        if (st.phase != "done" || !lastMatchComplete || lastTranslations.isEmpty() ||
            st.translationsTotal == 0 || lastTranslations.size != st.translationsTotal) return null
        return PreparedChapter(
            url = ChapterTranslationCache.normalizeUrl(chapterUrl), createdAt = System.currentTimeMillis(),
            sourceLang = AppSettings.sourceLang, targetLang = AppSettings.targetLang,
            ocrScript = AppSettings.ocrScript, promptVersion = Prompts.PROMPT_VERSION, botId = botId,
            novelMode = st.novelMode,
            paragraphs = novelItems.map { PreparedParagraph(it.first, it.second) },
            images = st.images.map { it.copy(regions = it.regions.toMutableList()) },
            translations = lastTranslations.toList()
        )
    }

    /** Apply a prepared chapter without running scan/OCR/chatbot again. */
    fun applyPreparedChapter(prepared: PreparedChapter) {
        activeJob?.cancel()
        val token = "cached-" + System.currentTimeMillis().toString(36)
        jobToken = token
        lastTranslations = prepared.translations.toList()
        lastMatchComplete = true
        novelItems = prepared.paragraphs.map { it.index to it.text }
        val images = prepared.images.map { it.copy(regions = it.regions.toMutableList()) }
        _state.value = JobUiState(
            jobId = token, phase = "inserting", status = "Applying prepared chapter translation…",
            images = images, novelParas = novelItems.size, novelMode = prepared.novelMode,
            translationsReady = prepared.translations.size, translationsTotal = prepared.translations.size
        )
        activeJob = scope.launch {
            try {
                val reader = readerWv() ?: run { fail(token, "Reader webview not ready"); return@launch }
                val liveUrl = withContext(Dispatchers.Main) { ChapterTranslationCache.normalizeUrl(reader.url.orEmpty()) }
                if (liveUrl != prepared.url) {
                    fail(token, "Prepared chapter URL no longer matches the reader")
                    return@launch
                }
                val inserted = insertTranslations(reader, prepared.translations)
                if (jobToken != token) return@launch
                _state.value = _state.value.copy(
                    phase = "done", status = "Prepared translation applied: $inserted/${prepared.translations.size}",
                    insertedCount = inserted
                )
                onLog("[$token] applied prepared translation: $inserted/${prepared.translations.size}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (jobToken == token) _state.value = _state.value.copy(phase = "cancelled", status = "Cancelled")
                throw e
            } catch (e: Exception) {
                fail(token, "Could not apply prepared translation: ${e.message}")
            }
        }
    }

    private fun buildPromptForChapter(): String? =
        if (_state.value.novelMode) {
            if (novelItems.isEmpty()) null
            else Prompts.buildPrompt(
                novelItems.mapIndexed { i, (idx, text) ->
                    TextRegion("novel", "p-$idx", text, 0f, i.toFloat(), 0f, 0f, i)
                },
                AppSettings.sourceLang, AppSettings.targetLang
            )
        } else {
            val regions = _state.value.images.flatMap { it.regions }
            if (regions.isEmpty()) null
            else Prompts.buildPrompt(regions, AppSettings.sourceLang, AppSettings.targetLang)
        }

    private fun expectedRegionIds(): List<String> =
        if (_state.value.novelMode) novelItems.map { "p-${it.first}" }
        else _state.value.images.flatMap { img -> img.regions.map { it.regionId } }

    /** Novels: paragraph replacement. Images: Bengali overlays on the image elements. */
    private suspend fun insertTranslations(reader: WebView, items: List<TranslationItem>): Int {
        return if (_state.value.novelMode) {
            withContext(Dispatchers.Main) {
                val updates = items.mapNotNull { item ->
                    val idx = item.regionId.removePrefix("p-").toIntOrNull() ?: return@mapNotNull null
                    val orig = novelItems.firstOrNull { it.first == idx }?.second ?: ""
                    JSONObject().put("idx", idx).put("text", item.translatedText).put("original", orig)
                }
                val payload = JSONObject().put("updates", JSONArray(updates)).toString()
                try { JSONObject(eval(reader, WebScripts.APPLY_NOVEL, payload)).optInt("applied", 0) } catch (_: Exception) { 0 }
            }
        } else {
            withContext(Dispatchers.Main) { eval(reader, WebScripts.INJECT_OVERLAY_LAYER) }
            var total = 0
            // Models sometimes omit imageId or echo the fallback "novel".
            // Region IDs are the reliable key: resolve each translation to the
            // scanned image that actually owns that region before grouping.
            val scannedImages = _state.value.images
            val byImage = items.mapNotNull { item ->
                val owner = scannedImages.firstOrNull { img ->
                    img.regions.any { region -> region.regionId == item.regionId }
                }
                if (owner == null) {
                    onLog("overlay skip: no image owns region ${item.regionId} (imageId=${item.imageId})")
                    null
                } else owner.imageId to item
            }.groupBy({ it.first }, { it.second })
            for ((imageId, group) in byImage) {
                val img = scannedImages.firstOrNull { it.imageId == imageId } ?: continue
                val regionsPayload = group.mapNotNull { item ->
                    val region = img.regions.firstOrNull { it.regionId == item.regionId } ?: return@mapNotNull null
                    val mapped = com.webtoon.translator.core.OverlayMath.map(
                        region.x, region.y, region.w, region.h,
                        img.naturalWidth, img.naturalHeight, 1f, 1f
                    )
                    JSONObject()
                        .put("id", region.regionId)
                        .put("l", mapped.leftRatio.toDouble()).put("t", mapped.topRatio.toDouble())
                        .put("w", mapped.widthRatio.toDouble()).put("h", mapped.heightRatio.toDouble())
                        .put("text", item.translatedText)
                }
                if (regionsPayload.isEmpty()) continue
                val payload = JSONObject()
                    .put("imageId", imageId).put("src", img.src)
                    .put("opacity", AppSettings.overlayOpacity.toDouble())
                    .put("fontScale", AppSettings.overlayFontScale.toDouble())
                    .put("regions", JSONArray(regionsPayload)).toString()
                try { total += JSONObject(eval(reader, WebScripts.APPLY_OVERLAYS, payload)).optInt("created", 0) } catch (_: Exception) {}
            }
            total
        }
    }

    fun toggleOverlays(show: Boolean) {
        val reader = readerWv() ?: return
        scope.launch(Dispatchers.Main) {
            eval(reader, if (show) WebScripts.INJECT_OVERLAY_LAYER else WebScripts.CLEAR_OVERLAYS)
        }
    }

    fun clearCache() {
        val n = TranslationCache(cacheDir.resolve("wt_cache")).clear()
        onLog("Cache cleared: $n entries")
        _state.value = _state.value.copy(diagnostics = listOf("cache cleared: $n entries"))
    }

    fun retryFailedImages() {
        val token = jobToken
        if (token.isEmpty()) return
        activeJob = scope.launch {
            val wv = readerWv() ?: return@launch
            val failed = _state.value.images.filter { it.state == "OCR_FAILED" || it.state == "OCR_EMPTY" }
            _state.value = _state.value.copy(status = "Retrying ${failed.size} images…")
            for (img in failed) {
                if (jobToken != token) return@launch
                updateImage(token, img.imageId) { it.retries++; it.error = "" }
                processImage(token, wv, img)
            }
            _state.value = _state.value.copy(status = "Retry finished")
        }
    }

    // ================================================================ helpers

    private fun pushImages(token: String, images: List<OcrImage>) {
        if (jobToken != token) return
        _state.value = _state.value.copy(images = images, novelMode = false, novelParas = 0)
    }

    private fun updateImage(token: String, imageId: String, update: (OcrImage) -> Unit) {
        if (jobToken != token) return
        _state.value = _state.value.copy(
            images = _state.value.images.map { if (it.imageId == imageId) { update(it); it } else it }
        )
    }

    private fun markPhase(token: String, status: String, phase: String) {
        if (jobToken != token) return
        _state.value = _state.value.copy(phase = phase, status = status)
    }

    private fun fail(token: String, msg: String) {
        if (jobToken != token) return
        onLog("[$token] FAIL: $msg")
        _state.value = _state.value.copy(phase = "failed", status = msg)
    }

    /** Evaluates JS on the main thread. Zero-arg scripts are uninvoked templates: (script)(args…) */
    suspend fun eval(wv: WebView, script: String, arg: String? = null): String =
        withContext(Dispatchers.Main) {
            suspendCoroutine { cont ->
                // JSON payloads (ChatbotHelper adapters) must be passed as objects,
                // not quoted strings. Primitive arguments such as "700" remain strings.
                val trimmedArg = arg?.trim()
                val jsArg = if (trimmedArg != null &&
                    ((trimmedArg.startsWith("{") && trimmedArg.endsWith("}")) ||
                     (trimmedArg.startsWith("[") && trimmedArg.endsWith("]")))) {
                    trimmedArg
                } else if (arg != null) JSONObject.quote(arg) else null
                val js = if (jsArg == null) script else "(" + script + ")(" + jsArg + ")"
                wv.evaluateJavascript(js) { raw ->
                    val value = raw ?: ""
                    // evaluateJavascript JSON-encodes string results; unwrap that layer
                    // before parsing the JSON returned by page-side helper scripts.
                    val decoded = try {
                        val v = org.json.JSONTokener(value).nextValue()
                        if (v is String) v else value
                    } catch (_: Exception) { value }
                    cont.resume(decoded)
                }
            }
        }

    fun shutdown() {
        scope.launch { activeJob?.cancelAndJoin() }
        providers.values.forEach { it.close() }
        providers.clear()
    }
}

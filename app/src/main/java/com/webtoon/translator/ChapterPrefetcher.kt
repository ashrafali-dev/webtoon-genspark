package com.webtoon.translator

import android.webkit.WebView
import com.webtoon.translator.core.ChatbotAdapters
import com.webtoon.translator.web.ChatbotHelper
import com.webtoon.translator.web.WebScripts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File

/** Background pipeline for the next episode; never scrolls or mutates the visible reader. */
class ChapterPrefetcher(
    private val readerWv: WebView,
    private val prefetchWv: WebView,
    private val readerEngine: Engine,
    private val prefetchEngine: Engine,
    private val botWv: WebView,
    private val chatHelper: ChatbotHelper,
    private val scope: CoroutineScope,
    cacheDir: File,
    private val onLog: (String) -> Unit
) {
    private val cache = ChapterTranslationCache(File(cacheDir, "prepared-chapters"))
    private var currentUrl = ""
    private var lastHandledCurrentUrl = ""
    private var targetUrl = ""
    private var prefetchJob: Job? = null
    private var loadTarget = ""
    private var loadWaiter: CompletableDeferred<String>? = null

    fun onCurrentPageStarted(url: String) {
        if (url.isNotBlank()) lastHandledCurrentUrl = ""
    }

    fun onCurrentPageFinished(url: String) {
        val normalized = ChapterTranslationCache.normalizeUrl(url)
        if (normalized.isBlank() || normalized == lastHandledCurrentUrl) return
        lastHandledCurrentUrl = normalized
        currentUrl = normalized
        scope.launch {
            val ready = cache.get(normalized, AppSettings.sourceLang, AppSettings.targetLang, AppSettings.ocrScript)
            if (currentUrl != normalized) return@launch
            if (ready != null) {
                onLog("Prefetch cache hit — applying prepared translation")
                readerEngine.applyPreparedChapter(ready)
            } else if (targetUrl == normalized && prefetchJob?.isActive == true) {
                onLog("This chapter is still being prepared in the background")
                return@launch
            }
            scheduleNext(normalized)
        }
    }

    fun onPrefetchPageFinished(url: String) {
        val waiter = loadWaiter ?: return
        val candidate = ChapterTranslationCache.normalizeUrl(url)
        val current = ChapterTranslationCache.normalizeUrl(prefetchWv.url.orEmpty())
        val expectedHost = try { java.net.URL(loadTarget).host } catch (_: Exception) { "" }
        val actualHost = try { java.net.URL(candidate).host } catch (_: Exception) { "" }
        if (candidate == current && candidate.isNotBlank() &&
            (candidate == ChapterTranslationCache.normalizeUrl(loadTarget) ||
                (expectedHost.isNotBlank() && expectedHost == actualHost))) {
            waiter.complete(url)
        }
    }

    fun clearCache() {
        onLog("Prepared chapter cache cleared: " + cache.clear() + " entries")
    }

    private suspend fun scheduleNext(forUrl: String) {
        if (currentUrl != forUrl) return
        val raw = try { readerEngine.eval(readerWv, WebScripts.FIND_NEXT_CHAPTER_URL) }
        catch (e: Exception) {
            onLog("Could not inspect next chapter link: " + (e.message ?: "error"))
            return
        }
        val next = try { JSONObject(raw).optString("url").trim() } catch (_: Exception) { "" }
        if (next.isBlank()) {
            onLog("No next-chapter link detected on this page")
            return
        }
        val normalized = ChapterTranslationCache.normalizeUrl(next)
        if (normalized.isBlank() || normalized == forUrl) return
        if (cache.get(normalized, AppSettings.sourceLang, AppSettings.targetLang, AppSettings.ocrScript) != null) {
            onLog("Next chapter already prepared — cache will be used when opened")
            return
        }
        if (targetUrl == normalized && prefetchJob?.isActive == true) return

        val oldJob = prefetchJob
        if (oldJob?.isActive == true) {
            targetUrl = ""
            oldJob.cancelAndJoin()
            prefetchEngine.cancelAll()
        }
        targetUrl = normalized
        onLog("Prefetch queued: " + normalized)
        prefetchJob = scope.launch { prepare(normalized) }
    }

    private suspend fun prepare(requestedUrl: String) {
        val self = currentCoroutineContext()[Job]
        var preparedUrl = requestedUrl
        var shouldScheduleSuccessor = false
        try {
            val waiter = CompletableDeferred<String>()
            loadTarget = requestedUrl
            loadWaiter = waiter
            withContext(Dispatchers.Main) { prefetchWv.loadUrl(requestedUrl) }
            val loadedUrl = withTimeout(75_000L) { waiter.await() }
            preparedUrl = ChapterTranslationCache.normalizeUrl(loadedUrl)
            loadWaiter = null
            delay(400)

            onLog("Scanning next chapter in background")
            prefetchEngine.scanChapter()
            val scan = withTimeout(6 * 60_000L) {
                prefetchEngine.state.first {
                    it.phase == "ocr" || it.phase == "scanned" || it.phase == "failed" || it.phase == "cancelled"
                }
            }
            if (scan.phase == "failed" || scan.phase == "cancelled") {
                onLog("Prefetch scan stopped: " + scan.status)
                return
            }
            if (!currentCoroutineContext().isActive) return

            val adapter = ChatbotAdapters.detect(botWv.url)
            if (adapter == null) {
                onLog("Prefetch OCR ready, but no supported chatbot page is open")
                return
            }
            onLog("Translating next chapter with " + adapter.name)
            prefetchEngine.translateViaChatbot(botWv, adapter, chatHelper)
            val translated = withTimeout(9 * 60_000L) {
                prefetchEngine.state.first {
                    it.phase == "done" || it.phase == "failed" || it.phase == "cancelled"
                }
            }
            if (translated.phase != "done") {
                onLog("Prefetch translation not ready: " + translated.status)
                return
            }

            val prepared = prefetchEngine.exportPreparedChapter(preparedUrl, adapter.id)
            if (prepared == null) {
                onLog("Prefetch not cached: translation was partial or incomplete")
                return
            }
            cache.put(prepared)
            onLog("Chapter prepared: " + prepared.translations.size + " translated items cached")
            if (currentUrl == prepared.url) {
                readerEngine.applyPreparedChapter(prepared)
                shouldScheduleSuccessor = true
            }
        } catch (e: CancellationException) {
            onLog("Prefetch cancelled")
            throw e
        } catch (e: Exception) {
            onLog("Prefetch failed: " + (e.message ?: e.javaClass.simpleName))
        } finally {
            loadWaiter = null
            if (targetUrl == requestedUrl) targetUrl = ""
            if (prefetchJob === self) prefetchJob = null
            if (shouldScheduleSuccessor && currentUrl.isNotBlank()) {
                scope.launch { scheduleNext(currentUrl) }
            }
        }
    }

    fun shutdown() {
        loadWaiter?.cancel()
        prefetchJob?.cancel()
        prefetchEngine.cancelAll()
    }
}

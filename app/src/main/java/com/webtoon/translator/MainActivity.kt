package com.webtoon.translator

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.webtoon.translator.core.ChatbotAdapters
import com.webtoon.translator.web.ChatbotHelper
import com.webtoon.translator.web.configureWebview
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single-activity app:
 *  bar     = browser (URL + Go)
 *  toolbar = nav + pipeline actions
 *  reader  = chapter WebView (top, weight 1.2)
 *  bottom  = split pane: chatbot WebView + tabs (OCR / Diagnostics / Settings)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var engine: Engine
    private lateinit var chatHelper: ChatbotHelper

    private lateinit var urlInput: EditText
    private lateinit var readerWv: WebView
    private lateinit var botWv: WebView
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var ocrText: TextView
    private lateinit var diagText: TextView
    private lateinit var botSpinner: AutoCompleteTextView
    private lateinit var bottomPane: LinearLayout
    private lateinit var botContainer: LinearLayout
    private lateinit var readerFrame: LinearLayout
    private lateinit var settingsView: View
    private val tabScrolls = ArrayList<ScrollView>()
    private var splitOpen = true
    private val logs = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.init(this)
        engine = Engine(readerWv = { readerWv }, scope = lifecycleScope, onLog = ::log)
        engine.setCacheDir(File(cacheDir, "engine"))
        chatHelper = ChatbotHelper { wv, script, arg -> engine.eval(wv, script, arg) }
        setContentView(buildUi())
        setupWebViews()
        loadHome()
        observeState()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun barBtn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(8), 0, dp(8), 0)
        backgroundTintList = android.content.res.ColorStateList.valueOf(0x33FFFFFF)
        setTextColor(0xFFFFFFFF.toInt())
        setOnClickListener { onClick() }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.setBackgroundColor(0xFF0F1412.toInt())

        // browser bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(0xFF0E7A5F.toInt())
        }
        urlInput = EditText(this).apply {
            hint = getString(R.string.url_hint)
            setSingleLine(true)
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0x88FFFFFF.toInt())
            setBackgroundColor(0x33FFFFFF.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnEditorActionListener { _, _, _ -> go(); true }
        }
        bar.addView(urlInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(barBtn("Go") { go() })

        // action toolbar
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
            setBackgroundColor(0xFF0B4F41.toInt())
        }
        listOf(
            "◀" to { if (readerWv.canGoBack()) readerWv.goBack() },
            "▶" to { if (readerWv.canGoForward()) readerWv.goForward() },
            "⟳" to { readerWv.reload() },
            "Scan" to { engine.scanChapter() },
            "Translate" to { doTranslate() },
            "Stop" to { engine.cancelAll() },
            "Retry" to { engine.retryFailedImages() },
            "Overlay" to {
                AppSettings.overlayOn = !AppSettings.overlayOn
                engine.toggleOverlays(AppSettings.overlayOn)
                toast("Overlays " + if (AppSettings.overlayOn) "ON" else "OFF")
            },
            "Cache" to { engine.clearCache() },
            "Split" to { toggleSplit() }
        ).forEach { (label, fn) -> row2.addView(barBtn(label, fn)) }

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        statusText = TextView(this).apply {
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setTextColor(0xFFBFE8DB.toInt())
            textSize = 12f
        }

        readerFrame = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // bottom split pane
        bottomPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF131917.toInt())
        }
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        botSpinner = AutoCompleteTextView(this).apply {
            hint = "Chatbot"
            textSize = 12f
            setSingleLine(true)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { showBotPicker() }
        }
        tabRow.addView(botSpinner, LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.WRAP_CONTENT))
        listOf("OCR", "Diagnostics", "Settings").forEachIndexed { i, name ->
            tabRow.addView(barBtn(name) { switchTab(i) }.apply {
                backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF1E2A26.toInt())
                setTextColor(0xFFD5E8E1.toInt())
            })
        }

        ocrText = TextView(this).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setTextColor(0xFFE8F3EF.toInt())
            textSize = 13f
            setTextIsSelectable(true)
        }
        diagText = TextView(this).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setTextColor(0xFFC9D8D2.toInt())
            textSize = 11f
            setTextIsSelectable(true)
        }
        settingsView = buildSettingsView()

        botContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        bottomPane.addView(tabRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bottomPane.addView(botContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.55f))
        listOf(ocrText, diagText, settingsView).forEach { content ->
            val sc = ScrollView(this)
            sc.addView(content)
            sc.visibility = View.GONE
            tabScrolls.add(sc)
            bottomPane.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.45f))
        }
        switchTab(0)

        root.addView(bar)
        root.addView(row2)
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
        root.addView(statusText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(readerFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.4f))
        root.addView(bottomPane, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340)))
        return root
    }

    private fun buildSettingsView(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        fun label(t: String): TextView = TextView(this).apply {
            text = t; textSize = 12f; setTextColor(0xFF9FC3B8.toInt())
        }
        fun edit(hint: String, get: () -> String, set: (String) -> Unit, password: Boolean = false): EditText =
            EditText(this).apply {
                setTextSize(13f)
                setSingleLine(true)
                this.hint = hint
                setHintTextColor(0xFF6E8A82.toInt())
                setTextColor(0xFFE8F3EF.toInt())
                if (password) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                setText(get())
                setOnFocusChangeListener { _, has -> if (!has && text.toString() != get()) set(text.toString()) }
            }

        box.addView(label("Source language (auto / English / Chinese / Japanese / Korean)"))
        box.addView(edit("auto", { AppSettings.sourceLang }, { AppSettings.sourceLang = it.trim() }))
        box.addView(label("Target language (default Bengali)"))
        box.addView(edit("Bengali", { AppSettings.targetLang }, { AppSettings.targetLang = it.trim() }))
        box.addView(label("OCR script — auto / latin / chinese / japanese / korean"))
        box.addView(edit("auto", { AppSettings.ocrScript }, { AppSettings.ocrScript = it.trim().lowercase() }))
        box.addView(label("API mode (optional) — OpenAI-compatible base URL / model / key"))
        box.addView(edit("https://api.openai.com/v1", { AppSettings.apiBaseUrl }, { AppSettings.apiBaseUrl = it.trim() }))
        box.addView(edit("gpt-4o-mini", { AppSettings.apiModel }, { AppSettings.apiModel = it.trim() }))
        box.addView(edit("API key (encrypted on device, never logged)", { AppSettings.apiKeyOpenAi }, { AppSettings.apiKeyOpenAi = it.trim() }, true))
        box.addView(label("Overlay font scale / bubble opacity 0-1"))
        box.addView(edit("1.0", { AppSettings.overlayFontScale.toString() }, { AppSettings.overlayFontScale = it.toFloatOrNull() ?: 1f }))
        box.addView(edit("0.85", { AppSettings.overlayOpacity.toString() }, { AppSettings.overlayOpacity = it.toFloatOrNull() ?: 0.85f }))
        return box
    }

    private fun switchTab(i: Int) {
        tabScrolls.forEachIndexed { n, sc -> sc.visibility = if (n == i) View.VISIBLE else View.GONE }
    }

    // ------------------------------------------------------------ webviews

    @SuppressLint("SetTextI18n")
    private fun setupWebViews() {
        readerWv = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            configureWebview(onProgress = { p -> runOnUiThread { this@MainActivity.progress.progress = p } })
        }
        readerFrame.addView(readerWv)
        engine.bridgeInstance().host = readerWv

        botWv = WebView(this).apply {
            configureWebview(onProgress = {}, onPage = { u -> log("chatbot: $u") })
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        botContainer.addView(botWv)
        loadBot()
    }

    private fun loadBot() {
        val adapter = ChatbotAdapters.byId(AppSettings.activeBot)
        botSpinner.setText(adapter.name, false)
        botWv.loadUrl(adapter.url)
    }

    private fun loadHome() {
        urlInput.setText(AppSettings.homeUrl)
        readerWv.loadUrl(AppSettings.homeUrl)
    }

    private fun go() {
        var u = urlInput.text.toString().trim()
        if (u.isEmpty()) return
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        AppSettings.homeUrl = u
        readerWv.loadUrl(u)
    }

    private fun doTranslate() {
        val adapter = ChatbotAdapters.detect(botWv.url)
        if (adapter == null) {
            toast("Open a supported chatbot in the bottom pane (and log in there) first")
            return
        }
        engine.translateViaChatbot(botWv, adapter, chatHelper)
    }

    private fun showBotPicker() {
        val names = ChatbotAdapters.all.map { it.name }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Chatbot for the bottom pane")
            .setItems(names) { _, which ->
                AppSettings.activeBot = ChatbotAdapters.all[which].id
                loadBot()
            }.show()
    }

    private fun toggleSplit() {
        splitOpen = !splitOpen
        bottomPane.visibility = if (splitOpen) View.VISIBLE else View.GONE
    }

    private fun observeState() {
        lifecycleScope.launch {
            engine.state.collect { st ->
                statusText.text = "[${st.phase}] ${st.status}"
                ocrText.text = buildString {
                    if (st.novelMode) {
                        append("Novel mode — ${st.novelParas} paragraphs detected")
                    } else {
                        append("Image mode — ${st.images.size} images\n\n")
                        st.images.forEach { img ->
                            append("${img.imageId} [${img.state}] regions=${img.regionCount} chars=${img.charCount}")
                            if (img.error.isNotEmpty()) append(" — ${img.error}")
                            append('\n')
                            img.regions.take(8).forEach { r ->
                                append("   ${r.regionId} (${r.x.toInt()},${r.y.toInt()} ${r.w.toInt()}x${r.h.toInt()}): ${r.text.take(60)}\n")
                            }
                            if (img.regions.size > 8) append("   … +${img.regions.size - 8} more\n")
                        }
                    }
                    if (st.translationsTotal > 0) {
                        append("\nTranslations: ${st.translationsReady}/${st.translationsTotal} matched by ID, ${st.insertedCount} inserted")
                    }
                }
                diagText.text = buildString {
                    append("Job ${st.jobId.ifEmpty { "—" }} · phase ${st.phase}\n\n")
                    logs.takeLast(60).forEach { append(it).append('\n') }
                }
            }
        }
    }

    private fun log(line: String) {
        logs.add("${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}  $line")
        if (logs.size > 500) logs.removeAt(0)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onBackPressed() {
        if (readerWv.canGoBack()) readerWv.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        engine.shutdown()
        readerWv.destroy()
        botWv.destroy()
        super.onDestroy()
    }
}

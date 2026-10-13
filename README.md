# Webtoon Translator (Android)

Browse web novels / webtoons / manga / manhua / manhwa in an in-app browser, extract text
(DOM paragraphs or on-image lettering via on-device OCR), send it to a chatbot with stable
IDs, and **automatically place the Bengali translation back** — into the exact paragraph
element or as positioned overlays on the original speech bubbles.

Built from scratch. Kotlin, single-activity, MVVM-ish (Engine + StateFlow), Gradle Kotlin DSL.

## Architecture

```
app/src/main/java/com/webtoon/translator/
  MainActivity.kt          # browser + split pane (reader / chatbot) + tabs + settings
  Engine.kt                # job manager: scan → OCR → translate → insert, stale-callback safe
  AppSettings.kt           # prefs + EncryptedSharedPreferences (API keys never logged)
  core/
    OcrModels.kt           # TextRegion/OcrImage/TranslationItem, stable IDs, ID matcher
    Prompts.kt             # prompt builder + response parser (JSON → marker fallback)
    OverlayMath.kt         # bitmap→displayed-box coordinate mapping (pure math)
    TranslationEngine.kt   # optional API mode (OpenAI-compatible, Gemini)
    TranslationCache.kt    # content-hash keyed cache (engine/lang/provider/prompt version)
    ChatbotAdapters.kt     # per-chatbot selector lists (ChatGPT, Gemini, Claude, DeepSeek)
  ocr/OcrEngine.kt         # ML Kit v2 provider (latin/chinese/japanese/korean) + decoder
  web/
    WebScripts.kt          # all injected JS: image discovery, scroll, fetch, overlays, novel
    WebViewClients.kt      # WebView config + ImageBridge (page-context fetch, blob:/data: safe)
    ChatbotHelper.kt       # editor insert → verify → submit → poll new response → read
app/src/test/java/...      # 23 unit tests
```

## Build

```
./gradlew test            # 23 tests
./gradlew assembleDebug   # app-debug.apk
./gradlew assembleRelease # app-release.apk (debug-signed unless KEYSTORE_* env vars set)
```

Requirements: JDK 17, Android SDK 34 (set in `local.properties`: `sdk.dir=/path/to/sdk`).

## OCR engine

ML Kit Text Recognition v2 — **bundled models** (`com.google.mlkit:text-recognition*`),
fully on-device/offline, ~4 MB per script per ABI. Scripts: Latin, Chinese (Simplified +
Traditional), Japanese, Korean. Select the script in Settings before scanning.
Limitations: vertical Japanese is recognized as separate horizontal lines (no tate-chūsho
reconstruction); stylized lettering accuracy varies; confidence comes from the line level.

## How automatic insertion works

1. Scan: novel paragraphs get stable IDs `p-<idx>`; image regions get `r-<img>-<line>`
   with bounding boxes in the original bitmap coordinate system.
2. Translate: prompt lists every item under a `<<<regionId>>>` marker and demands JSON
   `{"translations":[{"imageId","regionId","translatedText"}]}`. The parser accepts clean
   JSON, fenced JSON, or marker segments; the matcher matches **by ID only** (never by
   array position), reporting unknown/missing/duplicate IDs.
3. Insert:
   - **Novels**: the paragraph at `idx` is located again in the best content container and
     its `textContent` is replaced (original text kept as a guard against layout changes).
   - **Webtoons/manga**: the bbox is mapped to fractions of the image element's displayed
     box (`OverlayMath.map`), and Bengali overlay divs are attached to the image's parent,
     so they stay glued to the panel while scrolling/zooming. Toggle with the Overlay
     button; per-image cleanup prevents duplicate overlays.
4. Item counts only as inserted after the page-side JS confirms the write.

## Chatbot modes

- **WebView mode (default)**: log into the chatbot in the bottom pane, open your chapter in
  the reader, Scan → Translate. The adapter finds the editor (per-site selector lists),
  inserts with `execCommand('insertText')` (native value setter for textareas), verifies
  the editor actually contains the text, clicks Send (Enter fallback), then polls for a
  NEW assistant message that is stable and not "busy" — no fixed sleeps.
- **API mode (optional)**: set base URL / model / key in Settings (OpenAI-compatible or
  Gemini). Keys live in EncryptedSharedPreferences and are never logged or exposed to
  page JavaScript.

## Known limitations

- Webview chatbot automation is layout-dependent; site redesigns need selector updates
  (adapters are one file). Login/CAPTCHA is never bypassed.
- Vertical Japanese reads line-by-line; reading-order column grouping is future work.
- The chatbot may refuse or truncate very long batches; use smaller chapters on failure.
- Real-site behavior (specific manga readers, login walls) is unverified without a device.

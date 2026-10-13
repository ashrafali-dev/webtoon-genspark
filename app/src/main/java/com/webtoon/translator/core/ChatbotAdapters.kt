package com.webtoon.translator.core

import org.json.JSONObject

/**
 * Per-chatbot adapter registry. Each adapter describes:
 *  - how to recognize the bot host
 *  - CSS candidates for the message editor, send button, "stop" indicator and assistant messages
 *  - how to insert text (execCommand vs native value setter)
 *  - markers stripped from responses ("ChatGPT said:" etc.)
 *
 * Selectors are candidate LISTS tried in order — never one universal selector.
 */
data class BotAdapter(
    val id: String,
    val name: String,
    val url: String,
    val hostPatterns: List<String>,
    /** Editor candidates, best first. */
    val editorSelectors: List<String>,
    val sendSelectors: List<String>,
    val stopSelectors: List<String>,
    /** Assistant message element selectors, used to count turns / read the new response. */
    val assistantSelectors: List<String>,
    /** Insertion strategy: "execcommand" (contenteditable) or "value" (textarea/input). */
    val insertStrategy: String = "execcommand",
    /** Hosts that must never be automated (login flows etc. are always respected). */
    val responsePrefixStrip: List<String> = listOf()
) {
    fun matches(host: String): Boolean =
        hostPatterns.any { p -> host == p || host.endsWith(".$p") || host.contains(p) }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("url", url)
}

object ChatbotAdapters {

    val all: List<BotAdapter> = listOf(
        BotAdapter(
            id = "chatgpt",
            name = "ChatGPT",
            url = "https://chatgpt.com/",
            hostPatterns = listOf("chatgpt.com", "chat.openai.com"),
            editorSelectors = listOf(
                "#prompt-textarea",
                "div[contenteditable=\"true\"]#prompt-textarea",
                "form [contenteditable=\"true\"]",
                "div[contenteditable=\"true\"]",
                "textarea[data-id]",
                "textarea"
            ),
            sendSelectors = listOf(
                "[data-testid=\"send-button\"]",
                "button[aria-label*=\"Send\" i]",
                "form button[type=\"submit\"]",
                "#composer-submit-button"
            ),
            stopSelectors = listOf(
                "[data-testid=\"stop-button\"]",
                "button[aria-label*=\"Stop\" i]"
            ),
            assistantSelectors = listOf(
                "[data-message-author-role=\"assistant\"]",
                "[data-message-role=\"assistant\"]",
                "article [data-testid^=\"conversation-turn\"] .markdown",
                ".markdown"
            ),
            insertStrategy = "execcommand"
        ),
        BotAdapter(
            id = "gemini",
            name = "Gemini",
            url = "https://gemini.google.com/app",
            hostPatterns = listOf("gemini.google.com"),
            editorSelectors = listOf(
                "rich-textarea .ql-editor",
                "rich-textarea [contenteditable=\"true\"]",
                "div.ql-editor[contenteditable=\"true\"]",
                "[aria-label=\"Enter a prompt here\"]",
                "[contenteditable=\"true\"][role=\"textbox\"]",
                "[contenteditable=\"true\"]",
                "textarea"
            ),
            sendSelectors = listOf(
                "button[aria-label*=\"Send\" i]",
                "button.send-button",
                "button[data-testid*=\"send\" i]"
            ),
            stopSelectors = listOf(
                "button[aria-label*=\"Stop response\" i]",
                "button[aria-label*=\"Stop\" i]"
            ),
            assistantSelectors = listOf(
                "model-response",
                "message-content",
                ".model-response-text",
                ".response-content"
            ),
            insertStrategy = "execcommand"
        ),
        BotAdapter(
            id = "claude",
            name = "Claude",
            url = "https://claude.ai/new",
            hostPatterns = listOf("claude.ai"),
            editorSelectors = listOf(
                "div[contenteditable=\"true\"].ProseMirror",
                "div.ProseMirror[contenteditable=\"true\"]",
                "[aria-label=\"Write your prompt\"]",
                "div[contenteditable=\"true\"]",
                "textarea"
            ),
            sendSelectors = listOf(
                "button[aria-label=\"Send message\"]",
                "button[aria-label*=\"Send\" i]",
                "button[type=\"submit\"]"
            ),
            stopSelectors = listOf(
                "button[aria-label*=\"Stop\" i]",
                "[data-testid=\"stop-response\"]"
            ),
            assistantSelectors = listOf(
                "[data-testid=\"assistant-message\"]",
                ".font-claude-message",
                "div[data-is-streaming] .standard-actions ~ div",
                ".assistant-contained"
            ),
            insertStrategy = "execcommand"
        ),
        BotAdapter(
            id = "deepseek",
            name = "DeepSeek",
            url = "https://chat.deepseek.com/",
            hostPatterns = listOf("deepseek.com"),
            editorSelectors = listOf(
                "textarea#chat-input",
                "textarea[placeholder]",
                "textarea",
                "div[contenteditable=\"true\"]"
            ),
            sendSelectors = listOf(
                "div[role=\"button\"][aria-disabled=\"false\"]",
                "button[type=\"submit\"]",
                "button[class*=\"send\" i]"
            ),
            stopSelectors = listOf("div[role=\"button\"][aria-disabled=\"false\"] svg.stop"),
            assistantSelectors = listOf(".ds-markdown", ".ds-chat-message--assistant"),
            insertStrategy = "value"
        )
    )

    fun byId(id: String): BotAdapter = all.firstOrNull { it.id == id } ?: all.first()

    fun detect(url: String?): BotAdapter? {
        val host = try {
            android.net.Uri.parse(url ?: "").host?.lowercase() ?: return null
        } catch (_: Exception) { return null }
        return all.firstOrNull { it.matches(host) }
    }
}

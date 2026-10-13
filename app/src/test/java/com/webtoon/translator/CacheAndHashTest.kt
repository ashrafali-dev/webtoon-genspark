package com.webtoon.translator

import com.webtoon.translator.core.ContentHash
import com.webtoon.translator.core.TranslationCache
import com.webtoon.translator.core.TranslationItem
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CacheAndHashTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun cacheRoundTrip() {
        val c=TranslationCache(tmp.newFolder())
        c.put(listOf(TranslationItem("image-000","r-000-000","টেক্সট")),"hash1","mlkit-v2-latin","auto","Bengali","webview:chatgpt")
        assertEquals("টেক্সট",c.get("hash1","mlkit-v2-latin","auto","Bengali","webview:chatgpt")!![0].translatedText)
    }
    @Test fun differentInputsMissCache() {
        val c=TranslationCache(tmp.newFolder())
        c.put(listOf(TranslationItem("i","r","x")),"h","mlkit-v2-latin","auto","Bengali","webview:chatgpt")
        assertNull(c.get("h","mlkit-v2-chinese","auto","Bengali","webview:chatgpt"))
        assertNull(c.get("h","mlkit-v2-latin","en","Bengali","webview:chatgpt"))
        assertNull(c.get("h","mlkit-v2-latin","auto","Hindi","webview:chatgpt"))
        assertNull(c.get("h","mlkit-v2-latin","auto","Bengali","webview:gemini"))
    }
    @Test fun overwriteSupportsForceRetranslate() {
        val c=TranslationCache(tmp.newFolder())
        c.put(listOf(TranslationItem("i","r","old")),"h","e","auto","Bengali","p")
        c.put(listOf(TranslationItem("i","r","new")),"h","e","auto","Bengali","p")
        assertEquals("new",c.get("h","e","auto","Bengali","p")!![0].translatedText)
    }
    @Test fun clearRemovesEntries() {
        val c=TranslationCache(tmp.newFolder())
        c.put(listOf(TranslationItem("i","r","x")),"h","e","a","b","p")
        assertEquals(1,c.clear()); assertNull(c.get("h","e","a","b","p"))
    }
    @Test fun hashDiscriminatesChapters() {
        val a=ContentHash.of(listOf("chapter one text")); val b=ContentHash.of(listOf("chapter two text"))
        assertNotEquals(a,b); assertEquals(a,ContentHash.of(listOf("chapter one text")))
    }
}

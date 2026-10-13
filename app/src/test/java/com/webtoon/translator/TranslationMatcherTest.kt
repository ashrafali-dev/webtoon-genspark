package com.webtoon.translator

import com.webtoon.translator.core.TranslationItem
import com.webtoon.translator.core.TranslationMatcher
import org.junit.Assert.*
import org.junit.Test

class TranslationMatcherTest {
    private val expected = listOf("r-000-000", "r-000-001", "r-001-000")
    @Test fun exactMatchIsComplete() {
        val parsed = listOf(TranslationItem("image-000","r-000-000","এক"), TranslationItem("image-000","r-000-001","দুই"), TranslationItem("image-001","r-001-000","তিন"))
        val m = TranslationMatcher.match(expected, parsed)
        assertTrue(m.complete); assertEquals(3,m.matched.size); assertEquals("r-000-000",m.matched[0].regionId)
    }
    @Test fun outOfOrderResponseMatchesById() {
        val parsed = listOf(TranslationItem("image-001","r-001-000","তিন"), TranslationItem("image-000","r-000-000","এক"), TranslationItem("image-000","r-000-001","দুই"))
        val m=TranslationMatcher.match(expected,parsed); assertTrue(m.complete); assertEquals("r-000-000",m.matched[0].regionId)
    }
    @Test fun unknownIdsDetected() {
        val m=TranslationMatcher.match(expected,listOf(TranslationItem("x","bogus-1","?"),TranslationItem("image-000","r-000-000","এক")))
        assertFalse(m.complete); assertEquals(listOf("bogus-1"),m.unknownIds); assertEquals(1,m.matched.size)
    }
    @Test fun missingIdsDetected() {
        val m=TranslationMatcher.match(expected,listOf(TranslationItem("image-000","r-000-000","এক")))
        assertFalse(m.complete); assertEquals(setOf("r-000-001","r-001-000"),m.missingIds.toSet())
    }
    @Test fun duplicateIdsDetected() {
        val parsed=listOf(TranslationItem("image-000","r-000-000","এক"),TranslationItem("image-000","r-000-000","আবার"),TranslationItem("image-000","r-000-001","দুই"),TranslationItem("image-001","r-001-000","তিন"))
        val m=TranslationMatcher.match(expected,parsed); assertFalse(m.complete); assertEquals(listOf("r-000-000"),m.duplicateIds)
    }
    @Test fun neverMatchesByPosition() {
        val p=listOf(TranslationItem("x","wrong-a","এক"),TranslationItem("x","wrong-b","দুই"),TranslationItem("x","wrong-c","তিন"))
        assertEquals(0,TranslationMatcher.match(expected,p).matched.size)
    }
}

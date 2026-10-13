package com.webtoon.translator

import com.webtoon.translator.core.Prompts
import com.webtoon.translator.core.TextRegion
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PromptsTest {
    private val regions = listOf(
        TextRegion("image-000","r-000-000","Hello world",10f,20f,100f,30f,0,"en"),
        TextRegion("image-000","r-000-001","看什么看",10f,60f,100f,30f,1,"zh-Hans"),
        TextRegion("image-001","r-001-000","やった!",5f,5f,80f,25f,0,"ja")
    )
    @Test fun promptContainsMarkersAndTarget() {
        val p=Prompts.buildPrompt(regions,"auto","Bengali")
        for(r in regions) assertTrue(p.contains("<<<${r.regionId}>>>"))
        assertTrue(p.contains("Bengali"))
    }
    @Test fun parsesCleanJson() {
        val json=JSONObject().put("translations",JSONArray()
            .put(JSONObject().put("imageId","image-000").put("regionId","r-000-000").put("translatedText","হ্যালো"))
            .put(JSONObject().put("imageId","image-000").put("regionId","r-000-001").put("translatedText","কী দেখছ")))
        val out=Prompts.parseResponse(json.toString())
        assertEquals(2,out.size); assertEquals("হ্যালো",out[0].translatedText)
    }
    @Test fun parsesFencedJsonInsideCommentary() {
        val raw="Here is the translation:\n```json\n{\"translations\":[{\"regionId\":\"r-000-000\",\"translatedText\":\"বাংলা\"}]}\n```"
        val out=Prompts.parseResponse(raw); assertEquals(1,out.size); assertEquals("বাংলা",out[0].translatedText)
    }
    @Test fun parsesMarkerFallback() {
        val out=Prompts.parseResponse("Sure!\n<<<r-000-000>>>\nহ্যালো\n<<<r-000-001>>>\nকী দেখছ\ndone")
        assertEquals(2,out.size); assertEquals("হ্যালো",out[0].translatedText); assertEquals("r-000-001",out[1].regionId)
    }
    @Test fun preservesMultilineMarkerText() {
        val out=Prompts.parseResponse("<<<r-000-000>>>\nপ্রথম লাইন\nদ্বিতীয় লাইন\n<<<r-000-001>>>\nঠিক আছে")
        assertEquals(2,out.size); assertTrue(out[0].translatedText.contains("দ্বিতীয় লাইন"))
    }
    @Test fun garbageYieldsNothing() {
        assertTrue(Prompts.parseResponse("I cannot translate that.").isEmpty())
        assertTrue(Prompts.parseResponse("").isEmpty())
        assertTrue(Prompts.parseResponse("{\"nope\":1}").isEmpty())
    }
    @Test fun failureDetectionWorks() {
        assertTrue(Prompts.looksLikeFailure("",1))
        assertTrue(Prompts.looksLikeFailure("I'm unable to help with that.",3))
        assertFalse(Prompts.looksLikeFailure("{\"translations\":[{\"regionId\":\"r-000-000\",\"translatedText\":\"ওকে\"}]}",1))
    }
}

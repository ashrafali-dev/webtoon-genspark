package com.webtoon.translator

import com.webtoon.translator.core.OverlayMath
import org.junit.Assert.*
import org.junit.Test

class OverlayMathTest {
    @Test fun simpleRatioMapping() {
        val m=OverlayMath.map(100f,200f,50f,25f,800,1200,400f,600f)
        assertEquals(0.125f,m.leftRatio,1e-5f); assertEquals(200f/1200f,m.topRatio,1e-5f)
        assertEquals(50f/800f,m.widthRatio,1e-5f); assertEquals(25f/1200f,m.heightRatio,1e-5f)
    }
    @Test fun renderedSizeDoesNotAffectRatios() {
        val a=OverlayMath.map(100f,100f,50f,50f,1000,2000,500f,1000f)
        val b=OverlayMath.map(100f,100f,50f,50f,1000,2000,300f,600f)
        assertEquals(a.leftRatio,b.leftRatio,1e-6f); assertEquals(a.topRatio,b.topRatio,1e-6f)
    }
    @Test fun zeroSizesAreSafe() {
        val m=OverlayMath.map(1f,1f,1f,1f,0,0,0f,0f)
        assertEquals(0f,m.leftRatio); assertEquals(0f,m.widthRatio)
    }
    @Test fun outOfRangeBboxClamps() {
        val m=OverlayMath.map(-50f,-50f,5000f,5000f,800,1200,400f,600f)
        assertTrue(m.leftRatio>=0f&&m.topRatio>=0f)
        assertTrue(m.leftRatio+m.widthRatio<=1.0001f); assertTrue(m.topRatio+m.heightRatio<=1.0001f)
    }
    @Test fun fontAndPaddingScaleSanely() {
        assertTrue(OverlayMath.fontPx(0.2f,1000f)>OverlayMath.fontPx(0.02f,1000f))
        assertTrue(OverlayMath.padPx(0.2f,1000f)<=14f)
    }
}

package com.webtoon.translator.core

/**
 * Pure coordinate math for mapping OCR bounding boxes from the original decoded
 * bitmap into the element's displayed (CSS) rectangle.
 *
 * The page-side overlay uses the image element itself as the positioning
 * reference, so only the ratio natural->rendered matters here.
 */
object OverlayMath {

    data class MappedRect(
        /** Offsets relative to the element's displayed box, in fractions of it. */
        val leftRatio: Float,
        val topRatio: Float,
        val widthRatio: Float,
        val heightRatio: Float
    )

    /** Maps a bitmap-space bbox into element-box ratios. Safe against zero sizes. */
    fun map(
        bboxX: Float, bboxY: Float, bboxW: Float, bboxH: Float,
        naturalW: Int, naturalH: Int,
        renderedW: Float, renderedH: Float
    ): MappedRect {
        if (naturalW <= 0 || naturalH <= 0 || renderedW <= 0f || renderedH <= 0f) {
            return MappedRect(0f, 0f, 0f, 0f)
        }
        // If the bitmap was downscaled for OCR, the bbox is in downscaled space.
        // Our pipeline keeps bbox in the SAME space as naturalW/H that we report,
        // so plain ratios hold.
        val left = (bboxX / naturalW).coerceIn(0f, 1f)
        val top = (bboxY / naturalH).coerceIn(0f, 1f)
        val w = (bboxW / naturalW).coerceIn(0f, 1f - left)
        val h = (bboxH / naturalH).coerceIn(0f, 1f - top)
        return MappedRect(left, top, w, h)
    }

    /**
     * Padding in CSS pixels for an overlay box, so Bengali glyphs have room.
     * Scales sub-linearly with box height to avoid huge pads on big bubbles.
     */
    fun padPx(boxHeightRatio: Float, renderedHeightPx: Float, base: Float = 2f): Float {
        val h = boxHeightRatio * renderedHeightPx
        return (base + h * 0.06f).coerceAtMost(14f)
    }

    /** Estimates a readable Bengali font size for a box; never above box height. */
    fun fontPx(boxHeightRatio: Float, renderedHeightPx: Float, scale: Float = 1f): Float {
        val h = boxHeightRatio * renderedHeightPx
        if (h <= 4f) return 8f * scale
        return ((h * 0.62f).coerceAtMost(26f)).coerceAtLeast(9f) * scale
    }
}

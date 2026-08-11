package eu.kanade.tachiyomi.ui.reader.viewer

import okio.BufferedSource

/** Detects the correction needed to make a page upright. */
internal object PageOrientationDetector {

    private const val MIN_ROTATION_CONFIDENCE = 0.70f

    /**
     * Native ONNX inference is disabled until its process-level Android crash is resolved.
     * Keep the reader safe even when the preference was enabled by an earlier release.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun detectCorrection(imageSource: BufferedSource): Float = 0f

    /** Model labels describe current clockwise orientation; the viewer needs its inverse. */
    internal fun selectCorrection(scores: FloatArray): Int {
        require(scores.size == 4)
        val orientationClass = scores.indices.maxBy { scores[it] }
        if (orientationClass == 0 || scores[orientationClass] < MIN_ROTATION_CONFIDENCE) return 0
        return (360 - orientationClass * 90) % 360
    }
}

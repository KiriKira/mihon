package tachiyomi.core.common.util.system

internal object PageRotationCalculator {

    // Tuning parameter; keep in sync with docs/reader-page-auto-rotation.md.
    internal const val AUTO_ROTATE_MIN_ASPECT_RATIO = 1.25

    fun shouldRotatePage(
        imageWidth: Int,
        imageHeight: Int,
        isPortraitDisplay: Boolean,
    ): Boolean {
        if (!isPortraitDisplay || imageWidth <= 0 || imageHeight <= 0) return false
        return imageWidth.toDouble() / imageHeight >= AUTO_ROTATE_MIN_ASPECT_RATIO
    }
}

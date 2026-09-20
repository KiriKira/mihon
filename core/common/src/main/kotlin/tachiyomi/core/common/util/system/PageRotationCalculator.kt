package tachiyomi.core.common.util.system

internal object PageRotationCalculator {

    fun shouldRotatePage(
        imageWidth: Int,
        imageHeight: Int,
    ): Boolean {
        if (imageWidth <= 0 || imageHeight <= 0) return false
        return imageWidth > imageHeight
    }
}

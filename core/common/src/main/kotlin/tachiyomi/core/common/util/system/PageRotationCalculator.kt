package tachiyomi.core.common.util.system

internal object PageRotationCalculator {

    fun shouldRotateToMatchViewport(
        imageWidth: Int,
        imageHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
    ): Boolean {
        if (imageWidth <= 0 || imageHeight <= 0 || viewportWidth <= 0 || viewportHeight <= 0) return false
        if (imageWidth == imageHeight || viewportWidth == viewportHeight) return false
        return (imageWidth > imageHeight) != (viewportWidth > viewportHeight)
    }
}

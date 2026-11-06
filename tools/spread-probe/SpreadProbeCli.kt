package tachiyomi.core.common.util.system

import java.nio.file.Files
import java.nio.file.Path

/** Offline adapter: normalized binary PGM only, deliberately not a second JPEG/Android decode pipeline. */
fun main(args: Array<String>) {
    require(args.size in 3..5) {
        "Usage: SpreadProbeCli <normalized.pgm> <seamStart> <seamEnd> [maxShift=8] [stripWidth=16]"
    }
    val path = Path.of(args[0])
    // A bounded raster plus a bounded ASCII header; no unrestricted readAllBytes on caller input.
    val limit = 512 * 512 + 65536
    val bytes = Files.newInputStream(path).use { it.readNBytes(limit + 1) }
    require(bytes.size <= limit) { "PGM exceeds the bounded input size" }
    var cursor = 0
    fun whitespace(value: Int) = value == 32 || value in 9..13
    fun token(): String {
        while (cursor < bytes.size) {
            val value = bytes[cursor].toInt() and 255
            if (whitespace(value)) {
                cursor++
            } else if (value == '#'.code) {
                while (cursor < bytes.size && bytes[cursor] != '\n'.code.toByte()) cursor++
            } else {
                break
            }
        }
        val start = cursor
        while (cursor < bytes.size) {
            val value = bytes[cursor].toInt() and 255
            if (whitespace(value) || value == '#'.code) break
            cursor++
        }
        require(cursor > start && cursor - start <= 32) { "Invalid PGM header token" }
        return String(bytes, start, cursor - start, Charsets.US_ASCII)
    }
    require(token() == "P5") { "Expected binary PGM (P5)" }
    val width = token().toInt()
    val height = token().toInt()
    require(width in 1..512 && height in 1..512) { "Normalize dimensions to at most 512 first" }
    require(token() == "255") { "Expected 8-bit PGM maxval 255" }
    // Consume exactly one separator (or CRLF). Pixel values 10, 13, 32 and 35 are NOT header whitespace/comments.
    require(cursor < bytes.size && whitespace(bytes[cursor].toInt() and 255)) { "Missing raster separator" }
    val separator = bytes[cursor++]
    if (
        separator == '\r'.code.toByte() && bytes.size - cursor == width * height + 1 &&
        bytes[cursor] == '\n'.code.toByte()
    ) {
        cursor++
    }
    require(bytes.size - cursor == width * height) { "Expected exactly one tightly packed raster" }
    val pixels = IntArray(width * height) { bytes[cursor + it].toInt() and 255 }
    val config = SpreadContinuityProbe.Config(
        maxShift = args.getOrNull(3)?.toInt() ?: 8,
        stripWidth = args.getOrNull(4)?.toInt() ?: 16,
    )
    val result = SpreadContinuityProbe.analyze(pixels, width, height, args[1].toInt(), args[2].toInt(), config)
    val header = listOf(
        "sample", "status", "sample_rows", "best_shift", "texture_correlation", "luma_correlation_zero",
        "luma_correlation_best", "peak_separation", "at_search_boundary", "informative_bands", "agreeing_bands",
        "band_shifts",
    )
    val values = listOf(
        path.fileName, result.status, result.sampleRows, result.bestShift, result.bestCorrelation,
        result.luminanceCorrelationAtZeroShift, result.luminanceCorrelationAtBestShift, result.peakSeparation,
        result.atSearchBoundary, result.informativeBands, result.agreeingBands,
        result.bandBestShifts.joinToString(";") { it?.toString() ?: "" },
    )
    fun csv(value: Any?) = "\"" + (value?.toString() ?: "").replace("\"", "\"\"") + "\""
    println(header.joinToString(",", transform = ::csv))
    println(values.joinToString(",", transform = ::csv))
}

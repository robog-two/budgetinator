package net.robog.budgetinator.model

import net.robog.budgetinator.AppLog
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns a user-chosen file into images Excel can embed (PNG/JPEG/GIF, see [ImageFormat]).
 *
 * - PDFs: each page is rendered at [PDF_DPI] (up to [MAX_PDF_PAGES] pages).
 * - Formats ImageIO can't decode (HEIC from iPhones, TIFF, WebP, CMYK JPEGs…) are converted with macOS's `sips`.
 * - Large images are downscaled to [MAX_EDGE] px on the long side and re-encoded as JPEG or PNG,
 *   whichever is smaller for that picture, so the exported workbook stays small enough to email.
 *
 * Pure (no UI state) and potentially slow: call it off the UI thread.
 */
object DocImporter {
    const val MAX_INPUT_BYTES = 60L * 1024 * 1024
    const val MAX_PDF_PAGES = 20
    const val MAX_EDGE = 2000
    const val PDF_DPI = 150f
    const val JPEG_QUALITY = 0.85f
    /** Small, already-embeddable originals are kept byte-for-byte. */
    private const val KEEP_ORIGINAL_BYTES = 1L * 1024 * 1024
    private const val SIPS = "/usr/bin/sips"

    /** Extensions offered in the file chooser and accepted by drag and drop. */
    val EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "pdf", "heic", "heif", "tif", "tiff", "webp", "bmp")

    data class Page(val name: String, val bytes: ByteArray, val format: ImageFormat)

    /** [pages] may be non-empty even when [message] is set (e.g. a PDF that was cut off at the page limit). */
    data class Result(val pages: List<Page>, val message: String? = null)

    fun load(file: File): Result = try {
        when {
            !file.isFile -> Result(emptyList(), "${file.name} is not a file.")
            file.length() > MAX_INPUT_BYTES ->
                Result(emptyList(), "${file.name} is over ${MAX_INPUT_BYTES / 1024 / 1024} MB; please choose a smaller file.")
            isPdf(file) -> loadPdf(file)
            file.extension.lowercase() !in EXTENSIONS -> Result(emptyList(), unsupported(file))
            else -> loadImage(file)
        }
    } catch (e: OutOfMemoryError) {
        AppLog.error("Out of memory importing ${file.name}", e)
        Result(emptyList(), "${file.name} is too large to process. Try a smaller image or fewer pages.")
    } catch (e: Exception) {
        AppLog.error("Could not import ${file.name}", e)
        Result(emptyList(), "${file.name} could not be read (${e.message ?: e.javaClass.simpleName}).")
    }

    fun unsupported(file: File) =
        "${file.name}: only images (PNG, JPG, GIF, HEIC, TIFF, WebP) and PDFs can be attached. " +
            "Screenshot it or export it as a PDF or image first."

    private fun isPdf(file: File): Boolean {
        if (file.extension.equals("pdf", ignoreCase = true)) return true
        val head = ByteArray(5)
        val n = file.inputStream().use { it.read(head) }
        return n == 5 && String(head, Charsets.ISO_8859_1) == "%PDF-"
    }

    // ---- PDF

    private fun loadPdf(file: File): Result {
        val doc = try {
            Loader.loadPDF(file)
        } catch (e: InvalidPasswordException) {
            return Result(emptyList(), "${file.name} is password-protected. Remove the password or take screenshots instead.")
        }
        doc.use {
            val total = doc.numberOfPages
            if (total == 0) return Result(emptyList(), "${file.name} has no pages.")
            val renderer = PDFRenderer(doc)
            val count = minOf(total, MAX_PDF_PAGES)
            val pages = (0 until count).map { i ->
                val img = renderer.renderImageWithDPI(i, PDF_DPI, ImageType.RGB)
                val (bytes, fmt) = encode(img)
                Page(if (total == 1) file.name else "${file.name} (page ${i + 1})", bytes, fmt)
            }
            val msg = if (total > count)
                "${file.name} has $total pages; only the first $MAX_PDF_PAGES were added. Split the PDF to attach the rest."
            else null
            return Result(pages, msg)
        }
    }

    // ---- images

    private fun loadImage(file: File): Result {
        val bytes = file.readBytes()
        val decoded = decode(bytes) ?: decodeWithSips(file)
            ?: return Result(emptyList(), "${file.name} could not be read as an image. Try opening it in Preview and exporting it as PNG or JPEG.")
        return Result(listOf(prepare(file.name, bytes, decoded)))
    }

    private class Decoded(val image: BufferedImage, val format: ImageFormat?, val orientation: Int, val fullWidth: Int, val fullHeight: Int)

    /** Reads with ImageIO, subsampling huge images so a 100-megapixel scan can't exhaust memory. Null if unreadable. */
    private fun decode(bytes: ByteArray): Decoded? = try {
        MemoryCacheImageInputStream(bytes.inputStream()).use { iis ->
            val reader = ImageIO.getImageReaders(iis).asSequence().firstOrNull() ?: return null
            try {
                reader.input = iis
                val w = reader.getWidth(0)
                val h = reader.getHeight(0)
                val param = reader.defaultReadParam
                val step = max(1, max(w, h) / (MAX_EDGE * 2))
                if (step > 1) param.setSourceSubsampling(step, step, 0, 0)
                val img = reader.read(0, param)
                val fmt = when (reader.formatName.lowercase()) {
                    "png" -> ImageFormat.PNG
                    "jpeg", "jpg" -> ImageFormat.JPEG
                    "gif" -> ImageFormat.GIF
                    else -> null
                }
                Decoded(img, fmt, if (fmt == ImageFormat.JPEG) jpegOrientation(bytes) else 1, w, h)
            } finally {
                reader.dispose()
            }
        }
    } catch (e: Exception) {
        // e.g. "Unsupported Image Type" for CMYK JPEGs; the sips fallback handles those.
        AppLog.info("ImageIO could not decode image (${e.message}); trying sips")
        null
    }

    /** macOS's built-in converter handles HEIC/HEIF, TIFF, WebP, CMYK JPEG, etc. */
    private fun decodeWithSips(file: File): Decoded? {
        if (!File(SIPS).canExecute()) return null
        val tmp = Files.createTempFile("budgetinator-", ".png").toFile()
        try {
            val p = ProcessBuilder(SIPS, "-s", "format", "png", file.absolutePath, "--out", tmp.absolutePath)
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
            if (p.exitValue() != 0 || tmp.length() == 0L) {
                AppLog.info("sips could not convert ${file.name}: ${out.trim()}")
                return null
            }
            // sips copies the orientation tag into the PNG instead of rotating pixels (iPhone photos are often
            // stored sideways). Reading it back from the output stays correct even if sips ever bakes the rotation in.
            val orientation = sipsOrientation(tmp)
            return decode(tmp.readBytes())?.let { Decoded(it.image, null, orientation, it.fullWidth, it.fullHeight) }
        } finally {
            tmp.delete()
        }
    }

    private fun sipsOrientation(file: File): Int = try {
        val p = ProcessBuilder(SIPS, "-g", "orientation", file.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(10, TimeUnit.SECONDS)
        Regex("""orientation:\s*(\d)""").find(out)?.groupValues?.get(1)?.toInt() ?: 1
    } catch (_: Exception) { 1 }

    private fun prepare(name: String, original: ByteArray, d: Decoded): Page {
        val keep = d.format != null && d.orientation == 1 && original.size <= KEEP_ORIGINAL_BYTES &&
            max(d.fullWidth, d.fullHeight) <= MAX_EDGE
        if (keep) return Page(name, original, d.format!!)
        // Animated GIFs would lose their frames, but Excel only shows the first one anyway.
        val (bytes, fmt) = encode(applyOrientation(d.image, d.orientation))
        return Page(name, bytes, fmt)
    }

    /** Downscales to [MAX_EDGE] and returns the smaller of a PNG and a JPEG encoding (PNG wins near-ties, it's lossless). */
    fun encode(src: BufferedImage): Pair<ByteArray, ImageFormat> {
        val img = downscale(src, MAX_EDGE)
        val png = ByteArrayOutputStream().also { ImageIO.write(toType(img, if (img.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val jpeg = writeJpeg(toType(img, BufferedImage.TYPE_INT_RGB), JPEG_QUALITY)
        return if (jpeg.size < png.size * 0.75) jpeg to ImageFormat.JPEG else png to ImageFormat.PNG
    }

    /** Scales so the long edge is at most [maxEdge], halving repeatedly for a smooth result. */
    fun downscale(src: BufferedImage, maxEdge: Int): BufferedImage {
        val long = max(src.width, src.height)
        if (long <= maxEdge) return src
        val targetW = max(1, (src.width.toDouble() * maxEdge / long).roundToInt())
        val targetH = max(1, (src.height.toDouble() * maxEdge / long).roundToInt())
        val type = if (src.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        var cur = src
        var w = src.width
        var h = src.height
        do {
            w = max(targetW, w / 2)
            h = max(targetH, h / 2)
            val next = BufferedImage(w, h, type)
            next.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                drawImage(cur, 0, 0, w, h, null)
                dispose()
            }
            cur = next
        } while (w != targetW || h != targetH)
        return cur
    }

    private fun toType(src: BufferedImage, type: Int): BufferedImage {
        if (src.type == type) return src
        val out = BufferedImage(src.width, src.height, type)
        out.createGraphics().apply {
            if (type == BufferedImage.TYPE_INT_RGB) { color = Color.WHITE; fillRect(0, 0, src.width, src.height) }
            drawImage(src, 0, 0, null)
            dispose()
        }
        return out
    }

    private fun writeJpeg(img: BufferedImage, quality: Float): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val bos = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(bos).use { ios ->
                writer.output = ios
                val param = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality
                }
                writer.write(null, IIOImage(img, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        return bos.toByteArray()
    }

    // ---- EXIF orientation (phone photos are often stored sideways with a rotate flag)

    /** EXIF orientation 1..8 from a JPEG's APP1 segment, or 1 if absent/unparseable. */
    fun jpegOrientation(b: ByteArray): Int {
        fun u16(i: Int, le: Boolean) =
            if (le) (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
            else ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        fun u32(i: Int, le: Boolean) =
            if (le) u16(i, true) or (u16(i + 2, true) shl 16) else (u16(i, false) shl 16) or u16(i + 2, false)
        try {
            if (b.size < 4 || u16(0, false) != 0xFFD8) return 1
            var i = 2
            while (i + 4 <= b.size) {
                if (b[i].toInt() and 0xFF != 0xFF) return 1
                val marker = b[i + 1].toInt() and 0xFF
                val len = u16(i + 2, false)
                if (marker == 0xDA || marker == 0xD9) return 1 // start of scan: no EXIF before the image data
                if (marker == 0xE1 && i + 10 < b.size && String(b, i + 4, 6, Charsets.ISO_8859_1) == "Exif\u0000\u0000") {
                    val t = i + 10
                    val le = b[t].toInt() == 'I'.code
                    val ifd = t + u32(t + 4, le)
                    val n = u16(ifd, le)
                    for (e in 0 until n) {
                        val entry = ifd + 2 + e * 12
                        if (u16(entry, le) == 0x0112) return u16(entry + 8, le).takeIf { it in 1..8 } ?: 1
                    }
                    return 1
                }
                i += 2 + len
            }
        } catch (_: IndexOutOfBoundsException) {
        }
        return 1
    }

    fun applyOrientation(src: BufferedImage, orientation: Int): BufferedImage {
        if (orientation !in 2..8) return src
        val swap = orientation >= 5
        val w = src.width
        val h = src.height
        val out = BufferedImage(if (swap) h else w, if (swap) w else h,
            if (src.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        val t = java.awt.geom.AffineTransform()
        when (orientation) {
            2 -> { t.translate(w.toDouble(), 0.0); t.scale(-1.0, 1.0) }
            3 -> { t.translate(w.toDouble(), h.toDouble()); t.rotate(Math.PI) }
            4 -> { t.translate(0.0, h.toDouble()); t.scale(1.0, -1.0) }
            5 -> { t.rotate(Math.PI / 2); t.scale(1.0, -1.0) }
            6 -> { t.translate(h.toDouble(), 0.0); t.rotate(Math.PI / 2) }
            7 -> { t.translate(h.toDouble(), w.toDouble()); t.scale(-1.0, -1.0); t.rotate(Math.PI / 2); t.scale(1.0, -1.0) }
            8 -> { t.translate(0.0, w.toDouble()); t.rotate(3 * Math.PI / 2) }
        }
        g.drawImage(src, t, null)
        g.dispose()
        return out
    }
}

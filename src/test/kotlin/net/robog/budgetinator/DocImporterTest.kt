package net.robog.budgetinator

import net.robog.budgetinator.model.BudgetStore
import net.robog.budgetinator.model.DocImporter
import net.robog.budgetinator.model.ImageFormat
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Random
import javax.imageio.ImageIO
import kotlin.test.*

class DocImporterTest {
    private val dir: File = Files.createTempDirectory("docimporter-test").toFile()

    @AfterTest fun cleanup() { dir.deleteRecursively() }

    private fun read(bytes: ByteArray): BufferedImage = assertNotNull(ImageIO.read(bytes.inputStream()))

    private fun noise(w: Int, h: Int): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val rnd = Random(1)
        // Smooth-ish gradients plus noise: compresses like a photo (JPEG wins).
        for (y in 0 until h) for (x in 0 until w)
            img.setRGB(x, y, Color((x * 255 / w + rnd.nextInt(40)) % 256, (y * 255 / h + rnd.nextInt(40)) % 256, rnd.nextInt(80)).rgb)
        return img
    }

    private fun write(img: BufferedImage, name: String, fmt: String = name.substringAfterLast('.')): File =
        File(dir, name).also { ImageIO.write(img, fmt, it) }

    private fun pdf(name: String, pages: Int): File {
        val f = File(dir, name)
        PDDocument().use { doc ->
            repeat(pages) { i ->
                val page = PDPage(PDRectangle.LETTER)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.setNonStrokingColor(Color(20, 80 + i, 200))
                    cs.addRect(72f, 72f, 200f, 300f)
                    cs.fill()
                }
            }
            doc.save(f)
        }
        return f
    }

    @Test fun `large photo is downscaled to the max edge and stored as jpeg`() {
        val f = write(noise(4000, 3000), "photo.png")
        val r = DocImporter.load(f)
        assertNull(r.message)
        val p = r.pages.single()
        assertEquals("photo.png", p.name)
        assertEquals(ImageFormat.JPEG, p.format)
        val img = read(p.bytes)
        assertEquals(2000, img.width)
        assertEquals(1500, img.height)
        assertTrue(p.bytes.size < f.length() / 4, "re-encoded ${p.bytes.size} vs original ${f.length()}")
    }

    @Test fun `large flat screenshot stays png`() {
        val img = BufferedImage(3000, 1200, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply {
            color = Color.WHITE; fillRect(0, 0, 3000, 1200)
            color = Color.BLACK; repeat(40) { fillRect(50, 20 + it * 28, 1500 + it * 20, 10) }
            dispose()
        }
        val p = DocImporter.load(write(img, "screen.png")).pages.single()
        assertEquals(ImageFormat.PNG, p.format)
        assertEquals(2000, read(p.bytes).width)
    }

    @Test fun `small embeddable image is kept byte for byte`() {
        val f = write(noise(300, 200), "small.png")
        val p = DocImporter.load(f).pages.single()
        assertContentEquals(f.readBytes(), p.bytes)
        assertEquals(ImageFormat.PNG, p.format)
    }

    @Test fun `format comes from content not extension`() {
        val f = File(dir, "really-a-jpeg.png")
        ImageIO.write(noise(200, 100), "jpeg", f)
        assertEquals(ImageFormat.JPEG, DocImporter.load(f).pages.single().format)
    }

    @Test fun `exif orientation is applied to jpegs`() {
        val bos = ByteArrayOutputStream().also { ImageIO.write(noise(400, 200), "jpeg", it) }
        val f = File(dir, "rotated.jpg").apply { writeBytes(withExifOrientation(bos.toByteArray(), 6)) }
        assertEquals(6, DocImporter.jpegOrientation(f.readBytes()))
        val img = read(DocImporter.load(f).pages.single().bytes)
        assertEquals(200, img.width)
        assertEquals(400, img.height)
    }

    @Test fun `applyOrientation maps the top-left corner correctly`() {
        val src = BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, Color.RED.rgb) }
        // orientation -> where the source's top-left pixel must end up (out is 2x4 for 5..8)
        val expected = mapOf(2 to (3 to 0), 3 to (3 to 1), 4 to (0 to 1), 5 to (0 to 0), 6 to (1 to 0), 7 to (1 to 3), 8 to (0 to 3))
        for ((o, xy) in expected) {
            val out = DocImporter.applyOrientation(src, o)
            assertEquals(Color.RED.rgb, out.getRGB(xy.first, xy.second), "orientation $o")
        }
    }

    @Test fun `pdf pages become one image each`() {
        val r = DocImporter.load(pdf("quote.pdf", 3))
        assertNull(r.message)
        assertEquals(listOf("quote.pdf (page 1)", "quote.pdf (page 2)", "quote.pdf (page 3)"), r.pages.map { it.name })
        val img = read(r.pages[0].bytes)
        // Letter at 150 DPI is 1275 x 1650.
        assertTrue(img.width in 1274..1275 && img.height in 1649..1650, "${img.width}x${img.height}")
        // The blue rectangle was rendered (92pt, 92pt from the bottom-left corner).
        assertEquals(Color(20, 80, 200).rgb, img.getRGB(92 * 150 / 72, img.height - 92 * 150 / 72), "rendered pixel")
    }

    @Test fun `single page pdf keeps its file name`() {
        assertEquals("invoice.pdf", DocImporter.load(pdf("invoice.pdf", 1)).pages.single().name)
    }

    @Test fun `long pdf is capped with a message`() {
        val r = DocImporter.load(pdf("long.pdf", DocImporter.MAX_PDF_PAGES + 5))
        assertEquals(DocImporter.MAX_PDF_PAGES, r.pages.size)
        assertContains(assertNotNull(r.message), "only the first ${DocImporter.MAX_PDF_PAGES}")
    }

    @Test fun `unsupported file type gets a helpful message`() {
        val f = File(dir, "budget.docx").apply { writeText("not an image") }
        val r = DocImporter.load(f)
        assertTrue(r.pages.isEmpty())
        assertContains(assertNotNull(r.message), "only images")
        assertContains(r.message!!, "PDF")
    }

    @Test fun `corrupt image is reported`() {
        val f = File(dir, "broken.png").apply { writeBytes(ByteArray(100) { it.toByte() }) }
        val r = DocImporter.load(f)
        assertTrue(r.pages.isEmpty())
        assertContains(assertNotNull(r.message), "could not be read")
    }

    @Test fun `heic is converted with sips`() {
        if (!File("/usr/bin/sips").canExecute()) return
        val jpg = write(noise(640, 480), "src.jpg", "jpeg")
        val heic = File(dir, "IMG_0001.HEIC")
        val p = ProcessBuilder("/usr/bin/sips", "-s", "format", "heic", jpg.path, "--out", heic.path).redirectErrorStream(true).start()
        p.inputStream.readAllBytes(); p.waitFor()
        if (!heic.isFile || heic.length() == 0L) return // this macOS can't write HEIC; nothing to test
        val r = DocImporter.load(heic)
        assertNull(r.message)
        val page = r.pages.single()
        assertTrue(page.format == ImageFormat.JPEG || page.format == ImageFormat.PNG)
        assertEquals(640, read(page.bytes).width)
    }

    @Test fun `store adds every pdf page as a document`() {
        val store = BudgetStore()
        assertNull(store.addDocument(pdf("two.pdf", 2)))
        assertEquals(2, store.request.docs.size)
        assertNotNull(store.addDocument(File(dir, "missing.png")))
    }

    /** Inserts a minimal big-endian EXIF APP1 segment with just an Orientation tag right after SOI. */
    private fun withExifOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        val tiff = byteArrayOf(
            'M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8, // header, IFD0 at offset 8
            0, 1,                                                       // 1 entry
            0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0, // Orientation, SHORT, count 1, value
            0, 0, 0, 0,                                                 // no next IFD
        )
        val payload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff
        val len = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + payload
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }
}

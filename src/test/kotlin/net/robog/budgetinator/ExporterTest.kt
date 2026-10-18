package net.robog.budgetinator

import net.robog.budgetinator.export.XlsxExporter
import net.robog.budgetinator.model.*
import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

class ExporterTest {
    private fun export(r: BudgetRequest): Map<String, ByteArray> {
        val bos = ByteArrayOutputStream()
        XlsxExporter.export(r, bos)
        val m = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bos.toByteArray().inputStream()).use { z ->
            while (true) { val e = z.nextEntry ?: break; m[e.name] = z.readBytes() }
        }
        return m
    }

    private fun sample(n: Int, docs: List<SupportingDoc> = emptyList()) = BudgetRequest(
        header = Header(
            type = BudgetType.PROGRAMMING, organization = "Robotics & <Friends>", eventName = "Movie \"Night\"",
            eventDate = "Fall 2030", location = "Textor 102", expectedAttendance = "40", heldBefore = true,
            pastAttendance = "35", pastCost = "250.5", usesCollegeServices = true,
            collegeServices = setOf(CollegeService.PRINT_SHOP, CollegeService.CCES), hasAdditionalFunding = true,
        ),
        items = (1..n).map { LineItem(it.toLong(), ItemType.FOOD, "Item $it", "Wegmans", "why $it", "2", "12.34") },
        funding = listOf(FundingSource(900, "Dept", "Co-sponsor", FundingStatus.PENDING, "300")),
        docs = docs,
    )

    private fun String.part(p: Map<String, ByteArray>, name: String) = String(p.getValue(name))

    @Test fun `all xml parts are well formed`() {
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val parts = export(sample(30, listOf(SupportingDoc(1000, "quote.png", png, ImageFormat.PNG, setOf(1L), "menu"))))
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        for ((name, bytes) in parts) if (name.endsWith(".xml") || name.endsWith(".rels") || name.endsWith(".vml").not() && false)
            f.newDocumentBuilder().parse(bytes.inputStream())
        assertContains(String(parts.getValue("xl/drawings/drawing3.xml")), "Reference for Item 1 — menu")
        assertTrue("xl/media/supporting1.png" in parts)
        assertTrue("xl/drawings/drawing3.xml" in parts)
        assertFalse("xl/calcChain.xml" in parts)
        assertTrue("xl/embeddings/oleObject1.bin" in parts, "instructions PDF object must survive")
    }

    @Test fun `many large documents are scaled to fit one screen`() {
        fun png(w: Int, h: Int) = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val docs = (1..4).map { SupportingDoc(it.toLong(), "d$it.png", png(1600, 1000), ImageFormat.PNG, setOf(1L)) }
        val x = String(export(sample(2, docs)).getValue("xl/drawings/drawing3.xml"))
        val ext = Regex("""<xdr:pic>.*?""").let { Regex("""<xdr:ext cx="(\d+)" cy="(\d+)"/><xdr:pic>""").findAll(x).toList() }
        assertEquals(4, ext.size)
        ext.forEach { assertTrue(it.groupValues[1].toLong() / 9525 < 700, "image should be shrunk") }
        val bottoms = Regex("""<xdr:pos x="\d+" y="(\d+)"/><xdr:ext cx="\d+" cy="(\d+)"/><xdr:pic>""").findAll(x)
            .map { (it.groupValues[1].toLong() + it.groupValues[2].toLong()) / 9525 }.toList()
        assertEquals(4, bottoms.size)
        assertTrue(bottoms.all { it <= 100 + 640 }, "all images end inside the one-screen region: $bottoms")
    }

    @Test fun `header and items land in the template cells`() {
        val p = export(sample(3))
        val s = String(p.getValue("xl/worksheets/sheet2.xml"))
        assertContains(s, """<c r="D2" s="4" t="inlineStr"><is><t xml:space="preserve">Programming</t></is></c>""")
        assertContains(s, "Robotics &amp; &lt;Friends&gt;")
        assertContains(s, """<c r="F5" s="28"><v>40</v></c>""")
        assertContains(s, """<c r="F7" s="31"><v>250.5</v></c>""")
        assertContains(s, "IC Print Shop, CCES")
        assertContains(s, """<c r="B13" t="inlineStr"><is><t xml:space="preserve">Food</t></is></c>""")
        assertContains(s, """<c r="G13" s="12"><v>12.34</v></c>""")
        assertContains(s, """<f>F13*G13</f><v>24.68</v>""")
        assertContains(s, """<f>SUBTOTAL(109,Table1[Total Cost:])</f><v>74.04</v>""")
        assertContains(s, """<f>ROUNDUP(Table1[[#Totals],[Total Cost:]],0)</f><v>75</v>""")
        // blank template rows are kept, and the totals row stays at 35
        assertContains(s, """<row r="35" """)
        assertContains(String(p.getValue("xl/tables/table1.xml")), """ref="B12:H35"""")
    }

    @Test fun `tables grow past the template capacity`() {
        val p = export(sample(30))
        val t = String(p.getValue("xl/tables/table1.xml"))
        assertContains(t, """ref="B12:H43"""")
        assertContains(t, """<autoFilter ref="B12:H42"""")
        assertFalse("sortState" in t)
        val s = String(p.getValue("xl/worksheets/sheet2.xml"))
        assertContains(s, """<row r="42" """); assertContains(s, """<row r="43" """); assertFalse("""<row r="44" """ in s)
        assertContains(s, "B13:B42"); assertFalse("B13:B34" in s)
        assertContains(s, """<dimension ref="A1:H43"/>""")
    }

    @Test fun `funding sheet is filled and grows`() {
        val r = sample(1).copy(funding = (1..8).map { FundingSource(it.toLong(), "S$it", "r", FundingStatus.APPROVED, "10") })
        val p = export(r)
        val s = String(p.getValue("xl/worksheets/sheet4.xml"))
        assertContains(s, """<c r="E4" t="inlineStr"><is><t xml:space="preserve">S1</t></is></c>""")
        assertContains(s, """<c r="G4" t="inlineStr"><is><t xml:space="preserve">Approved</t></is></c>""")
        assertContains(s, """<f>SUBTOTAL(109,Table5[Amount:])</f><v>80</v>""")
        assertContains(String(p.getValue("xl/tables/table2.xml")), """ref="E3:H12"""")
    }

    @Test fun `item rows are sized to their wrapped text`() {
        val long = "Reasoning that is long enough to need several lines in the 55 wide source column. ".repeat(4).trim()
        val r = sample(3).copy(items = listOf(
            LineItem(1, ItemType.FOOD, "Pizza", "Wegmans", "short", "1", "5"),
            LineItem(2, ItemType.FOOD, "Pizza", "Wegmans", long, "1", "5"),
            LineItem(3, ItemType.FOOD, "Supercalifragilisticexpialidocious-name", "Wegmans", "x\ny\nz", "1", "5"),
        ))
        val s = String(export(r).getValue("xl/worksheets/sheet2.xml"))
        fun row(n: Int) = Regex("""<row r="$n"[^>]*>""").find(s)!!.value
        assertFalse("ht=" in row(13), "one-line rows keep the default height")
        val ht = Regex("""ht="([\d.]+)"""").find(row(14))!!.groupValues[1].toDouble()
        assertTrue(ht >= 45 && ht % 15 == 0.0, "wrapped reasoning needs several 15pt lines: $ht")
        assertContains(row(15), """ht="45"""") // three explicit lines; the 24.14-wide name column also wraps
    }

    /** Set BUDGET_SAMPLE_OUT to keep a sample workbook around for inspecting in Excel. */
    @Test fun `optional sample dump`() {
        val out = System.getenv("BUDGET_SAMPLE_OUT") ?: return
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        XlsxExporter.export(sample(25, listOf(SupportingDoc(1000, "quote.png", png, ImageFormat.PNG, setOf(1L, 2L), "menu"))), java.nio.file.Path.of(out))
    }

    @Test fun `output file is written to disk`() {
        val f = Files.createTempFile("budget", ".xlsx")
        XlsxExporter.export(sample(2), f)
        assertTrue(Files.size(f) > 100_000)
        Files.delete(f)
    }
}

package net.robog.budgetinator.export

import net.robog.budgetinator.model.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.min

/**
 * Fills the original SGC workbook. The template is never re-created: its zip parts are copied
 * through and only the cell data of the Budget, Additional Funds and Supporting Documentation
 * sheets is patched, so styles, tables, dropdowns, conditional formatting, the embedded
 * instructions PDF and the price list survive untouched.
 */
object XlsxExporter {
    private const val TEMPLATE = "/SGC_New_request_template.xlsx"

    // Template geometry (Budget sheet): items start on row 13 inside Table1, whose last
    // template data row is 34 and totals row 35. Additional Funds: rows 4..9, totals row 10.
    private const val ITEM_FIRST = 13
    private const val ITEM_LAST_TEMPLATE = 34
    private const val FUND_FIRST = 4
    private const val FUND_LAST_TEMPLATE = 9

    private const val BUDGET = "xl/worksheets/sheet2.xml"
    private const val DOCS = "xl/worksheets/sheet3.xml"
    private const val FUNDS = "xl/worksheets/sheet4.xml"

    fun export(request: BudgetRequest, out: Path) {
        Files.newOutputStream(out).use { export(request, it) }
    }

    fun export(request: BudgetRequest, out: OutputStream) {
        val parts = readTemplate()
        fun text(name: String) = String(parts.getValue(name), Charsets.UTF_8)
        fun put(name: String, s: String) { parts[name] = s.toByteArray(Charsets.UTF_8) }

        val items = request.filledItems
        val itemRows = maxOf(items.size, Rules.TEMPLATE_ITEM_ROWS)
        val itemLast = ITEM_FIRST + itemRows - 1
        val itemTotalsRow = itemLast + 1

        put(BUDGET, patchBudget(text(BUDGET), request, items, itemLast, itemTotalsRow))
        put("xl/tables/table1.xml", patchTable1(text("xl/tables/table1.xml"), itemLast, itemTotalsRow))

        val funds = request.filledFunding
        val fundRows = maxOf(funds.size, Rules.TEMPLATE_FUNDING_ROWS)
        val fundLast = FUND_FIRST + fundRows - 1
        put(FUNDS, patchFunds(text(FUNDS), request, funds, fundLast))
        put("xl/tables/table2.xml", patchTable5(text("xl/tables/table2.xml"), fundLast))

        if (request.docs.isNotEmpty()) addDocuments(parts, request)

        dropCalcChain(parts)
        put("xl/workbook.xml", text("xl/workbook.xml")
            .replace("<calcPr calcId=\"191029\"/>", "<calcPr calcId=\"191029\" fullCalcOnLoad=\"1\"/>")
            .replace("firstSheet=\"2\" activeTab=\"4\"", "firstSheet=\"0\" activeTab=\"1\""))
        for (n in 1..6) {
            val k = "xl/worksheets/sheet$n.xml"
            put(k, text(k).replace(" tabSelected=\"1\"", ""))
        }
        put(BUDGET, text(BUDGET).replaceFirst("<sheetView ", "<sheetView tabSelected=\"1\" "))

        ZipOutputStream(out).use { z ->
            for ((name, bytes) in parts) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
    }

    // ---------------------------------------------------------------- template IO

    private fun readTemplate(): LinkedHashMap<String, ByteArray> {
        val stream = XlsxExporter::class.java.getResourceAsStream(TEMPLATE)
            ?: error("Bundled template $TEMPLATE is missing")
        val m = LinkedHashMap<String, ByteArray>()
        ZipInputStream(stream).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                m[e.name] = z.readBytes()
            }
        }
        return m
    }

    private fun dropCalcChain(parts: MutableMap<String, ByteArray>) {
        parts.remove("xl/calcChain.xml")
        fun edit(name: String, re: Regex) {
            parts[name] = String(parts.getValue(name), Charsets.UTF_8).replace(re, "").toByteArray(Charsets.UTF_8)
        }
        edit("[Content_Types].xml", Regex("""<Override[^>]*calcChain[^>]*/>"""))
        edit("xl/_rels/workbook.xml.rels", Regex("""<Relationship [^>]*calcChain[^>]*/>"""))
    }

    // ---------------------------------------------------------------- cell helpers

    private val INVALID_XML = Regex("[^\\u0009\\u000A\\u000D\\u0020-\\uD7FF\\uE000-\\uFFFD]")

    private fun esc(s: String) = s.replace(INVALID_XML, "")
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun sAttr(style: String?) = if (style.isNullOrEmpty()) "" else """ s="$style""""

    private fun strCell(ref: String, value: String, style: String? = null) =
        """<c r="$ref"${sAttr(style)} t="inlineStr"><is><t xml:space="preserve">${esc(value)}</t></is></c>"""

    private fun numCell(ref: String, value: BigDecimal, style: String? = null) =
        """<c r="$ref"${sAttr(style)}><v>${value.stripTrailingZeros().toPlainString()}</v></c>"""

    private fun sharedCell(ref: String, idx: Int, style: String? = null) =
        """<c r="$ref"${sAttr(style)} t="s"><v>$idx</v></c>"""

    private fun emptyCell(ref: String, style: String?) = """<c r="$ref"${sAttr(style)}/>"""

    private fun formulaCell(ref: String, f: String, cached: BigDecimal, style: String?) =
        """<c r="$ref"${sAttr(style)}><f>${esc(f)}</f><v>${cached.stripTrailingZeros().toPlainString().ifEmpty { "0" }}</v></c>"""

    private fun cellRegex(ref: String) = Regex("""<c r="$ref"((?: [^>]*?)?)(?:/>|>.*?</c>)""", RegexOption.DOT_MATCHES_ALL)

    private fun styleOf(xml: String, ref: String): String? =
        cellRegex(ref).find(xml)?.groupValues?.get(1)?.let { Regex("""\ss="(\d+)"""").find(it)?.groupValues?.get(1) }

    private fun setCell(xml: String, ref: String, build: (style: String?) -> String): String {
        val re = cellRegex(ref)
        val m = re.find(xml) ?: error("Template cell $ref not found")
        return xml.replaceRange(m.range, build(styleOf(xml, ref)))
    }

    private fun setText(xml: String, ref: String, v: String) = setCell(xml, ref) { strCell(ref, v, it) }
    private fun setNum(xml: String, ref: String, v: BigDecimal) = setCell(xml, ref) { numCell(ref, v, it) }

    private fun templateRowStyles(xml: String, row: Int): Map<String, String?> {
        val m = Regex("""<row r="$row"[^>]*>(.*?)</row>""", RegexOption.DOT_MATCHES_ALL).find(xml)
            ?: error("Template row $row not found")
        return Regex("""<c r="([A-Z]+)$row"([^>]*?)(?:/>|>)""").findAll(m.groupValues[1]).associate { c ->
            c.groupValues[1] to Regex("""\ss="(\d+)"""").find(c.groupValues[2])?.groupValues?.get(1)
        }
    }

    private fun replaceRows(xml: String, firstRow: Int, rowsXml: String): String {
        val start = xml.indexOf("""<row r="$firstRow"""")
        val end = xml.indexOf("</sheetData>")
        check(start in 0 until end) { "Unexpected sheet layout" }
        return xml.substring(0, start) + rowsXml + xml.substring(end)
    }

    // ---------------------------------------------------------------- Budget sheet

    private fun patchBudget(
        src: String, r: BudgetRequest, items: List<LineItem>, lastDataRow: Int, totalsRow: Int,
    ): String {
        val h = r.header
        val type = h.type
        var x = src

        type?.let { x = setText(x, "D2", it.label) }
        if (type?.asksSemester == true) h.semester?.let { x = setText(x, "F2", it.label) }
        x = setText(x, "D3", h.organization.trim())
        x = setText(x, "D4", h.eventName.trim())
        x = setText(x, "D5", h.eventDate.trim())
        x = setText(x, "D6", h.location.trim())
        if (type?.asksTripSize == true) parseWholeNumber(h.tripSize)?.let { x = setNum(x, "F4", it.toBigDecimal()) }
        if (type?.asksExpectedAttendance == true)
            parseWholeNumber(h.expectedAttendance)?.let { x = setNum(x, "F5", it.toBigDecimal()) }
        h.heldBefore?.let { x = setText(x, "D7", yn(it)) }
        if (h.heldBefore == true) {
            parseWholeNumber(h.pastAttendance)?.let { x = setNum(x, "F6", it.toBigDecimal()) }
            parseMoney(h.pastCost)?.let { x = setNum(x, "F7", it) }
        }
        h.usesCollegeServices?.let { x = setText(x, "D8", yn(it)) }
        if (h.usesCollegeServices == true && h.collegeServices.isNotEmpty())
            x = setText(x, "D11", CollegeService.entries.filter { it in h.collegeServices }.joinToString(", ") { it.label })
        h.hasAdditionalFunding?.let { x = setCell(x, "D9") { s -> strCell("D9", yn(it), s) } }

        val warn = type?.category == Category.PROGRAMMING &&
            r.grandTotal > BigDecimal(Rules.ADDITIONAL_FUNDING_THRESHOLD)
        x = setCell(x, "D10") { s ->
            val text = if (warn) "*Please see step 7 in the instructions and add additional fundraising sources since your budget is over \$1000*" else " "
            val f = """IF(OR(AND(F11>1000,D2="Programming"),AND(F11>1000,D2="Programming (next academic year Block 1)")),"*Please see step 7 in the instructions and add additional fundraising sources since your budget is over ${'$'}1000*"," ")"""
            """<c r="D10"${sAttr(s)} t="str"><f>${esc(f)}</f><v xml:space="preserve">${esc(text)}</v></c>"""
        }
        x = setCell(x, "F11") { s ->
            """<c r="F11"${sAttr(s)}><f>ROUNDUP(Table1[[#Totals],[Total Cost:]],0)</f><v>${plain(r.grandTotal)}</v></c>"""
        }

        // Item rows ------------------------------------------------------------------
        val colWidths = columnWidths(src)
        val styleRows = (ITEM_FIRST..ITEM_LAST_TEMPLATE).associateWith { templateRowStyles(src, it) }
        val rows = StringBuilder()
        for (row in ITEM_FIRST..lastDataRow) {
            val st = styleRows[min(row, ITEM_LAST_TEMPLATE)]!!
            val item = items.getOrNull(row - ITEM_FIRST)
            val ht = item?.let { itemRowHeight(it, colWidths) }
            rows.append("""<row r="$row" spans="2:8"${ht?.let { """ ht="$it"""" }.orEmpty()} x14ac:dyDescent="0.25">""")
            rows.append(
                if (item?.type != null) strCell("B$row", item.type.label, st["B"])
                else sharedCell("B$row", 18, st["B"]) // "(Select Item Type)"
            )
            fun textOrEmpty(col: String, v: String?) =
                if (v.isNullOrBlank()) emptyCell("$col$row", st[col]) else strCell("$col$row", v.trim(), st[col])
            rows.append(textOrEmpty("C", item?.name))
            rows.append(textOrEmpty("D", item?.location))
            rows.append(textOrEmpty("E", item?.reasoning))
            val q = item?.let { parseWholeNumber(it.quantity) }
            val p = item?.let { parseMoney(it.unitPrice) }
            rows.append(if (q != null) numCell("F$row", q.toBigDecimal(), st["F"]) else emptyCell("F$row", st["F"]))
            rows.append(if (p != null) numCell("G$row", p, st["G"]) else emptyCell("G$row", st["G"]))
            rows.append(formulaCell("H$row", "F$row*G$row", item?.total ?: BigDecimal.ZERO, st["H"]))
            rows.append("</row>")
        }
        val tot = templateRowStyles(src, 35)
        rows.append("""<row r="$totalsRow" spans="2:8" x14ac:dyDescent="0.25">""")
        rows.append(sharedCell("B$totalsRow", 73, tot["B"]))
        for (c in listOf("C", "D", "E", "F", "G")) rows.append(emptyCell("$c$totalsRow", tot[c]))
        rows.append(
            """<c r="H$totalsRow"${sAttr(tot["H"])}><f>SUBTOTAL(109,Table1[Total Cost:])</f><v>${plain(r.total)}</v></c>"""
        )
        rows.append("</row>")

        x = replaceRows(x, ITEM_FIRST, rows.toString())
        x = x.replace(Regex("""<dimension ref="[^"]*"/>"""), """<dimension ref="A1:H$totalsRow"/>""")
        if (lastDataRow > ITEM_LAST_TEMPLATE)
            x = x.replace(Regex("""\b([B-H])13:([B-H])34\b"""), "$113:$2$lastDataRow")
        return x
    }

    private fun columnWidths(sheet: String): Map<Char, Double> =
        Regex("""<col min="(\d+)" max="(\d+)" width="([\d.]+)"""").findAll(sheet).flatMap { m ->
            (m.groupValues[1].toInt()..m.groupValues[2].toInt()).map { ('A' + it - 1) to m.groupValues[3].toDouble() }
        }.toMap()

    /**
     * Height (pt) that fits the wrapped text of the item's C/D/E cells, or null when everything fits on
     * one line and the default row height is right. Written without customHeight so Excel still
     * treats the row as auto-fit and re-fits it when the cell is edited.
     */
    private fun itemRowHeight(item: LineItem, widths: Map<Char, Double>): String? {
        val cells = listOf('C' to item.name, 'D' to item.location, 'E' to item.reasoning)
            .filter { it.second.isNotBlank() }
            .map { (col, text) -> text.trim() to widths.getValue(col) }
        return RowFit.heightPt(cells)?.let { plain(BigDecimal.valueOf(it)) }
    }

    private fun patchTable1(src: String, lastDataRow: Int, totalsRow: Int): String =
        src.replace("""ref="B12:H35"""", """ref="B12:H$totalsRow"""")
            .replace("""<autoFilter ref="B12:H34"""", """<autoFilter ref="B12:H$lastDataRow"""")
            .replace(Regex("""<sortState .*?</sortState>""", RegexOption.DOT_MATCHES_ALL), "")

    private fun plain(v: BigDecimal): String = if (v.signum() == 0) "0" else v.stripTrailingZeros().toPlainString()

    private fun yn(b: Boolean) = if (b) "Yes" else "No"

    // ---------------------------------------------------------------- Additional Funds sheet

    private fun patchFunds(src: String, r: BudgetRequest, funds: List<FundingSource>, lastDataRow: Int): String {
        val totalsRow = lastDataRow + 1
        val tot = templateRowStyles(src, 10)
        val rows = StringBuilder()
        for (row in FUND_FIRST..lastDataRow) {
            val f = funds.getOrNull(row - FUND_FIRST)
            rows.append("""<row r="$row" spans="1:9" x14ac:dyDescent="0.25">""")
            rows.append(if (f == null || f.source.isBlank()) "" else strCell("E$row", f.source.trim()))
            rows.append(
                if (f == null || f.rationale.isBlank()) emptyCell("F$row", "5") else strCell("F$row", f.rationale.trim(), "5")
            )
            rows.append(if (f?.status == null) "" else strCell("G$row", f.status.label))
            rows.append(parseMoney(f?.amount.orEmpty())?.let { numCell("H$row", it, "12") } ?: "")
            rows.append("</row>")
        }
        rows.append("""<row r="$totalsRow" spans="1:9" x14ac:dyDescent="0.25">""")
        rows.append(sharedCell("E$totalsRow", 73, tot["E"]))
        rows.append(emptyCell("F$totalsRow", tot["F"]))
        rows.append(emptyCell("G$totalsRow", tot["G"]))
        rows.append(
            """<c r="H$totalsRow"${sAttr(tot["H"])}><f>SUBTOTAL(109,Table5[Amount:])</f><v>${plain(r.fundingTotal)}</v></c>"""
        )
        rows.append("</row>")

        var x = replaceRows(src, FUND_FIRST, rows.toString())
        x = x.replace(Regex("""<dimension ref="[^"]*"/>"""), """<dimension ref="A1:I$totalsRow"/>""")
        if (lastDataRow > FUND_LAST_TEMPLATE) x = x.replace(Regex("""\b([EGH])4:([EGH])9\b"""), "$14:$2$lastDataRow")
        return x
    }

    private fun patchTable5(src: String, lastDataRow: Int): String =
        src.replace("""ref="E3:H10"""", """ref="E3:H${lastDataRow + 1}"""")
            .replace("""<autoFilter ref="E3:H9"""", """<autoFilter ref="E3:H$lastDataRow"""")

    // ---------------------------------------------------------------- Supporting Documentation

    private const val EMU_PER_PX = 9525L

    // Everything is laid out in a single grid that fits one Excel window at 100% zoom.
    private const val TOP_PX = 100        // below the sheet's instruction rows
    private const val REGION_W_PX = 1500
    private const val REGION_H_PX = 640
    private const val CAPTION_PX = 34
    private const val PAD_PX = 10
    private const val MIN_SCALE = 0.2     // below this, stay legible and let the grid run past one screen

    private class Placed(val doc: SupportingDoc, val w: Int, val h: Int)

    private fun addDocuments(parts: MutableMap<String, ByteArray>, r: BudgetRequest) {
        val itemNames = r.filledItems.associate { it.id to it.name.trim().ifEmpty { "(unnamed item)" } }
        val placed = r.docs.map { d ->
            val img = ImageIO.read(ByteArrayInputStream(d.bytes)) ?: error("${d.fileName} is not a readable image")
            Placed(d, img.width, img.height)
        }

        // Pick the column count that lets the smallest image stay largest.
        fun fitScale(cols: Int): Double {
            val rows = ceil(placed.size / cols.toDouble()).toInt()
            val boxW = REGION_W_PX / cols - PAD_PX
            val boxH = REGION_H_PX / rows - CAPTION_PX - PAD_PX
            return placed.minOf { min(1.0, min(boxW.toDouble() / it.w, boxH.toDouble() / it.h)) }
        }
        val cols = (1..placed.size).maxByOrNull { c -> fitScale(c) * 1000 - c } ?: 1
        val scale = maxOf(fitScale(cols), MIN_SCALE)
        val cellW = REGION_W_PX / cols

        // Displayed size of each image (EMU), capped to its column width.
        val sizes = placed.map { p ->
            val cx = (p.w * scale * EMU_PER_PX).toLong().coerceAtMost((cellW - PAD_PX) * EMU_PER_PX)
            cx to (cx.toDouble() * p.h / p.w).toLong()
        }
        // Each row starts just below the tallest image in the row above.
        val rowCount = ceil(placed.size / cols.toDouble()).toInt()
        val rowTops = IntArray(rowCount)
        for (row in 1 until rowCount) {
            val tallest = (0 until cols).mapNotNull { sizes.getOrNull((row - 1) * cols + it)?.second }.max()
            rowTops[row] = rowTops[row - 1] + CAPTION_PX + ceil(tallest / EMU_PER_PX.toDouble()).toInt() + PAD_PX
        }

        val anchors = StringBuilder()
        val rels = StringBuilder()
        var shapeId = 2
        placed.forEachIndexed { i, p ->
            val n = i + 1
            val d = p.doc
            val x = (i % cols) * cellW + PAD_PX / 2
            val y = TOP_PX + rowTops[i / cols]
            val (cx, cy) = sizes[i]
            parts["xl/media/supporting$n.${d.format.ext}"] = d.bytes

            val backs = d.itemIds.mapNotNull { itemNames[it] }.joinToString(", ")
            val caption = buildString {
                append(if (backs.isNotEmpty()) "Reference for $backs" else "Reference document $n")
                if (d.note.isNotBlank()) append(" — ${d.note.trim()}")
            }
            val capId = shapeId++
            val picId = shapeId++
            val capCx = (cellW - PAD_PX) * EMU_PER_PX
            anchors.append(
                """<xdr:absoluteAnchor><xdr:pos x="${x * EMU_PER_PX}" y="${y * EMU_PER_PX}"/><xdr:ext cx="$capCx" cy="${(CAPTION_PX - 4) * EMU_PER_PX}"/>""" +
                    """<xdr:sp macro="" textlink=""><xdr:nvSpPr><xdr:cNvPr id="$capId" name="Caption $n"/><xdr:cNvSpPr txBox="1"/></xdr:nvSpPr>""" +
                    """<xdr:spPr><a:xfrm><a:off x="${x * EMU_PER_PX}" y="${y * EMU_PER_PX}"/><a:ext cx="$capCx" cy="${(CAPTION_PX - 4) * EMU_PER_PX}"/></a:xfrm>""" +
                    """<a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></xdr:spPr>""" +
                    """<xdr:txBody><a:bodyPr wrap="square" lIns="0" tIns="0" rIns="0" bIns="0" rtlCol="0" anchor="b"/><a:lstStyle/>""" +
                    """<a:p><a:r><a:rPr lang="en-US" sz="1000" b="1"/><a:t>${esc(caption)}</a:t></a:r></a:p></xdr:txBody></xdr:sp><xdr:clientData/></xdr:absoluteAnchor>"""
            )
            val py = (y + CAPTION_PX) * EMU_PER_PX
            anchors.append(
                """<xdr:absoluteAnchor><xdr:pos x="${x * EMU_PER_PX}" y="$py"/><xdr:ext cx="$cx" cy="$cy"/><xdr:pic><xdr:nvPicPr>""" +
                    """<xdr:cNvPr id="$picId" name="Supporting document $n" descr="${esc(caption)}"/>""" +
                    """<xdr:cNvPicPr><a:picLocks noChangeAspect="1"/></xdr:cNvPicPr></xdr:nvPicPr><xdr:blipFill><a:blip r:embed="rId$n"/>""" +
                    """<a:stretch><a:fillRect/></a:stretch></xdr:blipFill><xdr:spPr><a:xfrm><a:off x="${x * EMU_PER_PX}" y="$py"/><a:ext cx="$cx" cy="$cy"/></a:xfrm>""" +
                    """<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:absoluteAnchor>"""
            )
            rels.append(
                """<Relationship Id="rId$n" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/supporting$n.${d.format.ext}"/>"""
            )
        }

        parts["xl/drawings/drawing3.xml"] = (
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                """<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">$anchors</xdr:wsDr>"""
            ).toByteArray()
        parts["xl/drawings/_rels/drawing3.xml.rels"] = (
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$rels</Relationships>"""
            ).toByteArray()

        fun edit(name: String, f: (String) -> String) {
            parts[name] = f(String(parts.getValue(name), Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        }
        edit("xl/worksheets/_rels/sheet3.xml.rels") {
            it.replace("</Relationships>",
                """<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/drawing3.xml"/></Relationships>""")
        }
        edit(DOCS) { s ->
            s.replace(Regex("""(<pageSetup [^>]*/>)"""), "$1<drawing r:id=\"rId2\"/>")
        }
        edit("[Content_Types].xml") { s ->
            var t = s
            for (f in r.docs.map { it.format }.toSet()) {
                if (!t.contains("""Extension="${f.ext}""""))
                    t = t.replaceFirst("<Default ", """<Default Extension="${f.ext}" ContentType="${f.mime}"/><Default """)
            }
            t.replace("</Types>",
                """<Override PartName="/xl/drawings/drawing3.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/></Types>""")
        }
    }
}

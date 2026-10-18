package net.robog.budgetinator.export

import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import kotlin.math.floor

/**
 * Works out the row height Excel would pick for wrapped Calibri 11 text.
 *
 * Facts this relies on (ECMA-376 §18.3.1.13 `col`/`row`, plus Excel's documented rendering):
 *  - The template's default font is Calibri 11 (styles.xml fonts[0]); its maximum digit width at
 *    96 DPI is 7 px, so a column of width `w` is `floor(w * 7 + 0.5)` px wide ("width" already includes
 *    the 5 px of cell padding/gridline that [CELL_PADDING_PX] removes again).
 *  - Calibri 11 pt at 96 DPI is 14.667 px; one text line takes 15 pt of row height (the row default),
 *    so n lines need 15 n pt, capped at Excel's 409.5 pt maximum.
 *  - Excel wraps at spaces (and after hyphens); a word wider than the cell is broken mid-word.
 *  - Carlito is metric-compatible with Calibri, so its advances are Calibri's advances.
 */
internal object RowFit {
    private const val FONT_PT = 11.0
    private const val DPI = 96.0
    private const val MAX_DIGIT_PX = 7
    private const val CELL_PADDING_PX = 5
    const val LINE_PT = 15.0
    const val MAX_ROW_PT = 409.5

    private val font: Font by lazy {
        val s = RowFit::class.java.getResourceAsStream("/fonts/Carlito-Regular.ttf") ?: error("Carlito font missing")
        s.use { Font.createFont(Font.TRUETYPE_FONT, it) }.deriveFont((FONT_PT * DPI / 72.0).toFloat())
    }
    private val frc = FontRenderContext(AffineTransform(), true, true)

    private val widthCache = HashMap<String, Double>()
    private fun textWidth(s: String): Double =
        if (s.isEmpty()) 0.0 else widthCache.getOrPut(s) { font.getStringBounds(s, frc).width }

    /** Pixels of text that fit on one line of a column that is [colWidth] characters wide. */
    fun usablePx(colWidth: Double): Double = floor(colWidth * MAX_DIGIT_PX + 0.5) - CELL_PADDING_PX

    /** Number of lines [text] occupies when wrapped into [usablePx] pixels. */
    fun lineCount(text: String, usablePx: Double): Int =
        text.replace("\r\n", "\n").replace('\r', '\n').split('\n').sumOf { paragraphLines(it, usablePx) }

    private fun paragraphLines(p: String, limit: Double): Int {
        var lines = 1
        var x = 0.0
        for (token in TOKEN.findAll(p).map { it.value }) {
            // Trailing spaces hang past the edge, so only the word itself has to fit.
            val word = token.trimEnd(' ')
            val w = textWidth(word)
            if (w <= limit - x) { x += textWidth(token); continue }
            if (w <= limit) { lines++; x = textWidth(token); continue }
            // Wider than a whole line: fill the current line, then break mid-word.
            word.codePoints().forEach { cp ->
                val cw = textWidth(String(Character.toChars(cp)))
                if (cw > limit - x && x > 0) { lines++; x = 0.0 }
                x += cw
            }
        }
        return lines
    }

    /** A word with its trailing spaces; a hyphen also ends a word (Excel may break after it). */
    private val TOKEN = Regex("""[^ \-]*-+ *|[^ \-]+ *| +""")

    /** Row height in points for the tallest of [cells] (each text paired with its column width), or null if one line. */
    fun heightPt(cells: List<Pair<String, Double>>): Double? {
        val lines = cells.maxOfOrNull { (t, w) -> lineCount(t, usablePx(w)) } ?: 1
        if (lines <= 1) return null
        return minOf(MAX_ROW_PT, lines * LINE_PT)
    }
}

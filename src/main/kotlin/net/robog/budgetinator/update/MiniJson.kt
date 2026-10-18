package net.robog.budgetinator.update

/**
 * A small, strict RFC 8259 JSON parser — enough to read the GitHub releases API without pulling in a
 * dependency. Objects become [Map]<String, Any?>, arrays [List], numbers [Long] or [Double],
 * plus [String], [Boolean] and null.
 */
internal object MiniJson {
    class ParseException(msg: String) : RuntimeException(msg)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (p.i != text.length) p.fail("Trailing data")
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun fail(msg: String): Nothing = throw ParseException("$msg at offset $i")

        fun ws() { while (i < s.length && s[i].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) i++ }

        fun value(depth: Int): Any? {
            if (depth > 200) fail("Nesting too deep")
            if (i >= s.length) fail("Unexpected end")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else fail("Unexpected '$c'")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("Bad literal")
            i += word.length
            return v
        }

        fun obj(depth: Int): Map<String, Any?> {
            i++ // {
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail("Expected key")
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') fail("Expected ':'")
                i++
                ws()
                m[k] = value(depth + 1)
                ws()
                if (i >= s.length) fail("Unterminated object")
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> { i--; fail("Expected ',' or '}'") }
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            i++ // [
            val l = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                ws()
                l += value(depth + 1)
                ws()
                if (i >= s.length) fail("Unterminated array")
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return l
                    else -> { i--; fail("Expected ',' or ']'") }
                }
            }
        }

        fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("Unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail("Bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("Bad unicode escape")
                                val hex = s.substring(i, i + 4)
                                sb.append(hex.toIntOrNull(16)?.toChar() ?: fail("Bad unicode escape"))
                                i += 4
                            }
                            else -> fail("Bad escape '\\$e'")
                        }
                    }
                    c < ' ' -> fail("Control character in string")
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Number {
            val start = i
            if (s[i] == '-') i++
            if (i >= s.length) fail("Bad number")
            if (s[i] == '0') i++
            else if (s[i] in '1'..'9') while (i < s.length && s[i].isAsciiDigit()) i++
            else fail("Bad number")
            var fractional = false
            if (i < s.length && s[i] == '.') {
                fractional = true; i++
                if (i >= s.length || !s[i].isAsciiDigit()) fail("Bad number")
                while (i < s.length && s[i].isAsciiDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                fractional = true; i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i >= s.length || !s[i].isAsciiDigit()) fail("Bad number")
                while (i < s.length && s[i].isAsciiDigit()) i++
            }
            val t = s.substring(start, i)
            return if (fractional) t.toDouble() else t.toLongOrNull() ?: t.toDouble()
        }

        private fun Char.isAsciiDigit() = this in '0'..'9'
    }
}

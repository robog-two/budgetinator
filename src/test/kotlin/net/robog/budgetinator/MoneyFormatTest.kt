package net.robog.budgetinator

import net.robog.budgetinator.model.parseMoney
import net.robog.budgetinator.ui.money
import java.math.BigDecimal
import java.util.Locale
import kotlin.test.*

class MoneyFormatTest {
    private fun <T> withLocale(l: Locale, f: () -> T): T {
        val old = Locale.getDefault()
        Locale.setDefault(l)
        try { return f() } finally { Locale.setDefault(old) }
    }

    @Test fun `money uses US formatting`() {
        assertEquals("$1,234.50", money(BigDecimal("1234.5")))
        assertEquals("$0.00", money(BigDecimal.ZERO))
        assertEquals("$1,000,000.00", money(BigDecimal("1000000")))
    }

    @Test fun `money ignores the system locale`() {
        for (l in listOf(Locale.GERMANY, Locale.FRANCE, Locale.forLanguageTag("hi-IN"), Locale.forLanguageTag("ar-EG"))) {
            assertEquals("$12,345.68", withLocale(l) { money(BigDecimal("12345.678")) }, "locale $l")
        }
    }

    @Test fun `parsing is locale independent`() {
        withLocale(Locale.GERMANY) {
            assertEquals(BigDecimal("1200.00"), parseMoney("1,200.00"))
            assertEquals(BigDecimal("12.50"), parseMoney("$12.50"))
        }
    }
}

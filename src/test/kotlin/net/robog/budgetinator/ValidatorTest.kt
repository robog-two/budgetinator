package net.robog.budgetinator

import net.robog.budgetinator.model.*
import net.robog.budgetinator.validation.Severity
import net.robog.budgetinator.validation.Validator
import java.math.BigDecimal
import kotlin.test.*

class ValidatorTest {
    private fun item(id: Long, name: String = "Pizza", qty: String = "2", price: String = "25") =
        LineItem(id, ItemType.FOOD, name, "Wegmans", "2 per 10 people", qty, price)

    private fun valid(type: BudgetType = BudgetType.PROGRAMMING) = BudgetRequest(
        header = Header(
            type = type, semester = Semester.FALL, organization = "Club", eventName = "Movie Night",
            eventDate = "Fall 2030", location = "Textor 102", tripSize = "10", expectedAttendance = "40",
            heldBefore = false, usesCollegeServices = false, hasAdditionalFunding = false,
        ),
        items = listOf(item(1)),
    )

    private fun errs(r: BudgetRequest) = Validator.validate(r).filter { it.severity == Severity.ERROR }

    @Test fun `a complete request has no errors`() = assertEquals(emptyList(), errs(valid()))

    @Test fun `empty request reports required header fields`() {
        val fields = errs(BudgetRequest()).mapNotNull { it.field }.toSet()
        assertTrue(setOf("type", "organization", "eventName", "eventDate", "location", "heldBefore").all { it in fields })
    }

    @Test fun `semester is only required for operational and fundraising`() {
        val base = valid(BudgetType.OPERATIONAL).let { it.copy(header = it.header.copy(semester = null)) }
        assertTrue(errs(base).any { it.field == "semester" })
        assertTrue(errs(base.copy(header = base.header.copy(type = BudgetType.TRAVEL, tripSize = "5"))).none { it.field == "semester" })
    }

    @Test fun `travel needs trip size and programming needs expected attendance`() {
        val travel = valid(BudgetType.TRAVEL).let { it.copy(header = it.header.copy(tripSize = "")) }
        assertTrue(errs(travel).any { it.field == "tripSize" })
        val prog = valid().let { it.copy(header = it.header.copy(expectedAttendance = "0")) }
        assertTrue(errs(prog).any { it.field == "expectedAttendance" })
    }

    @Test fun `held before requires past attendance and cost`() {
        val r = valid().let { it.copy(header = it.header.copy(heldBefore = true)) }
        assertEquals(setOf("pastAttendance", "pastCost"), errs(r).mapNotNull { it.field }.toSet())
    }

    @Test fun `college services require selection and an invoice`() {
        val r = valid().let { it.copy(header = it.header.copy(usesCollegeServices = true)) }
        val e = errs(r)
        assertTrue(e.any { it.field == "collegeServices" })
        assertTrue(e.any { it.message.contains("invoices") })
    }

    @Test fun `programming over 1000 requires additional funding`() {
        val r = valid().copy(items = listOf(item(1, qty = "10", price = "100.01")))
        assertEquals(BigDecimal("1001"), r.grandTotal)
        assertTrue(errs(r).any { it.message.contains("over \$1000") })
        val withFunding = r.copy(
            header = r.header.copy(hasAdditionalFunding = true),
            funding = listOf(FundingSource(9, "Dept", "Co-sponsor", FundingStatus.PENDING, "300")),
        )
        assertEquals(emptyList(), errs(withFunding))
    }

    @Test fun `exactly 1000 does not trigger the threshold like the template formula`() {
        val r = valid().copy(items = listOf(item(1, qty = "10", price = "100")))
        assertEquals(emptyList(), errs(r))
    }

    @Test fun `grand total rounds up per item cents`() =
        assertEquals(BigDecimal("51"), valid().copy(items = listOf(item(1, qty = "3", price = "16.67"))).grandTotal)

    @Test fun `item quantity and price are validated`() {
        val r = valid().copy(items = listOf(item(1, qty = "0", price = "abc"), item(2, qty = "1.5", price = "-3")))
        val fields = errs(r).map { it.rowId to it.field }.toSet()
        assertTrue((1L to "quantity") in fields && (1L to "unitPrice") in fields)
        assertTrue((2L to "quantity") in fields && (2L to "unitPrice") in fields)
    }

    @Test fun `no items is an error but blank rows are ignored`() {
        val r = valid().copy(items = listOf(LineItem(1)))
        assertTrue(errs(r).any { it.message.contains("at least one budget item") })
    }

    @Test fun `price list warnings for starred items and overpriced items`() {
        val r = valid().copy(items = listOf(item(1, name = "Napkins (250 count)", price = "9")))
        val w = Validator.validate(r).filter { it.severity == Severity.WARNING }.map { it.message }
        assertTrue(w.any { it.contains("not funded") })
        assertTrue(w.any { it.contains("suggested price") })
    }

    @Test fun `funding answer must match funding rows`() {
        val yesNone = valid().let { it.copy(header = it.header.copy(hasAdditionalFunding = true)) }
        assertTrue(errs(yesNone).any { it.message.contains("at least one") })
        val noButListed = valid().copy(funding = listOf(FundingSource(5, "Dept", "x", FundingStatus.APPROVED, "10")))
        assertTrue(errs(noButListed).any { it.field == "hasAdditionalFunding" })
    }

    @Test fun `money parsing`() {
        assertEquals(BigDecimal("1200.00"), parseMoney("$1,200"))
        assertEquals(BigDecimal("12.50"), parseMoney("12.5"))
        assertNull(parseMoney("-1")); assertNull(parseMoney("1.234")); assertNull(parseMoney(""))
    }

    @Test fun `linking a document to an item changes the request`() {
        val doc = SupportingDoc(7, "q.png", byteArrayOf(1), ImageFormat.PNG)
        val before = valid().copy(docs = listOf(doc))
        val after = before.copy(docs = listOf(doc.copy(itemIds = setOf(1L))))
        assertNotEquals(before, after)
    }
}

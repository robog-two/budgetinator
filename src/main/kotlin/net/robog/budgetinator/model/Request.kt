package net.robog.budgetinator.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The normalized in-memory model. Everything the user types is kept as raw text so the UI can
 * show half-typed input; [Validator][net.robog.budgetinator.validation.Validator] parses it.
 * Relationships are by id (a document references items by id), not by nesting.
 */
data class Header(
    val type: BudgetType? = null,
    val semester: Semester? = null,
    val organization: String = "",
    val eventName: String = "",
    val eventDate: String = "",
    val location: String = "",
    val tripSize: String = "",
    val expectedAttendance: String = "",
    val heldBefore: Boolean? = null,
    val pastAttendance: String = "",
    val pastCost: String = "",
    val usesCollegeServices: Boolean? = null,
    val collegeServices: Set<CollegeService> = emptySet(),
    val hasAdditionalFunding: Boolean? = null,
)

data class LineItem(
    val id: Long,
    val type: ItemType? = null,
    val name: String = "",
    val location: String = "",
    val reasoning: String = "",
    val quantity: String = "",
    val unitPrice: String = "",
) {
    val total: BigDecimal? get() {
        val q = parseWholeNumber(quantity) ?: return null
        val p = parseMoney(unitPrice) ?: return null
        return p.multiply(q.toBigDecimal())
    }
    val isBlank get() = type == null && name.isBlank() && location.isBlank() && reasoning.isBlank() &&
        quantity.isBlank() && unitPrice.isBlank()
}

data class FundingSource(
    val id: Long,
    val source: String = "",
    val rationale: String = "",
    val status: FundingStatus? = null,
    val amount: String = "",
) {
    val isBlank get() = source.isBlank() && rationale.isBlank() && status == null && amount.isBlank()
}

/** An image for the "Supporting Documentation" sheet. [itemIds] is the item(s) it backs up. */
data class SupportingDoc(
    val id: Long,
    val fileName: String,
    val bytes: ByteArray,
    val format: ImageFormat,
    val itemIds: Set<Long> = emptySet(),
    val note: String = "",
)
// Note: no custom equals. An id-only equals made "link to item" edits compare equal to the old
// value, so Compose never saw a state change. ByteArray compares by reference, which is fine
// because copy() keeps the same array.

enum class ImageFormat(val ext: String, val mime: String) { PNG("png", "image/png"), JPEG("jpeg", "image/jpeg"), GIF("gif", "image/gif") }

data class BudgetRequest(
    val header: Header = Header(),
    val items: List<LineItem> = emptyList(),
    val funding: List<FundingSource> = emptyList(),
    val docs: List<SupportingDoc> = emptyList(),
) {
    /** Items with anything in them (blank rows are dropped on export). */
    val filledItems get() = items.filterNot { it.isBlank }
    val filledFunding get() = funding.filterNot { it.isBlank }

    val total: BigDecimal get() = filledItems.fold(BigDecimal.ZERO) { a, i -> a + (i.total ?: BigDecimal.ZERO) }

    /** Template cell F11: ROUNDUP(total, 0). */
    val grandTotal: BigDecimal get() = total.setScale(0, RoundingMode.UP)

    val fundingTotal: BigDecimal get() = filledFunding.fold(BigDecimal.ZERO) { a, f -> a + (parseMoney(f.amount) ?: BigDecimal.ZERO) }
}

private val MONEY = Regex("""^\$?\s*(\d{1,3}(,\d{3})+|\d+)(\.\d{1,2})?$""")
private val WHOLE = Regex("""^\d{1,9}$""")

/** "12", "$12.50", "1,200.00" -> BigDecimal; anything else (incl. negatives, 3 decimals) -> null. */
fun parseMoney(s: String): BigDecimal? {
    val t = s.trim()
    if (!MONEY.matches(t)) return null
    return t.removePrefix("$").trim().replace(",", "").toBigDecimal().setScale(2, RoundingMode.UNNECESSARY)
}

fun parseWholeNumber(s: String): Int? = s.trim().takeIf { WHOLE.matches(it) }?.toInt()

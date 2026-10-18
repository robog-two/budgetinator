package net.robog.budgetinator.validation

import net.robog.budgetinator.model.*
import java.math.BigDecimal

enum class Severity { ERROR, WARNING }

/** Wizard pages, in order. Issues carry the step so the review page can jump back to fix them. */
enum class Step(val title: String) {
    BASICS("Request basics"),
    DETAILS("Event details"),
    ITEMS("Budget items"),
    FUNDING("Additional funding"),
    DOCS("Supporting documents"),
    REVIEW("Review & export"),
}

data class Issue(
    val severity: Severity,
    val step: Step,
    val message: String,
    /** Item/funding row this concerns, if any, so the UI can mark that row. */
    val rowId: Long? = null,
    val field: String? = null,
)

/**
 * Every rule below comes from the SGC template: its cell prompts, conditional formatting,
 * the D10 formula, and the Instructions PDF. ERRORs block export; WARNINGs do not.
 */
object Validator {

    fun validate(r: BudgetRequest): List<Issue> = buildList {
        header(r.header, r)
        items(r)
        funding(r)
        docs(r)
    }

    fun errors(r: BudgetRequest) = validate(r).filter { it.severity == Severity.ERROR }

    private fun MutableList<Issue>.err(step: Step, msg: String, rowId: Long? = null, field: String? = null) =
        add(Issue(Severity.ERROR, step, msg, rowId, field))

    private fun MutableList<Issue>.warn(step: Step, msg: String, rowId: Long? = null, field: String? = null) =
        add(Issue(Severity.WARNING, step, msg, rowId, field))

    private fun MutableList<Issue>.header(h: Header, r: BudgetRequest) {
        val b = Step.BASICS
        val d = Step.DETAILS
        val type = h.type
        if (type == null) err(b, "Choose the type of funding request.", field = "type")
        if (type?.asksSemester == true && h.semester == null)
            err(b, "Choose the semester this request is for.", field = "semester")
        if (h.organization.isBlank()) err(b, "Enter your organization.", field = "organization")
        if (h.eventName.isBlank()) err(b, "Enter the name of the event (${type?.eventNameHint ?: "see request type"}).", field = "eventName")
        if (h.eventDate.isBlank()) err(b, "Enter the date of the event (e.g. Fall 2030, April 17, 2018).", field = "eventDate")
        if (h.location.isBlank()) err(b, "Enter the location of the event (e.g. Emerson Suites, Textor 102).", field = "location")

        if (type?.asksTripSize == true) {
            val n = parseWholeNumber(h.tripSize)
            if (n == null || n < 1) err(d, "Enter how many people are going on the trip (a whole number, 1 or more).", field = "tripSize")
        }
        if (type?.asksExpectedAttendance == true) {
            val n = parseWholeNumber(h.expectedAttendance)
            if (n == null || n < 1) err(d, "Enter how many people you expect at the event (a whole number, 1 or more).", field = "expectedAttendance")
        }

        when (h.heldBefore) {
            null -> err(d, "Say whether this event was held before.", field = "heldBefore")
            true -> {
                if (parseWholeNumber(h.pastAttendance) == null)
                    err(d, "Enter how many people attended the past event (a whole number, 0 or more).", field = "pastAttendance")
                if (parseMoney(h.pastCost) == null)
                    err(d, "Enter what the past event cost, e.g. 250 or 250.00.", field = "pastCost")
            }
            false -> Unit
        }

        when (h.usesCollegeServices) {
            null -> err(d, "Say whether the event will use College Services.", field = "usesCollegeServices")
            true -> {
                if (h.collegeServices.isEmpty())
                    err(d, "Pick which College Services you will use (Print Shop, CCES, Public Safety, Other).", field = "collegeServices")
                if (r.docs.isEmpty())
                    err(Step.DOCS, "College Services are used, so invoices from them must be included in the supporting documentation.")
            }
            false -> Unit
        }

        if (h.hasAdditionalFunding == null) err(Step.FUNDING, "Say whether you have additional funding sources.", field = "hasAdditionalFunding")
    }

    private fun MutableList<Issue>.items(r: BudgetRequest) {
        val s = Step.ITEMS
        val filled = r.filledItems
        if (filled.isEmpty()) err(s, "Add at least one budget item.")
        for ((idx, it) in filled.withIndex()) {
            val label = "Item ${idx + 1}" + it.name.trim().takeIf(String::isNotEmpty)?.let { n -> " (\"$n\")" }.orEmpty()
            if (it.type == null) err(s, "$label: choose an item type.", it.id, "type")
            if (it.name.isBlank()) err(s, "$label: enter an item name (e.g. Pizza, Streamers, Student Workers).", it.id, "name")
            if (it.location.isBlank()) err(s, "$label: enter where it is purchased (e.g. Walmart, Amazon, CCES).", it.id, "location")
            if (it.reasoning.isBlank())
                err(s, "$label: explain the source/reasoning — how you calculated the price, why you need it.", it.id, "reasoning")
            val q = parseWholeNumber(it.quantity)
            if (q == null || q < 1) err(s, "$label: quantity must be a whole number, 1 or more.", it.id, "quantity")
            val p = parseMoney(it.unitPrice)
            if (p == null) err(s, "$label: amount per item must be a dollar amount like 12.50.", it.id, "unitPrice")
            else if (p.signum() <= 0) err(s, "$label: amount per item must be more than \$0.", it.id, "unitPrice")

            val hit = PriceList.match(it.name)
            if (hit != null) {
                if (hit.notFundedIfProvided)
                    warn(s, "$label: ${PriceList.STAR_NOTE.removePrefix("*")} — don't request this if your food provider includes it.", it.id, "name")
                if (p != null && p > hit.price)
                    warn(s, "$label: \$$p is above the suggested price of \$${hit.price} at ${hit.location}. Explain why in the reasoning and attach a quote.", it.id, "unitPrice")
            }
            if (it.type == ItemType.FOOD && r.docs.none { d -> it.id in d.itemIds } && hit == null)
                warn(s, "$label: no supporting documentation (e.g. a menu or quote) is attached to this item.", it.id)
        }
    }

    private fun MutableList<Issue>.funding(r: BudgetRequest) {
        val s = Step.FUNDING
        val h = r.header
        val rows = r.filledFunding
        for ((idx, f) in rows.withIndex()) {
            val label = "Source ${idx + 1}" + f.source.trim().takeIf(String::isNotEmpty)?.let { n -> " (\"$n\")" }.orEmpty()
            if (f.source.isBlank()) err(s, "$label: enter the source (e.g. Department, Fundraiser Name, Chipotle).", f.id, "source")
            if (f.rationale.isBlank()) err(s, "$label: enter the rationale/explanation.", f.id, "rationale")
            if (f.status == null) err(s, "$label: choose a status (Approved, Pending or Denied).", f.id, "status")
            val a = parseMoney(f.amount)
            if (a == null) err(s, "$label: amount must be a dollar amount like 250 or 250.00.", f.id, "amount")
            else if (a.signum() <= 0) err(s, "$label: amount must be more than \$0.", f.id, "amount")
        }
        if (h.hasAdditionalFunding == true && rows.isEmpty())
            err(s, "You said you have additional funding sources — list at least one.")
        if (h.hasAdditionalFunding == false && rows.isNotEmpty())
            err(s, "You listed additional funding sources but answered \"No\" to having any. Fix one of them.", field = "hasAdditionalFunding")

        // Template cell D10: Programming over $1000 must show additional fundraising (Step 7).
        val over = r.grandTotal > BigDecimal(Rules.ADDITIONAL_FUNDING_THRESHOLD)
        if (h.type?.category == Category.PROGRAMMING && over && rows.isEmpty())
            err(s, "Programming requests over \$${Rules.ADDITIONAL_FUNDING_THRESHOLD} must list additional fundraising sources (Instructions, step 7).")

        if (rows.any { it.status == FundingStatus.DENIED })
            warn(s, "A denied source is listed. The template still adds its amount into the funding total — consider removing it.")
        if (h.type?.category in setOf(Category.TRAVEL, Category.FUNDRAISING) && h.hasAdditionalFunding == false)
            warn(s, "Travel and fundraising requests receiving outside funding should list it (Instructions, step 7). Skip this if you truly have none.")
    }

    private fun MutableList<Issue>.docs(r: BudgetRequest) {
        val s = Step.DOCS
        if (r.filledItems.isNotEmpty() && r.docs.isEmpty())
            warn(s, "No supporting documentation attached. SGC expects quotes, receipts or screenshots backing the prices.")
        for (d in r.docs) if (d.itemIds.isEmpty() && r.filledItems.isNotEmpty())
            warn(s, "\"${d.fileName}\" is not linked to any budget item.", d.id)
    }
}

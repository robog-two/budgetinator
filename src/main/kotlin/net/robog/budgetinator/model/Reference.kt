package net.robog.budgetinator.model

/**
 * Reference data lifted from the SGC request template: the dropdown lists on the hidden
 * "Hidden Extra Work" sheet and the "Suggested Price List" sheet. Labels are the exact
 * strings Excel's list validation expects, so they are written to the sheet verbatim.
 */
enum class Category { OPERATIONAL, PROGRAMMING, TRAVEL, FUNDRAISING }

enum class BudgetType(val label: String, val category: Category, val nextYear: Boolean) {
    OPERATIONAL("Operational", Category.OPERATIONAL, false),
    PROGRAMMING("Programming", Category.PROGRAMMING, false),
    TRAVEL("Travel", Category.TRAVEL, false),
    FUNDRAISING("Fundraising", Category.FUNDRAISING, false),
    OPERATIONAL_NEXT("Operational (next academic semester)", Category.OPERATIONAL, true),
    PROGRAMMING_NEXT("Programming (next academic year Block 1)", Category.PROGRAMMING, true),
    TRAVEL_NEXT("Travel (next academic year Block 1)", Category.TRAVEL, true),
    FUNDRAISING_NEXT("Fundraising (next academic year Block 1)", Category.FUNDRAISING, true);

    /** Template conditional formatting asks "What semester and year" for these only. */
    val asksSemester get() = category == Category.OPERATIONAL || category == Category.FUNDRAISING
    val asksTripSize get() = category == Category.TRAVEL
    val asksExpectedAttendance get() = category == Category.PROGRAMMING

    /** What the template's prompt on "Name of Event" says to enter for this request type. */
    val eventNameHint
        get() = when (category) {
            Category.OPERATIONAL -> "e.g. Fall 2030"
            Category.TRAVEL -> "Name of conference, event, location, etc."
            else -> "Name of the event"
        }
}

enum class Semester(val label: String) {
    FALL("Fall"),
    SPRING("Spring"),
    FALL_OPERATIONAL_NEXT("Fall Operational (next academic year)"),
}

enum class ItemType(val label: String) {
    FOOD("Food"),
    ADVERTISEMENT("Advertisement"),
    SUPPLIES("Supplies"),
    DECORATIONS("Decorations"),
    ENTERTAINMENT("Entertainment"),
    AUDIO_VISUAL("Audio & Visual"),
    VENUE("Venue"),
    OTHER("Other"),
}

/** The (orphaned, unlabeled) dropdown on cell D11 of the template. */
enum class CollegeService(val label: String) {
    PRINT_SHOP("IC Print Shop"),
    CCES("CCES"),
    PUBLIC_SAFETY("Public Safety"),
    OTHER("Other"),
}

enum class FundingStatus(val label: String) { PENDING("Pending"), APPROVED("Approved"), DENIED("Denied") }

data class PriceListEntry(
    val name: String,
    val location: String,
    val price: java.math.BigDecimal,
    val type: ItemType,
    /** True for the "*" rows: SGC does not fund these if the food provider already includes them. */
    val notFundedIfProvided: Boolean = false,
)

object PriceList {
    private fun e(n: String, l: String, p: Int, t: ItemType, star: Boolean = false) =
        PriceListEntry(n, l, p.toBigDecimal().setScale(2), t, star)

    const val STAR_NOTE = "*If included by food provider, not funded!"

    val entries = listOf(
        e("Chips", "Wegmans", 3, ItemType.FOOD),
        e("Pretzels", "Wegmans", 2, ItemType.FOOD),
        e("Cookies", "Wegmans", 6, ItemType.FOOD),
        e("Juice", "Wegmans", 4, ItemType.FOOD),
        e("Soda", "Wegmans", 1, ItemType.FOOD),
        e("Veggie Platter", "Wegmans", 15, ItemType.FOOD),
        e("Sandwich Tray", "Wegmans", 30, ItemType.FOOD),
        e("Cake Mix", "Wegmans", 2, ItemType.FOOD),
        e("Frosting", "Wegmans", 2, ItemType.FOOD),
        e("Popcorn", "Wegmans", 4, ItemType.FOOD),
        e("Sheet Pizza", "Wegmans", 25, ItemType.FOOD),
        e("Wings", "Wegmans", 20, ItemType.FOOD),
        e("Disposable Cups (50 count)", "Wegmans", 3, ItemType.SUPPLIES, star = true),
        e("Paper Plates (100 count)", "Wegmans", 5, ItemType.SUPPLIES, star = true),
        e("Napkins (250 count)", "Wegmans", 3, ItemType.SUPPLIES, star = true),
        e("Disposable Cutlery (48 count)", "Wegmans", 3, ItemType.SUPPLIES, star = true),
        e("Poster Board", "Walmart", 3, ItemType.SUPPLIES),
        e("Ithaca College Vehicle (per day)", "IC Office of Facilities", 45, ItemType.OTHER),
    )

    fun match(name: String): PriceListEntry? =
        entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
}

/** Numbers pulled from the template's own rules. */
object Rules {
    /** Programming requests above this many whole dollars must list additional funding (Step 7, cell D10). */
    const val ADDITIONAL_FUNDING_THRESHOLD = 1000

    /** Rows physically present in the template's item table; the exporter grows it beyond this. */
    const val TEMPLATE_ITEM_ROWS = 22
    const val TEMPLATE_FUNDING_ROWS = 6
}

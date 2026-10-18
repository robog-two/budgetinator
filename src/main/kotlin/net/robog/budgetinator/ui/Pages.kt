package net.robog.budgetinator.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.robog.budgetinator.model.*
import net.robog.budgetinator.validation.Severity
import net.robog.budgetinator.validation.Step
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.imageio.ImageIO

/** Inline error for a header field, shown only once the user has moved past the step. */
private fun BudgetStore.err(step: Step, field: String, rowId: Long? = null): String? =
    if (!showErrorsFor(step)) null
    else issues.firstOrNull { it.severity == Severity.ERROR && it.field == field && it.rowId == rowId }?.message

@Composable
fun BasicsPage(store: BudgetStore) {
    val h = store.request.header
    PageTitle("Request basics", "What kind of request is this, and for which organization and event?")
    SectionCard {
        Dropdown(
            "Type of funding request", h.type, BudgetType.entries, { it.label },
            { t -> store.updateHeader { it.copy(type = t) } },
            error = store.err(Step.BASICS, "type"),
        )
        h.type?.let {
            Callout(
                when (it.category) {
                    Category.OPERATIONAL -> "Operational: you'll be asked for the semester and year."
                    Category.PROGRAMMING -> "Programming: you'll be asked how many people you expect. Requests over \$1,000 must also list additional fundraising sources."
                    Category.TRAVEL -> "Travel: you'll be asked how many people are going on the trip."
                    Category.FUNDRAISING -> "Fundraising: you'll be asked for the semester and year."
                } + if (it.nextYear) " This is a next-academic-year (Block 1) request." else ""
            )
        }
        if (h.type?.asksSemester == true)
            Dropdown("What semester and year is the request for?", h.semester, Semester.entries, { it.label },
                { s -> store.updateHeader { it.copy(semester = s) } }, error = store.err(Step.BASICS, "semester"))
        Field("Organization", h.organization, { v -> store.updateHeader { it.copy(organization = v) } },
            error = store.err(Step.BASICS, "organization"), support = "Type the name of your organization")
        Field("Name of event", h.eventName, { v -> store.updateHeader { it.copy(eventName = v) } },
            placeholder = h.type?.eventNameHint,
            error = store.err(Step.BASICS, "eventName"),
            support = "Operational: e.g. Fall 2012 · Programming/Fundraising: name of event · Travel: name of conference, event, location")
        Field("Date of event", h.eventDate, { v -> store.updateHeader { it.copy(eventDate = v) } },
            error = store.err(Step.BASICS, "eventDate"), support = "The date you're requesting money for, e.g. Fall 2030 or April 17, 2018")
        Field("Location of event", h.location, { v -> store.updateHeader { it.copy(location = v) } },
            error = store.err(Step.BASICS, "location"), support = "e.g. Emerson Suites, Textor 102, State Theatre of Ithaca")
    }
}

@Composable
fun DetailsPage(store: BudgetStore) {
    val h = store.request.header
    PageTitle("Event details", "A few follow-up questions. Only the ones that apply to your request are shown.")
    SectionCard {
        if (h.type == null) Callout("Pick a request type on the previous page to see type-specific questions.", color = Warn)
        if (h.type?.asksTripSize == true)
            Field("How many people are going on the trip?", h.tripSize, { v -> store.updateHeader { it.copy(tripSize = v) } },
                numeric = true, error = store.err(Step.DETAILS, "tripSize"))
        if (h.type?.asksExpectedAttendance == true)
            Field("How many people are you expecting to come to this event?", h.expectedAttendance,
                { v -> store.updateHeader { it.copy(expectedAttendance = v) } },
                numeric = true, error = store.err(Step.DETAILS, "expectedAttendance"))

        YesNo("Was this event held before?", h.heldBefore, { v -> store.updateHeader { it.copy(heldBefore = v) } },
            error = store.err(Step.DETAILS, "heldBefore"))
        if (h.heldBefore == true) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Field("How many people attended in the past?", h.pastAttendance,
                    { v -> store.updateHeader { it.copy(pastAttendance = v) } }, Modifier.weight(1f),
                    numeric = true, error = store.err(Step.DETAILS, "pastAttendance"))
                Field("How much did it cost in the past?", h.pastCost,
                    { v -> store.updateHeader { it.copy(pastCost = v) } }, Modifier.weight(1f),
                    numeric = true, prefix = "$", error = store.err(Step.DETAILS, "pastCost"))
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    SectionCard {
        YesNo("Will this event be using College Services?", h.usesCollegeServices,
            { v -> store.updateHeader { it.copy(usesCollegeServices = v) } },
            error = store.err(Step.DETAILS, "usesCollegeServices"))
        if (h.usesCollegeServices == true) {
            Text("Which services?", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CollegeService.entries.forEach { s ->
                    FilterChip(s in h.collegeServices,
                        { store.updateHeader { it.copy(collegeServices = if (s in it.collegeServices) it.collegeServices - s else it.collegeServices + s) } },
                        { Text(s.label) })
                }
            }
            store.err(Step.DETAILS, "collegeServices")?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Callout("Include invoices from them with your supporting documentation (step 5).")
        }
    }
    Spacer(Modifier.height(16.dp))
    SectionCard {
        YesNo("Do you have additional funding sources?", h.hasAdditionalFunding,
            { v -> store.updateHeader { it.copy(hasAdditionalFunding = v) } },
            error = store.err(Step.FUNDING, "hasAdditionalFunding"),
            hint = "Outside money: department funds, fundraisers, sponsors. Required if a programming request is over \$1,000.")
    }
}

@Composable
fun ItemsPage(store: BudgetStore) {
    val req = store.request
    PageTitle("Budget items", "One card per thing you're asking money for. Start from the SGC suggested price list, or add your own.")
    val showErr = store.showErrorsFor(Step.ITEMS)
    req.items.forEachIndexed { idx, item ->
        key(item.id) {
            ItemCard(store, idx, item, showErr)
            Spacer(Modifier.height(12.dp))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Button({ store.addItem() }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add item") }
        var open by remember { mutableStateOf(false) }
        Box {
            OutlinedButton({ open = true }) { Text("Add from price list") }
            DropdownMenu(open, { open = false }) {
                PriceList.entries.forEach { e ->
                    DropdownMenuItem(
                        text = { Text("${e.name} — ${money(e.price)} at ${e.location}${if (e.notFundedIfProvided) "  *" else ""}") },
                        onClick = { store.addItem(e); open = false },
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(PriceList.STAR_NOTE.replace("*If", "* If"), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ItemCard(store: BudgetStore, idx: Int, item: LineItem, showErr: Boolean) {
    fun e(field: String) =
        if (!showErr) null else store.issues.firstOrNull { it.rowId == item.id && it.field == field && it.severity == Severity.ERROR }?.message
            ?.substringAfter(": ")
    val warns = store.issues.filter { it.rowId == item.id && it.severity == Severity.WARNING }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Item ${idx + 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            Text("Total ${item.total?.let(::money) ?: "—"}", style = MaterialTheme.typography.titleMedium)
            IconButton({ store.removeItem(item.id) }) { Icon(Icons.Default.Delete, "Remove item") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Dropdown("Item type", item.type, ItemType.entries, { it.label },
                { t -> store.updateItem(item.id) { it.copy(type = t) } }, Modifier.weight(1f), error = e("type"))
            Field("Item name", item.name, { v ->
                store.updateItem(item.id) { cur ->
                    val hit = PriceList.match(v)
                    if (hit != null) cur.copy(name = v, type = cur.type ?: hit.type,
                        location = cur.location.ifBlank { hit.location },
                        unitPrice = cur.unitPrice.ifBlank { hit.price.toPlainString() })
                    else cur.copy(name = v)
                }
            }, Modifier.weight(2f), placeholder = "e.g. Pizza, Streamers, Student Workers", error = e("name"))
            Field("Purchase location", item.location, { v -> store.updateItem(item.id) { it.copy(location = v) } },
                Modifier.weight(1.5f), placeholder = "e.g. Walmart, Amazon, CCES", error = e("location"))
        }
        Field("Source / reasoning", item.reasoning, { v -> store.updateItem(item.id) { it.copy(reasoning = v) } },
            singleLine = false, minLines = 2, error = e("reasoning"),
            support = "How you calculated the gas price, hotel cost, per-item cost — why you need it")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("Quantity", item.quantity, { v -> store.updateItem(item.id) { it.copy(quantity = v) } }, Modifier.weight(1f),
                numeric = true, error = e("quantity"), support = "Number of items you're purchasing")
            Field("Amount per item", item.unitPrice, { v -> store.updateItem(item.id) { it.copy(unitPrice = v) } },
                Modifier.weight(1f), numeric = true, prefix = "$", error = e("unitPrice"), support = "Dollar amount per unit")
        }
        warns.forEach { IssueLine(it.copy(message = it.message.substringAfter(": "))) }
    }
}

@Composable
fun FundingPage(store: BudgetStore) {
    val h = store.request.header
    PageTitle("Additional funding", "Money coming from somewhere other than SGC for this event.")
    val over = h.type?.category == Category.PROGRAMMING &&
        store.request.grandTotal > java.math.BigDecimal(Rules.ADDITIONAL_FUNDING_THRESHOLD)
    if (over) {
        Callout("Your programming budget is over \$1,000 — additional fundraising sources are required (Instructions, step 7).", color = Warn)
        Spacer(Modifier.height(12.dp))
    }
    when (h.hasAdditionalFunding) {
        null -> Callout("Answer \"Do you have additional funding sources?\" on the Event details page.", color = Warn)
        false -> {
            Callout("You answered No. Nothing to enter here.")
            if (over) {
                Spacer(Modifier.height(12.dp))
                Button({ store.updateHeader { it.copy(hasAdditionalFunding = true) } }) { Text("Actually, I do have additional funding") }
            }
        }
        true -> {
            val showErr = store.showErrorsFor(Step.FUNDING)
            store.request.funding.forEachIndexed { idx, f ->
                key(f.id) {
                    FundingCard(store, idx, f, showErr)
                    Spacer(Modifier.height(12.dp))
                }
            }
            Button({ store.addFunding() }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add source") }
            Spacer(Modifier.height(12.dp))
            Text("Total additional funding: ${money(store.request.fundingTotal)}", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun FundingCard(store: BudgetStore, idx: Int, f: FundingSource, showErr: Boolean) {
    fun e(field: String) =
        if (!showErr) null else store.issues.firstOrNull { it.rowId == f.id && it.field == field && it.severity == Severity.ERROR }?.message
            ?.substringAfter(": ")
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Source ${idx + 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton({ store.removeFunding(f.id) }) { Icon(Icons.Default.Delete, "Remove source") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("Source", f.source, { v -> store.updateFunding(f.id) { it.copy(source = v) } }, Modifier.weight(2f),
                error = e("source"), support = "Department, fundraiser name, Chipotle…")
            Dropdown("Status", f.status, FundingStatus.entries, { it.label },
                { s -> store.updateFunding(f.id) { it.copy(status = s) } }, Modifier.weight(1f), error = e("status"))
            Field("Amount", f.amount, { v -> store.updateFunding(f.id) { it.copy(amount = v) } }, Modifier.weight(1f),
                numeric = true, prefix = "$", error = e("amount"), support = "Expected or collected")
        }
        Field("Rationale / explanation", f.rationale, { v -> store.updateFunding(f.id) { it.copy(rationale = v) } },
            singleLine = false, minLines = 2, error = e("rationale"))
    }
}

@Composable
fun DocsPage(store: BudgetStore, window: Frame) {
    val req = store.request
    var message by remember { mutableStateOf<String?>(null) }
    PageTitle("Supporting documentation", "Screenshots, quotes and invoices that back up your prices. They are embedded on the Supporting Documentation sheet.")
    if (req.header.usesCollegeServices == true)
        Callout("You are using College Services — attach their invoices here.", color = Warn).also { Spacer(Modifier.height(12.dp)) }
    Button({
        val fd = FileDialog(window, "Choose supporting documentation", FileDialog.LOAD).apply {
            isMultipleMode = true
            setFilenameFilter { _, n -> n.lowercase().substringAfterLast('.') in setOf("png", "jpg", "jpeg", "gif") }
            isVisible = true
        }
        message = fd.files.mapNotNull { store.addDocument(it) }.joinToString("\n").ifBlank { null }
    }) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text("Add images…") }
    message?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
    Spacer(Modifier.height(16.dp))
    if (req.docs.isEmpty()) Text("Nothing attached yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    req.docs.forEach { d ->
        key(d.id) {
            SectionCard {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val bmp = remember(d.id) { ImageIO.read(d.bytes.inputStream())?.toComposeImageBitmap() }
                    if (bmp != null) Image(bmp, d.fileName, Modifier.size(140.dp), contentScale = ContentScale.Fit)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.fileName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            IconButton({ store.removeDoc(d.id) }) { Icon(Icons.Default.Delete, "Remove") }
                        }
                        Text("Which items does this back up?", style = MaterialTheme.typography.labelLarge)
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            req.items.filterNot { it.isBlank }.forEachIndexed { i, it ->
                                FilterChip(it.id in d.itemIds,
                                    { store.updateDoc(d.id) { x -> x.copy(itemIds = if (it.id in x.itemIds) x.itemIds - it.id else x.itemIds + it.id) } },
                                    { Text(it.name.ifBlank { "Item ${i + 1}" }) })
                            }
                        }
                        Field("Note (optional)", d.note, { v -> store.updateDoc(d.id) { it.copy(note = v) } })
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
fun ReviewPage(store: BudgetStore, window: Frame) {
    val req = store.request
    val h = req.header
    var result by remember { mutableStateOf<Result<File>?>(null) }
    PageTitle("Review & export", "Check everything, fix anything flagged, then generate the filled-in SGC workbook.")
    SectionCard {
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Stat("Grand total (rounded up)", money(req.grandTotal))
            Stat("Additional funding", money(req.fundingTotal))
            Stat("Items", req.filledItems.size.toString())
            Stat("Documents", req.docs.size.toString())
        }
        HorizontalDivider()
        Text(
            listOf(h.type?.label, h.organization, h.eventName, h.eventDate, h.location).filterNot { it.isNullOrBlank() }.joinToString(" · ")
                .ifBlank { "No header details yet" },
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    Spacer(Modifier.height(16.dp))
    val grouped = store.issues.groupBy { it.step }
    if (store.issues.isEmpty()) Callout("Everything checks out. You're ready to export.")
    else Step.entries.forEach { s ->
        val list = grouped[s].orEmpty()
        if (list.isNotEmpty()) {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton({ store.goTo(s) }) { Text("Fix on this page") }
                }
                list.forEach { IssueLine(it) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    Spacer(Modifier.height(8.dp))
    Button(
        enabled = store.errorCount == 0,
        onClick = {
            val fd = FileDialog(window, "Save budget request", FileDialog.SAVE).apply {
                file = defaultFileName(req)
                isVisible = true
            }
            val name = fd.file ?: return@Button
            val out = File(fd.directory, if (name.endsWith(".xlsx")) name else "$name.xlsx")
            result = runCatching { net.robog.budgetinator.export.XlsxExporter.export(req, out.toPath()); store.markExported(); out }
        },
    ) { Text(if (store.errorCount == 0) "Generate budget request (.xlsx)" else "Fix ${store.errorCount} error(s) to export") }
    result?.let { r ->
        Spacer(Modifier.height(12.dp))
        r.onSuccess { Callout("Saved ${it.absolutePath}") }
            .onFailure { Text("Export failed: ${it.message}", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    }
}

private fun defaultFileName(r: BudgetRequest): String {
    val base = listOf(r.header.organization, r.header.eventName).filter { it.isNotBlank() }.joinToString(" - ")
        .replace(Regex("""[\\/:*?"<>|]"""), "").ifBlank { "Budget Request" }
    return "$base - SGC Budget Request.xlsx"
}

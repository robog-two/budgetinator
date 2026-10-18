package net.robog.budgetinator.model

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.robog.budgetinator.validation.Issue
import net.robog.budgetinator.validation.Severity
import net.robog.budgetinator.validation.Step
import net.robog.budgetinator.validation.Validator
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/** Single in-memory source of truth for the wizard. Rows reference each other by id. */
class BudgetStore {
    var request by mutableStateOf(BudgetRequest())
        private set
    var step by mutableStateOf(Step.BASICS)
        private set

    /** Steps the user has moved past; validation messages only show for these (no red on first view). */
    var visited by mutableStateOf(emptySet<Step>())
        private set

    private var nextId = 1L
    private fun id() = nextId++

    val issues: List<Issue> by derivedStateOf { Validator.validate(request) }
    val errorCount by derivedStateOf { issues.count { it.severity == Severity.ERROR } }
    val warningCount by derivedStateOf { issues.count { it.severity == Severity.WARNING } }

    // ---- document state (draft save/load, see persist/DraftFile.kt)

    /** The .budget file this request was last saved to / opened from; null for an untitled request. */
    var currentFile by mutableStateOf<File?>(null)
        private set

    /** Snapshot as of the last save/open (or New/app start). */
    private var savedBaseline by mutableStateOf(BudgetRequest())
    /** Snapshot as of the last Excel export. */
    private var exportedBaseline by mutableStateOf(BudgetRequest())

    /** Differs from the draft file on disk: drives the title's "Edited" marker and autosave. */
    val isEdited: Boolean get() = request != savedBaseline

    /**
     * Would closing lose work? False right after a save or an export: an exported workbook is
     * itself a finished copy of the request, so (as before drafts existed) quitting right after
     * exporting doesn't nag.
     */
    val isDirty: Boolean get() = request != savedBaseline && request != exportedBaseline

    fun markExported() { exportedBaseline = request }

    /** Call after [saved] (a snapshot of [request]) was written to [file]. */
    fun markSaved(file: File, saved: BudgetRequest = request) {
        currentFile = file
        savedBaseline = saved
    }

    /** Fresh, untitled request (File > New). */
    fun newRequest() {
        nextId = 1L
        load(BudgetRequest(items = listOf(LineItem(id()))), step = null, file = null)
    }

    /**
     * Replaces everything with [r] (File > Open, crash recovery). [edited] = true keeps it marked
     * unsaved (a recovered autosave isn't on disk anywhere the user chose).
     */
    fun load(r: BudgetRequest, step: Step? = null, file: File? = null, edited: Boolean = false) {
        val maxId = (r.items.map { it.id } + r.funding.map { it.id } + r.docs.map { it.id }).maxOrNull() ?: 0L
        nextId = maxOf(nextId, maxId + 1)
        request = r
        this.step = step ?: Step.BASICS
        // A reopened draft shows its validation messages for the steps already worked through.
        visited = Step.entries.filter { it < this.step }.toSet()
        currentFile = file
        savedBaseline = if (edited) BudgetRequest() else r
        exportedBaseline = savedBaseline
    }

    init {
        request = request.copy(items = listOf(LineItem(id())))
        savedBaseline = request
        exportedBaseline = request
    }

    fun goTo(target: Step) {
        visited = visited + step
        step = target
    }

    fun showErrorsFor(s: Step) = s in visited || step == Step.REVIEW

    // ---- header
    fun updateHeader(f: (Header) -> Header) {
        val before = request.header
        var h = f(before)
        if (h.hasAdditionalFunding == true && before.hasAdditionalFunding != true && request.funding.isEmpty())
            request = request.copy(funding = listOf(FundingSource(id())))
        request = request.copy(header = h)
    }

    // ---- items
    fun addItem(prefill: PriceListEntry? = null) {
        val item = LineItem(
            id = id(),
            type = prefill?.type,
            name = prefill?.name.orEmpty(),
            location = prefill?.location.orEmpty(),
            unitPrice = prefill?.price?.toPlainString().orEmpty(),
            quantity = if (prefill != null) "1" else "",
        )
        request = request.copy(items = request.items + item)
    }

    fun updateItem(id: Long, f: (LineItem) -> LineItem) {
        request = request.copy(items = request.items.map { if (it.id == id) f(it) else it })
    }

    fun removeItem(id: Long) {
        request = request.copy(
            items = request.items.filterNot { it.id == id },
            docs = request.docs.map { it.copy(itemIds = it.itemIds - id) },
        )
    }

    // ---- funding
    fun addFunding() { request = request.copy(funding = request.funding + FundingSource(id())) }

    fun updateFunding(id: Long, f: (FundingSource) -> FundingSource) {
        request = request.copy(funding = request.funding.map { if (it.id == id) f(it) else it })
    }

    fun removeFunding(id: Long) { request = request.copy(funding = request.funding.filterNot { it.id == id }) }

    // ---- documents
    /** Returns an error message, or null on success. */
    fun addDocument(file: File): String? {
        val format = when (file.extension.lowercase()) {
            "png" -> ImageFormat.PNG
            "jpg", "jpeg" -> ImageFormat.JPEG
            "gif" -> ImageFormat.GIF
            else -> return "${file.name}: Excel can only embed PNG, JPG or GIF images. Screenshot or export it as an image first."
        }
        if (file.length() > 15L * 1024 * 1024) return "${file.name} is over 15 MB; please shrink it."
        val bytes = file.readBytes()
        if (ImageIO.read(ByteArrayInputStream(bytes)) == null) return "${file.name} could not be read as an image."
        request = request.copy(docs = request.docs + SupportingDoc(id(), file.name, bytes, format))
        return null
    }

    fun updateDoc(id: Long, f: (SupportingDoc) -> SupportingDoc) {
        request = request.copy(docs = request.docs.map { if (it.id == id) f(it) else it })
    }

    fun removeDoc(id: Long) { request = request.copy(docs = request.docs.filterNot { it.id == id }) }
}

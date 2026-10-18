package net.robog.budgetinator.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.robog.budgetinator.model.BudgetRequest
import net.robog.budgetinator.model.BudgetStore
import net.robog.budgetinator.persist.Autosave
import net.robog.budgetinator.persist.Draft
import net.robog.budgetinator.persist.DraftFile
import net.robog.budgetinator.validation.Step
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.SwingUtilities

/**
 * Document lifecycle for the window: File menu actions, the unsaved-changes prompt
 * (Save / Don't Save / Cancel) shared by New, Open and Quit, autosave and crash recovery.
 */
class DocumentController(
    private val store: BudgetStore,
    private val scope: CoroutineScope,
    private val exit: () -> Unit,
    private val autosave: Autosave = Autosave(),
) {
    /** Parent for file dialogs; set by [DocumentWindow]. */
    var window: Frame? = null

    class UnsavedPrompt(val action: String, val onProceed: () -> Unit)
    class RecoveryOffer(val draft: Draft, val savedAt: Long)

    var unsavedPrompt by mutableStateOf<UnsavedPrompt?>(null)
        private set
    var recovery by mutableStateOf<RecoveryOffer?>(null)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    private var saving = false

    init {
        val time = autosave.lastModified()
        autosave.read()?.let { recovery = RecoveryOffer(it, time) }
    }

    val documentName: String get() = store.currentFile?.name ?: "Untitled"

    val title: String get() =
        if (store.currentFile == null && !store.isEdited) "Budgetinator — SGC Budget Request"
        else "Budgetinator — $documentName" + if (store.isEdited) " — Edited" else ""

    // ---- menu actions

    fun newRequest() = guard("start a new request") { store.newRequest() }

    fun open() = guard("open another request") {
        val fd = FileDialog(window, "Open budget request", FileDialog.LOAD).apply {
            store.currentFile?.parent?.let { directory = it }
            setFilenameFilter { _, n -> n.endsWith(".${DraftFile.EXTENSION}", ignoreCase = true) }
            isVisible = true
        }
        val name = fd.file ?: return@guard
        load(File(fd.directory, name))
    }

    /** Finder double-click / drag onto the Dock icon. */
    fun openFile(f: File) {
        if (f.absoluteFile == store.currentFile?.absoluteFile) return
        guard("open “${f.name}”") { load(f) }
    }

    fun save(then: () -> Unit = {}) {
        val f = store.currentFile
        if (f == null) saveAs(then) else writeTo(f, then)
    }

    fun saveAs(then: () -> Unit = {}) {
        val fd = FileDialog(window, "Save budget request", FileDialog.SAVE).apply {
            val cur = store.currentFile
            if (cur != null) { directory = cur.parent; file = cur.name }
            else file = DraftFile.defaultFileName(store.request)
            isVisible = true
        }
        val name = fd.file ?: return
        writeTo(DraftFile.withExtension(File(fd.directory, name)), then)
    }

    fun exportToExcel() = store.goTo(Step.REVIEW)

    fun requestQuit() {
        if (store.isDirty) unsavedPrompt = UnsavedPrompt("quit", ::quitNow) else quitNow()
    }

    private fun quitNow() {
        autosave.shutdown()
        exit()
    }

    // ---- prompts

    fun promptSave() { val p = unsavedPrompt ?: return; unsavedPrompt = null; save(then = p.onProceed) }
    fun promptDontSave() { val p = unsavedPrompt ?: return; unsavedPrompt = null; p.onProceed() }
    fun promptCancel() { unsavedPrompt = null }

    fun restore() {
        val r = recovery ?: return
        recovery = null
        val orig = r.draft.originalFile?.takeIf { it.isFile }
        store.load(r.draft.request, r.draft.step, orig, edited = true)
    }

    fun discardRecovery() {
        recovery = null
        autosave.delete()
    }

    fun dismissError() { errorMessage = null }

    // ---- internals

    private fun guard(action: String, proceed: () -> Unit) {
        if (store.isDirty) unsavedPrompt = UnsavedPrompt(action, proceed) else proceed()
    }

    private fun load(f: File) {
        scope.launch {
            val draft = try {
                withContext(Dispatchers.IO) { DraftFile.read(f) }
            } catch (e: Exception) {
                errorMessage = "“${f.name}” couldn’t be opened.\n\n${e.message}"
                return@launch
            }
            recovery = null // opening a file supersedes an un-answered recovery offer
            store.load(draft.request, draft.step, f)
        }
    }

    private fun writeTo(f: File, then: () -> Unit) {
        if (saving) return
        saving = true
        val snapshot = store.request
        val draft = Draft(snapshot, store.step)
        scope.launch {
            try {
                withContext(Dispatchers.IO) { DraftFile.write(f, draft) }
                store.markSaved(f, snapshot)
                then()
            } catch (e: Exception) {
                errorMessage = "Couldn’t save “${f.name}”.\n\n${e.message}"
            } finally {
                saving = false
            }
        }
    }

    /**
     * Runs for the life of the window. A few seconds after any change, writes the request to the
     * autosave file if it differs from the last save, or deletes the autosave once it doesn't.
     * Paused while the launch-time recovery offer is unanswered so it can't clobber that file.
     */
    suspend fun runAutosave() {
        var lastWritten: BudgetRequest? = null
        snapshotFlow { Triple(store.request, store.isEdited, recovery == null) }
            .filter { it.third }
            .conflate()
            .collect {
                delay(AUTOSAVE_DELAY_MS)
                if (recovery != null) return@collect
                val r = store.request
                try {
                    if (!store.isEdited) {
                        if (lastWritten != null || autosave.exists()) withContext(Dispatchers.IO) { autosave.delete() }
                        lastWritten = null
                    } else if (r !== lastWritten) {
                        val draft = Draft(r, store.step, store.currentFile)
                        withContext(Dispatchers.IO) { autosave.write(draft) }
                        lastWritten = r
                    }
                } catch (e: Exception) {
                    System.err.println("Autosave failed: $e")
                }
            }
    }

    /** macOS: handle .budget files opened from Finder. Needs the document type in Info.plist. */
    fun installOpenFileHandler() {
        if (!Desktop.isDesktopSupported()) return
        val d = Desktop.getDesktop()
        if (!d.isSupported(Desktop.Action.APP_OPEN_FILE)) return
        d.setOpenFileHandler { e ->
            val f = e.files.firstOrNull { it.extension.equals(DraftFile.EXTENSION, true) } ?: return@setOpenFileHandler
            SwingUtilities.invokeLater { openFile(f) }
        }
    }

    companion object {
        const val AUTOSAVE_DELAY_MS = 3_000L
    }
}

@Composable
fun rememberDocumentController(store: BudgetStore, exit: () -> Unit): DocumentController {
    val scope = rememberCoroutineScope()
    val doc = remember { DocumentController(store, scope, exit) }
    LaunchedEffect(doc) { doc.installOpenFileHandler() }
    LaunchedEffect(doc) { doc.runAutosave() }
    return doc
}

/** Menu bar, macOS proxy-icon/edited-dot hints, and the document dialogs. Call inside the Window. */
@Composable
fun FrameWindowScope.DocumentWindow(doc: DocumentController, store: BudgetStore) {
    doc.window = window
    val edited = store.isEdited
    val file = store.currentFile
    LaunchedEffect(edited, file) {
        window.rootPane.putClientProperty("Window.documentModified", edited)
        window.rootPane.putClientProperty("Window.documentFile", file)
    }

    MenuBar {
        Menu("File", mnemonic = 'F') {
            Item("New", shortcut = KeyShortcut(Key.N, meta = true), onClick = doc::newRequest)
            Item("Open…", shortcut = KeyShortcut(Key.O, meta = true), onClick = doc::open)
            Separator()
            Item("Save", shortcut = KeyShortcut(Key.S, meta = true), onClick = { doc.save() })
            Item("Save As…", shortcut = KeyShortcut(Key.S, meta = true, shift = true), onClick = { doc.saveAs() })
            Separator()
            Item("Export to Excel…", shortcut = KeyShortcut(Key.E, meta = true), onClick = doc::exportToExcel)
        }
    }

    MaterialTheme {
        doc.unsavedPrompt?.let { p ->
            AlertDialog(
                onDismissRequest = doc::promptCancel,
                title = { Text("Save changes to “${doc.documentName}” before you ${p.action}?") },
                text = { Text("If you don’t save, the changes you’ve made since your last save will be lost.") },
                confirmButton = { Button(doc::promptSave) { Text(if (store.currentFile == null) "Save…" else "Save") } },
                dismissButton = {
                    Row {
                        OutlinedButton(doc::promptDontSave) { Text("Don’t Save") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(doc::promptCancel) { Text("Cancel") }
                    }
                },
            )
        }
        doc.recovery?.let { r ->
            val what = listOf(r.draft.request.header.organization, r.draft.request.header.eventName)
                .filter { it.isNotBlank() }.joinToString(" — ")
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Restore your unsaved request from ${formatTime(r.savedAt)}?") },
                text = {
                    Text(
                        (if (what.isNotBlank()) "“$what” " else "A budget request ") +
                            "wasn’t saved when Budgetinator last closed. Restore it to keep working, or discard it."
                    )
                },
                confirmButton = { Button(doc::restore) { Text("Restore") } },
                dismissButton = { OutlinedButton(doc::discardRecovery) { Text("Discard") } },
            )
        }
        doc.errorMessage?.let { msg ->
            AlertDialog(
                onDismissRequest = doc::dismissError,
                title = { Text("Something went wrong") },
                text = { Text(msg) },
                confirmButton = { Button(doc::dismissError) { Text("OK") } },
            )
        }
    }
}

private fun formatTime(millis: Long): String {
    val t = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val time = t.format(DateTimeFormatter.ofPattern("h:mm a"))
    return when (t.toLocalDate()) {
        today -> "today at $time"
        today.minusDays(1) -> "yesterday at $time"
        else -> t.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")) + " at $time"
    }
}

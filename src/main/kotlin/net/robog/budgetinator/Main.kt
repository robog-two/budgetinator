package net.robog.budgetinator

import androidx.compose.runtime.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import net.robog.budgetinator.model.BudgetStore
import net.robog.budgetinator.ui.AppExtras
import net.robog.budgetinator.ui.BudgetinatorApp
import net.robog.budgetinator.ui.DocumentWindow
import net.robog.budgetinator.ui.rememberDocumentController
import java.awt.Desktop

fun main() {
    // Put the File menu in the macOS screen menu bar rather than inside the window.
    System.setProperty("apple.laf.useScreenMenuBar", "true")
    application {
        val store = remember { BudgetStore() }
        // Save/open, autosave + crash recovery, and the Save / Don't Save / Cancel prompt: ui/Document.kt
        val doc = rememberDocumentController(store, ::exitApplication)

        // Cmd+Q / Dock "Quit" bypass the window's close request on macOS, so intercept it too.
        LaunchedEffect(Unit) {
            val d = Desktop.getDesktop()
            if (d.isSupported(Desktop.Action.APP_QUIT_HANDLER)) d.setQuitHandler { _, response ->
                response.cancelQuit()
                doc.requestQuit()
            }
        }

        Window(
            onCloseRequest = doc::requestQuit,
            icon = painterResource("app-icon.png"),
            title = doc.title,
            state = rememberWindowState(size = DpSize(1240.dp, 840.dp)),
        ) {
            BudgetinatorApp(window, store)
            DocumentWindow(doc, store)
            // About window + update checks. Quitting for an update skips the autosave cleanup on purpose,
            // so anything unsaved is offered for restore after the relaunch.
            AppExtras(store, quit = ::exitApplication)
        }
    }
}

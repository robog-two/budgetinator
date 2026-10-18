package net.robog.budgetinator

import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import net.robog.budgetinator.model.BudgetStore
import net.robog.budgetinator.ui.AppExtras
import net.robog.budgetinator.ui.BudgetinatorApp
import net.robog.budgetinator.ui.DocumentWindow
import net.robog.budgetinator.ui.rememberDocumentController
import java.awt.Desktop
import java.awt.Dimension
import java.awt.GraphicsEnvironment

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    AppLog.installCrashHandler()
    // Put the File menu in the macOS screen menu bar rather than inside the window.
    System.setProperty("apple.laf.useScreenMenuBar", "true")
    application {
        val store = remember { BudgetStore() }
        // Save/open, autosave + crash recovery, and the Save / Don't Save / Cancel prompt: ui/Document.kt
        val doc = rememberDocumentController(store, ::exitApplication)

        // Cmd+Q / Dock "Quit" bypass the window's close request on macOS, so intercept it too.
        LaunchedEffect(Unit) {
            if (!Desktop.isDesktopSupported()) return@LaunchedEffect
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
            state = rememberWindowState(size = initialWindowSize(), position = WindowPosition.Aligned(Alignment.Center)),
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(MIN_WIDTH, MIN_HEIGHT)
                // Exceptions while composing, drawing or handling input: log them and explain before closing.
                // The autosave is left in place, so the request is offered for restore on next launch.
                window.exceptionHandler = WindowExceptionHandler { t ->
                    AppLog.error("Uncaught exception in the UI", t)
                    AppLog.showErrorDialog(fatal = true)
                    exitApplication()
                }
            }
            BudgetinatorApp(window, store)
            DocumentWindow(doc, store)
            // About window + update checks. Quitting for an update skips the autosave cleanup on purpose,
            // so anything unsaved is offered for restore after the relaunch.
            AppExtras(store, quit = ::exitApplication)
        }
    }
}

private const val MIN_WIDTH = 900
private const val MIN_HEIGHT = 620

/** 1240×840 when it fits, otherwise 90% of the screen's usable area (minus menu bar and Dock), never below the minimum. */
private fun initialWindowSize(): DpSize {
    val usable = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }.getOrNull()
        ?: return DpSize(1240.dp, 840.dp)
    // AWT reports macOS points, which is what Compose's dp maps to on a Mac window.
    val w = minOf(1240, (usable.width * 0.9).toInt()).coerceAtLeast(MIN_WIDTH)
    val h = minOf(840, (usable.height * 0.9).toInt()).coerceAtLeast(MIN_HEIGHT)
    return DpSize(w.dp, h.dp)
}

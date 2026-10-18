package net.robog.budgetinator

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import net.robog.budgetinator.ui.BudgetinatorApp
import java.awt.Desktop
import java.awt.Dimension
import java.awt.GraphicsEnvironment

@OptIn(ExperimentalComposeUiApi::class)
fun main() = application {
    remember { AppLog.installCrashHandler() }
    val store = remember { BudgetStore() }
    var confirmQuit by remember { mutableStateOf(false) }

    fun requestQuit() { if (store.isDirty) confirmQuit = true else exitApplication() }

    // Cmd+Q / Dock "Quit" bypass the window's close request on macOS, so intercept it too.
    LaunchedEffect(Unit) {
        if (!Desktop.isDesktopSupported()) return@LaunchedEffect
        val d = Desktop.getDesktop()
        if (d.isSupported(Desktop.Action.APP_QUIT_HANDLER)) d.setQuitHandler { _, response ->
            if (store.isDirty) { response.cancelQuit(); confirmQuit = true }
            else response.performQuit()
        }
    }

    Window(
        onCloseRequest = ::requestQuit,
        icon = painterResource("app-icon.png"),
        title = "Budgetinator — SGC Budget Request",
        state = rememberWindowState(size = initialWindowSize(), position = WindowPosition.Aligned(Alignment.Center)),
    ) {
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(MIN_WIDTH, MIN_HEIGHT)
            // Exceptions while composing, drawing or handling input: log them and explain before closing.
            window.exceptionHandler = WindowExceptionHandler { t ->
                AppLog.error("Uncaught exception in the UI", t)
                AppLog.showErrorDialog(fatal = true)
                exitApplication()
            }
        }
        BudgetinatorApp(window, store)

        if (confirmQuit) MaterialTheme {
            AlertDialog(
                onDismissRequest = { confirmQuit = false },
                title = { Text("Are you sure?") },
                text = {
                    Text("Your budget request isn\u2019t saved anywhere until you export it. " +
                        "If you quit now, everything you\u2019ve entered will be lost.")
                },
                confirmButton = {
                    Button({ confirmQuit = false; exitApplication() }) { Text("Quit without exporting") }
                },
                dismissButton = {
                    OutlinedButton({ confirmQuit = false }) { Text("Keep working") }
                },
            )
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

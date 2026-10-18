package net.robog.budgetinator

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import java.awt.Desktop

fun main() = application {
    val store = remember { BudgetStore() }
    var confirmQuit by remember { mutableStateOf(false) }

    fun requestQuit() { if (store.isDirty) confirmQuit = true else exitApplication() }

    // Cmd+Q / Dock "Quit" bypass the window's close request on macOS, so intercept it too.
    LaunchedEffect(Unit) {
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
        state = rememberWindowState(size = DpSize(1240.dp, 840.dp)),
    ) {
        BudgetinatorApp(window, store)
        AppExtras(store, quit = ::exitApplication) // About window, update checks

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

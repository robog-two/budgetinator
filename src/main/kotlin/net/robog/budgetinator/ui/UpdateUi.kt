package net.robog.budgetinator.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.robog.budgetinator.AppInfo
import net.robog.budgetinator.model.BudgetStore
import net.robog.budgetinator.update.UpdateController
import net.robog.budgetinator.update.UpdateLogic
import net.robog.budgetinator.update.UpdateState
import java.awt.Desktop

/**
 * Everything About/update related that lives inside the main window: the macOS "About" menu hook,
 * the launch-time update check, the floating update card, the unsaved-work warning and the About window.
 * Call once from inside the main `Window { }`.
 *
 * @param quit exits immediately (no "are you sure" prompt — the updater has already asked).
 */
@Composable
fun AppExtras(store: BudgetStore, quit: () -> Unit) {
    val updater = remember { UpdateController(isDirty = { store.isDirty }, quit = quit) }

    DisposableEffect(Unit) {
        runCatching {
            if (Desktop.isDesktopSupported()) {
                val d = Desktop.getDesktop()
                if (d.isSupported(Desktop.Action.APP_ABOUT)) d.setAboutHandler { AboutState.open = true }
            }
        }
        updater.checkAutomatically()
        onDispose { updater.dispose() }
    }

    MaterialTheme(colorScheme = Scheme) {
        if (updater.showBanner) UpdateCard(updater)

        if (updater.confirmUnsaved) AlertDialog(
            onDismissRequest = updater::cancelUnsaved,
            title = { Text("Unsaved changes") },
            text = { Text("Save or export your request first — unsaved changes will be lost when Budgetinator restarts to update.") },
            confirmButton = { Button(updater::confirmUnsavedAndUpdate) { Text("Update Anyway") } },
            dismissButton = { OutlinedButton(updater::cancelUnsaved) { Text("Cancel") } },
        )
    }

    if (AboutState.open) AboutDialog(updater) { AboutState.open = false }
}

@Composable
private fun UpdateCard(updater: UpdateController) {
    Box(Modifier.fillMaxSize().padding(end = 24.dp, bottom = 84.dp), contentAlignment = Alignment.BottomEnd) {
        ElevatedCard(Modifier.width(380.dp), elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (val s = updater.state) {
                    is UpdateState.Available -> {
                        Text("${AppInfo.NAME} ${s.release.version} is available", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        if (!AppInfo.isDevBuild)
                            Text("You have ${AppInfo.version}.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val notes = remember(s.release) { UpdateLogic.notesSnippet(s.release.body) }
                        if (notes.isNotBlank()) Box(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                            Text(notes, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(updater::updateAndRestart) { Text("Update and Restart") }
                            TextButton(updater::later) { Text("Later") }
                        }
                        TextButton(updater::skipThisVersion, contentPadding = PaddingValues(0.dp)) {
                            Text("Skip this version", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    is UpdateState.Downloading -> {
                        Text("Downloading ${AppInfo.NAME} ${s.release.version}…", style = MaterialTheme.typography.titleSmall)
                        if (s.progress in 0f..1f) LinearProgressIndicator({ s.progress }, Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    is UpdateState.Verifying -> {
                        Text("Verifying the update…", style = MaterialTheme.typography.titleSmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    UpdateState.Restarting -> Text("Restarting…", style = MaterialTheme.typography.titleSmall)
                    is UpdateState.InstallFailed -> {
                        Text("Update not installed", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(updater::updateAndRestart) { Text("Try Again") }
                            OutlinedButton({ updater.openReleasePage(s.release) }) { Text("Download Manually") }
                            TextButton(updater::dismissMessage) { Text("Close") }
                        }
                    }
                    is UpdateState.OpenedInBrowser -> {
                        Text("Download ${AppInfo.NAME} ${s.release.version} from your browser",
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("${s.reason} The release page has been opened so you can install it manually.",
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(updater::dismissMessage) { Text("OK") }
                    }
                    else -> {}
                }
            }
        }
    }
}

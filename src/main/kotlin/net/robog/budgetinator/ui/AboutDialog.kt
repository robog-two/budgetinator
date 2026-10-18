package net.robog.budgetinator.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import net.robog.budgetinator.AppInfo
import net.robog.budgetinator.LogMailer
import net.robog.budgetinator.update.UpdateController
import net.robog.budgetinator.update.UpdateState

/** App-wide toggle for the About window (opened from the macOS app menu or the sidebar). */
object AboutState {
    var open by mutableStateOf(false)
}

const val NOT_ENDORSED = "This app is not endorsed by SGC, please check the final spreadsheet before submitting."

@Composable
fun AboutDialog(updater: UpdateController, onClose: () -> Unit) {
    DialogWindow(
        onCloseRequest = onClose,
        state = rememberDialogState(size = DpSize(440.dp, 700.dp)),
        title = "About ${AppInfo.NAME}",
        icon = painterResource("app-icon.png"),
        resizable = false,
    ) {
        MaterialTheme(colorScheme = Scheme) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Image(painterResource("app-icon.png"), null, Modifier.size(96.dp))
                    Text(AppInfo.NAME, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        if (AppInfo.isDevBuild) "Development build" else "Version ${AppInfo.version}",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Callout(NOT_ENDORSED, color = Warn)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Made with ", style = MaterialTheme.typography.bodyMedium)
                        Icon(Icons.Filled.Favorite, "love", tint = Color(0xFFE0245E), modifier = Modifier.size(15.dp))
                        Text(" by an IC student.", style = MaterialTheme.typography.bodyMedium)
                    }

                    Text("Questions or problems? ${LogMailer.TO}", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({ openUrl("mailto:${LogMailer.TO}?subject=Budgetinator") }) { Text("Email Sam") }
                        TextButton({ LogMailer.send() }) { Text("Send Logs…") }
                    }

                    UpdateSection(updater, onClose)

                    TextButton({ openUrl(AppInfo.repoUrl) }) {
                        Text("github.com/${AppInfo.REPO_OWNER}/${AppInfo.REPO_NAME}")
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(14.dp))
                    }

                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text("Licenses", style = MaterialTheme.typography.titleSmall)
                    val small = MaterialTheme.typography.bodySmall
                    val muted = MaterialTheme.colorScheme.onSurfaceVariant
                    Text(AppInfo.CREDIT, style = small, color = muted, textAlign = TextAlign.Center)
                    Text("Carlito font © Łukasz Dziedzic, licensed under the SIL Open Font License 1.1.",
                        style = small, color = muted, textAlign = TextAlign.Center)
                    Text("Built with Kotlin and Compose Multiplatform (Apache License 2.0).",
                        style = small, color = muted, textAlign = TextAlign.Center)
                    Row {
                        TextButton({ openUrl(AppInfo.licenseUrl) }) { Text("CC0 1.0", style = MaterialTheme.typography.labelMedium) }
                        TextButton({ openUrl(AppInfo.carlitoLicenseUrl) }) { Text("SIL OFL 1.1", style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateSection(updater: UpdateController, closeAbout: () -> Unit) {
    val s = updater.state
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (s) {
            is UpdateState.Available -> {
                Text("${AppInfo.NAME} ${s.release.version} is available.", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ closeAbout(); updater.updateAndRestart() }) { Text("Update and Restart") }
                    OutlinedButton({ updater.openReleasePage(s.release) }) { Text("Release notes") }
                }
            }
            else -> OutlinedButton({ updater.checkManually() }, enabled = !updater.busy) {
                if (s == UpdateState.Checking) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Checking…")
                } else Text("Check for Updates…")
            }
        }
        val (msg, isError) = when (s) {
            is UpdateState.UpToDate -> s.message to false
            is UpdateState.CheckFailed -> s.message to true
            is UpdateState.InstallFailed -> s.message to true
            is UpdateState.OpenedInBrowser -> s.reason to false
            is UpdateState.Downloading, is UpdateState.Verifying -> "Installing update…" to false
            else -> null to false
        }
        if (msg != null) Text(
            msg, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun openUrl(url: String) = net.robog.budgetinator.update.UpdateInstaller.openInBrowser(url)

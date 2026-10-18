package net.robog.budgetinator.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import net.robog.budgetinator.AppInfo
import java.nio.file.Path

/** What the update UI should show. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    /** Result of a manual check that found nothing newer. */
    data class UpToDate(val message: String) : UpdateState
    /** A manual check failed (automatic checks fail silently). */
    data class CheckFailed(val message: String) : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    data class Verifying(val release: Release) : UpdateState
    data class InstallFailed(val release: Release, val message: String) : UpdateState
    /** We couldn't self-install, so the release page was opened in the browser instead. */
    data class OpenedInBrowser(val release: Release, val reason: String) : UpdateState
    data object Restarting : UpdateState
}

/**
 * Drives update checks and installs; Compose observes [state]. Network / process work runs on
 * Dispatchers.IO, state changes happen on Dispatchers.Main (Swing EDT via kotlinx-coroutines-swing).
 *
 * @param isDirty whether the user has unsaved work (asked right before restarting).
 * @param quit exits the app so the install helper can swap the bundle (must not prompt again).
 */
class UpdateController(
    private val isDirty: () -> Boolean,
    private val quit: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    var state: UpdateState by mutableStateOf(UpdateState.Idle)
        private set

    /** True while asking "you have unsaved changes — update anyway?". */
    var confirmUnsaved: Boolean by mutableStateOf(false)
        private set

    /** Whether the floating update card should be visible. */
    val showBanner: Boolean
        get() = when (state) {
            is UpdateState.Available, is UpdateState.Downloading, is UpdateState.Verifying,
            is UpdateState.InstallFailed, is UpdateState.OpenedInBrowser, UpdateState.Restarting -> true
            else -> false
        }

    val busy: Boolean
        get() = state is UpdateState.Checking || state is UpdateState.Downloading ||
            state is UpdateState.Verifying || state == UpdateState.Restarting

    /** Background check on launch: skipped in dev builds, at most once per 24h, silent on failure. */
    fun checkAutomatically() {
        if (AppInfo.isDevBuild || !UpdateChecker.isAutoCheckDue() || busy) return
        UpdatePrefs.lastCheckMillis = System.currentTimeMillis()
        job = scope.launch {
            val latest = runCatching { withContext(Dispatchers.IO) { UpdateChecker.fetchLatest() } }.getOrNull()
            val newer = UpdateLogic.newerRelease(latest, AppInfo.version, UpdatePrefs.skippedVersion)
            if (newer != null && state == UpdateState.Idle) state = UpdateState.Available(newer)
        }
    }

    /** "Check for Updates…": always reports a result; ignores a previously skipped version. */
    fun checkManually() {
        if (busy) return
        state = UpdateState.Checking
        job = scope.launch {
            state = try {
                val latest = withContext(Dispatchers.IO) { UpdateChecker.fetchLatest() }
                val newer = UpdateLogic.newerRelease(latest, AppInfo.version)
                when {
                    AppInfo.isDevBuild -> UpdateState.UpToDate(
                        "This is a development build. The latest release is ${latest.version}.")
                    newer != null -> UpdateState.Available(newer)
                    else -> UpdateState.UpToDate("You’re up to date — ${AppInfo.version} is the latest version.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.CheckFailed(friendly(e, "Couldn’t check for updates"))
            }
        }
    }

    fun later() { if (!busy) state = UpdateState.Idle }

    fun skipThisVersion() {
        val s = state as? UpdateState.Available ?: return
        UpdatePrefs.skippedVersion = s.release.version.toString()
        state = UpdateState.Idle
    }

    fun dismissMessage() {
        if (state is UpdateState.UpToDate || state is UpdateState.CheckFailed ||
            state is UpdateState.InstallFailed || state is UpdateState.OpenedInBrowser) state = UpdateState.Idle
    }

    /** "Update and Restart". Warns first if there's unsaved work. */
    fun updateAndRestart() {
        if (busy) return
        if (isDirty()) confirmUnsaved = true else startInstall()
    }

    fun confirmUnsavedAndUpdate() { confirmUnsaved = false; startInstall() }
    fun cancelUnsaved() { confirmUnsaved = false }

    private fun startInstall() {
        val release = when (val s = state) {
            is UpdateState.Available -> s.release
            is UpdateState.InstallFailed -> s.release
            else -> return
        }
        // If there are unsaved changes right now, the user has just agreed to lose them.
        val lossAccepted = isDirty()
        state = UpdateState.Downloading(release, -1f)
        job = scope.launch {
            var work: Path? = null
            try {
                val running = withContext(Dispatchers.IO) { RunningApp.detect() }
                val asset = UpdateLogic.archSuffix()?.let { UpdateLogic.selectAsset(release, it) }
                val reason = running.cannotSelfInstallReason
                    ?: if (asset == null) "This release has no automatic-update package for your Mac." else null
                if (reason != null) {
                    UpdateInstaller.openInBrowser(release.htmlUrl.ifBlank { AppInfo.releasesUrl })
                    state = UpdateState.OpenedInBrowser(release, reason)
                    return@launch
                }
                val bundle = running.bundle!!
                val team = running.teamId!!
                val dir = withContext(Dispatchers.IO) { UpdateInstaller.newWorkDir() }.also { work = it }
                val zip = withContext(Dispatchers.IO) {
                    UpdateInstaller.download(asset!!, dir) { p ->
                        scope.launch { if (state is UpdateState.Downloading) state = UpdateState.Downloading(release, p) }
                    }
                }
                state = UpdateState.Verifying(release)
                val newApp = withContext(Dispatchers.IO) {
                    UpdateInstaller.extract(zip, dir).also { UpdateInstaller.verify(it, team) }
                }
                // Last chance: the user may have typed something while we downloaded.
                if (isDirty() && !lossAccepted) {
                    state = UpdateState.InstallFailed(release,
                        "You made changes while the update downloaded. Save or export them, then try again.")
                    UpdateInstaller.deleteQuietly(dir)
                    return@launch
                }
                withContext(Dispatchers.IO) { UpdateInstaller.launchSwapHelper(newApp, bundle, dir) }
                work = null // the helper owns the directory now
                state = UpdateState.Restarting
                delay(300)
                quit()
                // Belt and braces: make sure the JVM really exits so the helper can proceed.
                Thread({ Thread.sleep(5000); System.exit(0) }, "update-exit").apply { isDaemon = true; start() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = UpdateState.InstallFailed(release, friendly(e, "The update couldn’t be installed"))
            } finally {
                work?.let { UpdateInstaller.deleteQuietly(it) }
            }
        }
    }

    fun openReleasePage(release: Release?) =
        UpdateInstaller.openInBrowser(release?.htmlUrl?.ifBlank { null } ?: AppInfo.releasesUrl)

    fun dispose() = scope.cancel()

    private fun friendly(e: Exception, prefix: String): String {
        val msg = e.message?.takeIf { it.isNotBlank() }
        return when (e) {
            is java.net.http.HttpTimeoutException -> "$prefix: the connection timed out."
            is java.net.ConnectException, is java.net.UnknownHostException, is java.nio.channels.UnresolvedAddressException ->
                "$prefix: you appear to be offline."
            else -> if (msg != null) "$prefix: $msg" else "$prefix."
        }
    }
}

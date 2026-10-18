package net.robog.budgetinator.update

import net.robog.budgetinator.AppInfo
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** Result of running an external tool. */
internal data class ProcResult(val exitCode: Int, val output: String) {
    val ok get() = exitCode == 0
}

internal fun runTool(vararg cmd: String, timeoutSec: Long = 120): ProcResult {
    val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    val out = StringBuilder()
    val reader = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } } }
        .apply { isDaemon = true; start() }
    if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
        p.destroyForcibly()
        return ProcResult(-1, "Timed out: ${cmd.joinToString(" ")}")
    }
    reader.join(2000)
    return ProcResult(p.exitValue(), synchronized(out) { out.toString() })
}

/** Where (and how) the current process is running — decides whether self-update is possible. */
data class RunningApp(val bundle: File?, val teamId: String?) {
    /** Reason self-install isn't possible, or null if it is. */
    val cannotSelfInstallReason: String?
        get() = when {
            !System.getProperty("os.name").orEmpty().lowercase().contains("mac") -> "Automatic install is only supported on macOS."
            bundle == null -> "This copy isn’t running from an installed Budgetinator.app."
            "/AppTranslocation/" in bundle.path ->
                "macOS is running Budgetinator from a temporary location. Move it to your Applications folder first."
            bundle.path.startsWith("/Volumes/") -> "Budgetinator is running from a disk image. Copy it to Applications first."
            teamId == null -> "This copy isn’t signed with a Developer ID, so updates can’t be verified."
            !bundle.parentFile.canWrite() || !bundle.canWrite() ->
                "You don’t have permission to replace ${bundle.path}."
            else -> null
        }

    companion object {
        fun detect(): RunningApp {
            val cmd = ProcessHandle.current().info().command().orElse(null)
            val bundlePath = UpdateLogic.bundlePathFrom(cmd)
                ?: UpdateLogic.bundlePathFrom(System.getProperty("java.home")?.let { "$it/" })
            val bundle = bundlePath?.let { File(it) }?.takeIf { it.isDirectory }
            val team = bundle?.let { b -> runCatching { teamIdOf(b) }.getOrNull() }
            return RunningApp(bundle, team)
        }

        fun teamIdOf(app: File): String? =
            UpdateLogic.parseTeamId(runTool("/usr/bin/codesign", "-dv", "--verbose=2", app.path, timeoutSec = 30).output)
    }
}

/** Downloads, verifies and installs an update. All functions block — call from Dispatchers.IO. */
object UpdateInstaller {

    /** Streams [asset] into [dir], reporting progress in 0..1 (or -1 if the size is unknown). */
    fun download(asset: ReleaseAsset, dir: Path, onProgress: (Float) -> Unit): Path {
        val req = HttpRequest.newBuilder(URI.create(asset.downloadUrl))
            .timeout(Duration.ofMinutes(10))
            .header("User-Agent", UpdateChecker.userAgent)
            .header("Accept", "application/octet-stream")
            .GET().build()
        val resp = try {
            UpdateChecker.http.send(req, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); throw IOException("Download interrupted", e)
        }
        if (resp.statusCode() != 200) {
            resp.body().close(); throw IOException("Download failed (HTTP ${resp.statusCode()}).")
        }
        val total = resp.headers().firstValueAsLong("Content-Length").orElse(asset.size).takeIf { it > 0 } ?: -1L
        val target = dir.resolve(File(asset.name).name) // never trust path components in the asset name
        resp.body().use { input ->
            Files.newOutputStream(target).use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var lastReported = -1f
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("Download cancelled")
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val p = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f
                    if (p < 0 || p - lastReported >= 0.01f) { onProgress(p); lastReported = p }
                }
                if (asset.size > 0 && done != asset.size)
                    throw IOException("Download was incomplete ($done of ${asset.size} bytes).")
            }
        }
        onProgress(1f)
        return target
    }

    /** Extracts a ditto/zip archive and returns the single `.app` inside it. */
    fun extract(zip: Path, dir: Path): File {
        val out = Files.createDirectories(dir.resolve("extracted"))
        val r = runTool("/usr/bin/ditto", "-x", "-k", zip.absolutePathString(), out.absolutePathString(), timeoutSec = 300)
        if (!r.ok) throw IOException("Couldn’t unpack the update: ${r.output.trim()}")
        val apps = out.listDirectoryEntries().filter { it.isDirectory() && it.name.endsWith(".app") }
        if (apps.size != 1) throw IOException("The update archive didn’t contain exactly one app.")
        return apps.single().toFile()
    }

    /**
     * Checks the downloaded app before we let it anywhere near the installed one: valid deep signature,
     * accepted by Gatekeeper (i.e. notarized), same Developer ID team as the running app, same bundle id.
     */
    fun verify(newApp: File, expectedTeamId: String) {
        val sig = runTool("/usr/bin/codesign", "--verify", "--deep", "--strict", newApp.path, timeoutSec = 300)
        if (!sig.ok) throw IOException("The update’s code signature is invalid.\n${sig.output.trim()}")

        val gk = runTool("/usr/sbin/spctl", "--assess", "--type", "execute", newApp.path, timeoutSec = 120)
        if (!gk.ok) throw IOException("macOS Gatekeeper rejected the update.\n${gk.output.trim()}")

        val team = RunningApp.teamIdOf(newApp)
        if (team != expectedTeamId)
            throw IOException("The update is signed by a different developer (${team ?: "unsigned"}), so it was not installed.")

        val plist = File(newApp, "Contents/Info.plist")
        val id = runTool("/usr/libexec/PlistBuddy", "-c", "Print :CFBundleIdentifier", plist.path, timeoutSec = 15)
        if (!id.ok || id.output.trim() != AppInfo.BUNDLE_ID)
            throw IOException("The update has an unexpected bundle identifier (${id.output.trim()}).")
    }

    /**
     * Writes and starts a detached helper that waits for this process to exit, swaps [newApp] into
     * [target]'s place (same folder, so it's an atomic rename on one volume), clears quarantine and relaunches.
     * If the copy fails, the old app is put back. The caller must quit the app right after this returns.
     */
    fun launchSwapHelper(newApp: File, target: File, workDir: Path) {
        val script = workDir.resolve("install-update.sh")
        Files.writeString(script, SWAP_SCRIPT)
        script.toFile().setExecutable(true, true)
        val log = workDir.resolve("install-update.log").toFile()
        ProcessBuilder(
            "/usr/bin/nohup", "/bin/bash", script.absolutePathString(),
            ProcessHandle.current().pid().toString(), newApp.absolutePath, target.absolutePath,
        )
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .start()
    }

    fun newWorkDir(): Path = Files.createTempDirectory("budgetinator-update-")

    fun deleteQuietly(dir: Path) {
        runCatching { dir.toFile().deleteRecursively() }
    }

    /** Opens a URL in the default browser, with `open` as a fallback when java.awt.Desktop can't. */
    fun openInBrowser(url: String) {
        val viaDesktop = runCatching {
            Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) &&
                Desktop.getDesktop().browse(URI(url)).let { true }
        }.getOrDefault(false)
        if (!viaDesktop) runCatching { ProcessBuilder("/usr/bin/open", url).start() }
    }

    private val SWAP_SCRIPT = """
        |#!/bin/bash
        |# Budgetinator self-update helper. Args: <pid> <new .app> <installed .app>
        |set -u
        |PID="${'$'}1"; NEW="${'$'}2"; TARGET="${'$'}3"
        |DIR="${'$'}(dirname "${'$'}TARGET")"; BASE="${'$'}(basename "${'$'}TARGET")"
        |STAGE="${'$'}DIR/.${'$'}BASE.update-${'$'}${'$'}"
        |OLD="${'$'}DIR/.${'$'}BASE.old-${'$'}${'$'}"
        |echo "${'$'}(date): waiting for pid ${'$'}PID"
        |for i in ${'$'}(seq 1 240); do kill -0 "${'$'}PID" 2>/dev/null || break; sleep 0.25; done
        |if kill -0 "${'$'}PID" 2>/dev/null; then echo "app did not quit; giving up"; exit 1; fi
        |rm -rf "${'$'}STAGE"
        |if ! /usr/bin/ditto "${'$'}NEW" "${'$'}STAGE"; then
        |  echo "copy failed"; rm -rf "${'$'}STAGE"; /usr/bin/open "${'$'}TARGET"; exit 1
        |fi
        |/usr/bin/xattr -dr com.apple.quarantine "${'$'}STAGE" 2>/dev/null || true
        |if ! mv "${'$'}TARGET" "${'$'}OLD"; then
        |  echo "could not move old app"; rm -rf "${'$'}STAGE"; /usr/bin/open "${'$'}TARGET"; exit 1
        |fi
        |if ! mv "${'$'}STAGE" "${'$'}TARGET"; then
        |  echo "could not move new app into place; restoring"; mv "${'$'}OLD" "${'$'}TARGET"; rm -rf "${'$'}STAGE"
        |  /usr/bin/open "${'$'}TARGET"; exit 1
        |fi
        |rm -rf "${'$'}OLD"
        |# Make Launch Services notice the new version before relaunching.
        |/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f "${'$'}TARGET" 2>/dev/null || true
        |echo "${'$'}(date): installed; relaunching"
        |/usr/bin/open "${'$'}TARGET"
        |# Clean up the unpacked copy and the download (the log stays for troubleshooting).
        |WORK="${'$'}(dirname "${'$'}(dirname "${'$'}NEW")")"
        |case "${'$'}WORK" in */budgetinator-update-*) rm -rf "${'$'}WORK/extracted" "${'$'}WORK"/*.zip ;; esac
    """.trimMargin() + "\n"
}

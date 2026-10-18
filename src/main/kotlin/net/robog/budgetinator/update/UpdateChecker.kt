package net.robog.budgetinator.update

import net.robog.budgetinator.AppInfo
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.prefs.Preferences

/** Persisted updater settings (java.util.prefs → ~/Library/Preferences on macOS). */
object UpdatePrefs {
    private const val LAST_CHECK = "lastCheckEpochMillis"
    private const val SKIPPED = "skippedVersion"
    private val node: Preferences? = runCatching { Preferences.userRoot().node("net/robog/budgetinator/update") }.getOrNull()

    var lastCheckMillis: Long
        get() = node?.getLong(LAST_CHECK, 0L) ?: 0L
        set(v) { runCatching { node?.putLong(LAST_CHECK, v); node?.flush() } }

    var skippedVersion: String?
        get() = node?.get(SKIPPED, null)
        set(v) { runCatching { if (v == null) node?.remove(SKIPPED) else node?.put(SKIPPED, v); node?.flush() } }
}

/** Talks to the GitHub releases API. Blocking — call from Dispatchers.IO. */
object UpdateChecker {
    val AUTO_CHECK_INTERVAL: Duration = Duration.ofHours(24)

    internal val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    val userAgent: String get() = "${AppInfo.NAME}/${AppInfo.version} (${System.getProperty("os.name")}; ${System.getProperty("os.arch")})"

    fun isAutoCheckDue(now: Long = System.currentTimeMillis()): Boolean {
        val last = UpdatePrefs.lastCheckMillis
        return last > now || now - last >= AUTO_CHECK_INTERVAL.toMillis()
    }

    /** Fetches the latest published (non-draft, non-prerelease — GitHub's /latest already excludes those) release. */
    @Throws(IOException::class)
    fun fetchLatest(): Release {
        val req = HttpRequest.newBuilder(URI.create(AppInfo.latestReleaseApiUrl))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", userAgent)
            .GET().build()
        val resp = try {
            http.send(req, HttpResponse.BodyHandlers.ofString())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); throw IOException("Interrupted", e)
        }
        when (resp.statusCode()) {
            200 -> {}
            404 -> throw IOException("No releases have been published yet.")
            403, 429 -> throw IOException("GitHub is rate-limiting update checks right now. Try again later.")
            else -> throw IOException("GitHub returned HTTP ${resp.statusCode()}.")
        }
        UpdatePrefs.lastCheckMillis = System.currentTimeMillis()
        return UpdateLogic.parseRelease(resp.body()) ?: throw IOException("Couldn’t read the release information from GitHub.")
    }
}

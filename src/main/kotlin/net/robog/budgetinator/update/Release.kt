package net.robog.budgetinator.update

/** One downloadable file attached to a GitHub release. */
data class ReleaseAsset(val name: String, val downloadUrl: String, val size: Long)

/** The subset of a GitHub release we care about. */
data class Release(
    val tag: String,
    val version: Semver,
    val name: String,
    val htmlUrl: String,
    val body: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<ReleaseAsset>,
)

/**
 * Pure helpers for the update flow — no I/O, so they're unit-tested.
 *
 * Release contract (produced by the GitHub Actions release workflow): tags `v<semver>`, assets
 * `Budgetinator-<version>-<arch>.zip` (a `ditto -c -k --keepParent` zip of the signed, notarized,
 * stapled app) and `Budgetinator-<version>-<arch>.dmg`, with `<arch>` = `arm64` or `x64`.
 */
object UpdateLogic {

    /** Parses one release object from `GET /repos/{owner}/{repo}/releases/latest`. Null if it isn't usable. */
    fun parseRelease(json: String): Release? {
        val root = runCatching { MiniJson.parse(json) }.getOrNull() as? Map<*, *> ?: return null
        return releaseFrom(root)
    }

    /** Parses the `GET /releases` list form (used as a fallback / for tests). */
    fun parseReleaseList(json: String): List<Release> {
        val root = runCatching { MiniJson.parse(json) }.getOrNull() as? List<*> ?: return emptyList()
        return root.mapNotNull { (it as? Map<*, *>)?.let(::releaseFrom) }
    }

    private fun releaseFrom(o: Map<*, *>): Release? {
        val tag = o["tag_name"] as? String ?: return null
        val version = Semver.parse(tag) ?: return null
        val assets = (o["assets"] as? List<*>).orEmpty().mapNotNull { a ->
            val m = a as? Map<*, *> ?: return@mapNotNull null
            val name = m["name"] as? String ?: return@mapNotNull null
            val url = m["browser_download_url"] as? String ?: return@mapNotNull null
            ReleaseAsset(name, url, (m["size"] as? Number)?.toLong() ?: -1L)
        }
        return Release(
            tag = tag,
            version = version,
            name = (o["name"] as? String)?.takeIf { it.isNotBlank() } ?: tag,
            htmlUrl = o["html_url"] as? String ?: "",
            body = o["body"] as? String ?: "",
            draft = o["draft"] as? Boolean ?: false,
            prerelease = o["prerelease"] as? Boolean ?: false,
            assets = assets,
        )
    }

    /**
     * Returns [release] if it's a stable release strictly newer than [currentVersion] and not the version
     * the user chose to skip; otherwise null. A non-semver current version ("dev") never matches.
     */
    fun newerRelease(release: Release?, currentVersion: String, skippedVersion: String? = null): Release? {
        release ?: return null
        if (release.draft || release.prerelease || release.version.isPreRelease) return null
        val current = Semver.parse(currentVersion) ?: return null
        if (release.version <= current) return null
        if (skippedVersion != null && Semver.parse(skippedVersion)?.compareTo(release.version) == 0) return null
        return release
    }

    /** Maps `os.arch` to the asset arch suffix, or null for unsupported architectures. */
    fun archSuffix(osArch: String = System.getProperty("os.arch").orEmpty()): String? =
        when (osArch.lowercase()) {
            "aarch64", "arm64" -> "arm64"
            "x86_64", "amd64", "x64" -> "x64"
            else -> null
        }

    /** Picks `Budgetinator-<version>-<arch>.<ext>` from the release, tolerating a leading `v` in the file name. */
    fun selectAsset(release: Release, arch: String, ext: String = "zip"): ReleaseAsset? {
        val v = release.version.toString()
        val wanted = setOf("Budgetinator-$v-$arch.$ext", "Budgetinator-v$v-$arch.$ext").map { it.lowercase() }
        return release.assets.firstOrNull { it.name.lowercase() in wanted }
    }

    /**
     * Extracts the Team Identifier from `codesign -dv --verbose=2` output (which codesign prints on stderr).
     * Returns null for unsigned / ad-hoc signatures (`TeamIdentifier=not set`).
     */
    fun parseTeamId(codesignOutput: String): String? =
        codesignOutput.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("TeamIdentifier=") }
            ?.substringAfter('=')?.trim()
            ?.takeIf { it.isNotEmpty() && it != "not set" && it.all { c -> c.isLetterOrDigit() } }

    /** Finds the enclosing `.app` bundle path for an executable path like `/Applications/X.app/Contents/MacOS/X`. */
    fun bundlePathFrom(executablePath: String?): String? {
        executablePath ?: return null
        val idx = executablePath.indexOf(".app/Contents/")
        return if (idx < 0) null else executablePath.substring(0, idx + 4)
    }

    /** A short, plain-text excerpt of the release notes (Markdown lightly stripped) for the update banner. */
    fun notesSnippet(body: String, maxChars: Int = 500): String {
        val cleaned = body.replace("\r\n", "\n").lineSequence()
            .map { line ->
                line.trimEnd()
                    .replace(Regex("""^#{1,6}\s*"""), "")
                    .replace(Regex("""^\s*[*-]\s+"""), "• ")
                    .replace(Regex("""\*\*(.+?)\*\*"""), "$1")
                    .replace(Regex("""`([^`]*)`"""), "$1")
                    .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
            }
            .joinToString("\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
        if (cleaned.length <= maxChars) return cleaned
        val cut = cleaned.take(maxChars)
        val nice = cut.lastIndexOfAny(charArrayOf('\n', ' ')).takeIf { it > maxChars / 2 } ?: cut.length
        return cut.take(nice).trimEnd() + "…"
    }
}

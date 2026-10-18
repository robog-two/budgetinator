package net.robog.budgetinator

/** Static facts about the app: name, version, where it lives on GitHub. */
object AppInfo {
    const val NAME = "Budgetinator"
    const val BUNDLE_ID = "net.robog.budgetinator"
    const val CREDIT = "Created by Sam Knight. Developed with Claude Code. CC0 / Public Domain."
    const val REPO_OWNER = "robog-two"
    const val REPO_NAME = "budgetinator"

    /** Set by the jpackage launcher in packaged builds; `./gradlew run` has no such property. */
    val version: String = System.getProperty("jpackage.app-version")?.takeIf { it.isNotBlank() } ?: "dev"

    val isDevBuild: Boolean get() = version == "dev"

    const val repoUrl = "https://github.com/$REPO_OWNER/$REPO_NAME"
    const val releasesUrl = "$repoUrl/releases"
    const val latestReleaseApiUrl = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    const val licenseUrl = "$repoUrl/blob/main/LICENSE"
    const val carlitoLicenseUrl = "https://openfontlicense.org/open-font-license-official-text/"
}

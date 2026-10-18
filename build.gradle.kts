import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.nio.file.FileSystems
import java.nio.file.Files

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

// Single source of truth for the version: `appVersion` in gradle.properties.
// CI overrides it from the git tag (v1.2.3 -> -PappVersion=1.2.3).
val appVersion: String = providers.gradleProperty("appVersion").get().removePrefix("v")

group = "net.robog.budgetinator"
version = appVersion

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.pdfbox)

    testImplementation(libs.kotlin.test)
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
}

tasks.test {
    useJUnitPlatform()
}

// ProGuard needs the JDK's classes as library input. The Compose plugin looks for them in
// $JAVA_HOME/jmods, but JDK 24+ builds (including Temurin 25) ship without jmods (JEP 493), so
// extract them from the runtime image with `jimage` and hand ProGuard the directories instead.
val jdkHome = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
    .map { it.metadata.installationPath }
val proguardJdkDir = layout.buildDirectory.dir("proguard-jdk")
val extractJdkClassesForProguard by tasks.registering {
    val modulesImage = jdkHome.map { it.file("lib/modules") }
    val jimage = jdkHome.map { it.file("bin/jimage").asFile.absolutePath }
    val outDir = proguardJdkDir
    inputs.file(modulesImage)
    outputs.dir(outDir)
    doLast {
        val root = outDir.get().asFile
        val classes = root.resolve("classes")
        root.deleteRecursively()
        classes.mkdirs()
        val exit = ProcessBuilder(jimage.get(), "extract", "--dir", classes.absolutePath, modulesImage.get().asFile.absolutePath)
            .inheritIO().start().waitFor()
        check(exit == 0) { "jimage extract failed ($exit)" }
        val libraryJars = classes.listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }
            .joinToString("\n") { "-libraryjars '${it.absolutePath}'(!module-info.class)" }
        root.resolve("jdk-libraries.pro").writeText(libraryJars + "\n")
    }
}
tasks.matching { it.name == "proguardReleaseJars" }.configureEach {
    dependsOn(extractJdkClassesForProguard)
    // The skiko runtime jar bundles the native library for both macOS archs. Compose later moves
    // the host arch's copy out of the jar into $APPDIR; the other arch's copy is dead weight (~22 MB).
    val hostArch = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "arm64" else "x64"
    val foreignLib = Regex("libskiko-macos-(?!$hostArch\\.).*\\.dylib(\\.sha256)?")
    doLast {
        outputs.files.asFileTree.matching { include("**/*.jar") }.forEach { jar ->
            FileSystems.newFileSystem(jar.toPath()).use { fs ->
                Files.list(fs.getPath("/")).use { entries ->
                    entries.filter { it.fileName?.toString()?.matches(foreignLib) == true }
                        .toList().forEach { Files.delete(it) }
                }
            }
        }
    }
}

// Signing is off unless asked for, so local dev builds (`run`, `createDistributable`) work without
// a certificate. Enable with -Pmac.sign=true (or MAC_SIGN=true) and optionally pick the identity
// ("Developer ID Application: Name (TEAMID)" or just "Name (TEAMID)") and keychain.
fun setting(property: String, env: String): Provider<String> =
    providers.gradleProperty(property).orElse(providers.environmentVariable(env))

val macSign: Boolean = setting("mac.sign", "MAC_SIGN").map { it.toBoolean() }.getOrElse(false)

compose.desktop {
    application {
        mainClass = "net.robog.budgetinator.MainKt"
        jvmArgs += "--enable-native-access=ALL-UNNAMED"

        buildTypes.release.proguard {
            // ProGuard 7.8+ is required to read JDK 25 (class file version 69) bytecode.
            version.set(libs.versions.proguard)
            isEnabled.set(true)
            obfuscate.set(false)
            optimize.set(true)
            joinOutputJars.set(true)
            configurationFiles.from(project.file("proguard-rules.pro"))
            configurationFiles.from(proguardJdkDir.map { it.file("jdk-libraries.pro") })
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "Budgetinator"
            packageVersion = appVersion
            description = "SGC budget request builder"
            copyright = "Created by Sam Knight. Developed with Claude Code. CC0 / Public Domain."
            vendor = "Sam Knight"
            // JDK modules beyond Compose's defaults (from `./gradlew suggestRuntimeModules`);
            // java.net.http is the updater's HttpClient. Re-run that task after adding dependencies.
            modules("java.instrument", "java.net.http", "java.prefs", "jdk.unsupported")

            // Double-clicking a saved draft in Finder opens it (handler in ui/Document.kt).
            fileAssociation(
                mimeType = "application/x-budgetinator",
                extension = "budget",
                description = "Budgetinator Budget Request",
            )

            macOS {
                bundleID = "net.robog.budgetinator"
                dockName = "Budgetinator"
                appCategory = "public.app-category.productivity"
                // JDK 25 itself requires macOS 12+.
                minimumSystemVersion = "12.0"
                iconFile.set(project.file("packaging/AppIcon.icns"))
                infoPlist {
                    // Shown in the macOS permission prompt the first time "Send Logs" scripts Apple Mail.
                    extraKeysRawXml = """
                        <key>NSAppleEventsUsageDescription</key>
                        <string>Budgetinator uses Mail to attach its log file when you choose Send Logs.</string>
                    """.trimIndent()
                }

                entitlementsFile.set(project.file("packaging/entitlements.plist"))
                runtimeEntitlementsFile.set(project.file("packaging/entitlements.plist"))

                signing {
                    sign.set(macSign)
                    setting("mac.signing.identity", "MAC_SIGNING_IDENTITY").orNull?.let { identity.set(it) }
                    setting("mac.signing.keychain", "MAC_SIGNING_KEYCHAIN").orNull?.let { keychain.set(it) }
                }
            }
        }
    }
}

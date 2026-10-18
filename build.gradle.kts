import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.composeMultiplatform)
}

group = "net.robog.budgetinator"
version = "1.0.0"

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.swing)

    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "net.robog.budgetinator.MainKt"
        jvmArgs += "--enable-native-access=ALL-UNNAMED"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Budgetinator"
            packageVersion = "1.0.0"
            description = "SGC budget request builder"

            macOS {
                bundleID = "net.robog.budgetinator"
                dockName = "Budgetinator"
                iconFile.set(project.file("packaging/AppIcon.icns"))
            }
        }
    }
}

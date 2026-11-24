import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask

plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
    id("org.jetbrains.compose") version "1.8.0"
}

// CI passes -PappVersion=<version>; local builds use the base version below.
val baseVersion = "1.1.1"
val appVersion = (findProperty("appVersion") as String?) ?: baseVersion

group = "ph.agritourism"
version = appVersion

// Optional escape hatch for Windows/OneDrive file locks in build/compose during packaging.
if (project.hasProperty("buildDirOverride")) {
    layout.buildDirectory.set(file(project.property("buildDirOverride")!!))
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")

    testImplementation(kotlin("test"))
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
    testImplementation(compose.desktop.uiTestJUnit4)
}

tasks.test {
    systemProperty("java.awt.headless", "true")
    systemProperty("screenshots.dir", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
    testLogging { events("passed", "failed"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

compose.desktop {
    application {
        mainClass = "ph.agritourism.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "AgriTourism Philippines"
            packageVersion = appVersion
            description = "AgriTourism Philippines desktop app (Kotlin Compose + SQLite)"
            vendor = "AgriTourism Philippines"
            licenseFile.set(project.file("LICENSE"))
            modules("java.sql")
            windows {
                menuGroup = "AgriTourism"
                shortcut = true
                dirChooser = true
            }
        }
    }
}

// Generated from icons/pwa-512x512.png (16–256 px) so EXE, MSI and taskbar icons match the app.
tasks.withType<AbstractJPackageTask>().configureEach {
    iconFile.set(project.file("src/main/resources/icons/app.ico"))
}

val releaseDir = layout.buildDirectory.dir("release")

/** Generates the seeded SQLite database that ships with release artifacts. */
val seedDatabase by tasks.registering(JavaExec::class) {
    group = "distribution"
    description = "Generates a pre-seeded agritourism.db for release artifacts."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ph.agritourism.tools.SeedDatabaseKt")
    args(releaseDir.get().file("agritourism.db").asFile.absolutePath)
}

/** Builds the native installer plus the seeded database into build/release. */
val releaseArtifacts by tasks.registering {
    group = "distribution"
    description = "Builds the native installer and seeded database for a GitHub release."
    // packageExe works without WiX; MSI packaging on Windows requires the WiX 3 toolset.
    dependsOn(tasks.named("packageExe"), seedDatabase)
    doLast {
        val out = releaseDir.get().asFile
        out.mkdirs()
        copy {
            from(layout.buildDirectory.dir("compose/binaries/main")) {
                include("exe/*.exe", "msi/*.msi")
            }
            into(out)
            eachFile { path = name }
            includeEmptyDirs = false
        }
        out.listFiles()?.sortedBy { it.name }?.forEach { println("release artifact: ${it.name} (${it.length()} bytes)") }
    }
}

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

// JavaCPP ships native FFmpeg libs per-platform under a classifier; each CI job packages
// for the OS it's running on, so only that OS's classifier needs to be on the classpath.
val javacppPlatform = run {
    val osName = System.getProperty("os.name").lowercase()
    val osArch = System.getProperty("os.arch").lowercase()
    when {
        osName.contains("win") -> "windows-x86_64"
        osName.contains("mac") -> if (osArch.contains("aarch64") || osArch.contains("arm")) "macosx-arm64" else "macosx-x86_64"
        else -> "linux-x86_64"
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.bytedeco:javacv:1.5.11")
    implementation("org.bytedeco:ffmpeg:7.1-1.5.11")
    implementation("org.bytedeco:ffmpeg:7.1-1.5.11:$javacppPlatform")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

// The real release version. Compose Desktop's Dmg validation requires MAJOR > 0, so macOS
// gets its own jpackage-internal version below; the public artifact still gets renamed to
// this version in CI. Deb/Msi have no such restriction and use it directly.
val appVersion = "0.4.0"

compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "wren"
            packageVersion = appVersion
            modules("java.net.http")
            linux {
                iconFile.set(project.file("wren.png"))
            }
            macOS {
                // Both the app-image bundler (createDistributable) and the Dmg bundler
                // enforce jpackage's MAJOR > 0 rule, so both need overriding here — never
                // shown to users. CI renames the produced .dmg to the real appVersion.
                packageVersion = "1.0.0"
                dmgPackageVersion = "1.0.0"
            }
        }
    }
}

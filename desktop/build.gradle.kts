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

compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "wren"
            packageVersion = "1.0.0"
            modules("java.net.http")
            linux {
                iconFile.set(project.file("wren.png"))
            }
        }
    }
}

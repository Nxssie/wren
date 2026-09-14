plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.ui)
    implementation(compose.material)
    implementation(compose.materialIconsExtended)
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.media3:media3-exoplayer:1.5.0")
    implementation("androidx.media3:media3-common:1.5.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.0")
    implementation("androidx.media:media:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.browser:browser:1.8.0")
    debugImplementation("androidx.compose.ui:ui-tooling-preview:1.7.5")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.5")
}

/**
 * `versionCode` has to grow for an install to replace the previous build, and the commit count
 * does that without anyone remembering to bump a number. `versionName` keeps the `git describe`
 * string, so a build installed on a phone says which revision it came from. Both are overridable
 * from the environment, which is what a tagged release (or CI) would use.
 *
 * A missing or shallow git checkout falls back to 1 / "1.0.0" instead of failing the build.
 */
val gitDescription: String? = runCatching {
    providers.exec {
        commandLine("git", "describe", "--tags", "--always", "--dirty")
        workingDir = rootProject.projectDir
        isIgnoreExitValue = true
    }.standardOutput.asText.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
}.getOrNull()

/** What both the APK file and the installed app report; the environment can pin it for a tag. */
val appVersionName: String = System.getenv("WREN_VERSION_NAME")
    ?: gitDescription?.removePrefix("v")
    ?: "1.0.0"

/**
 * Whether the four release signing variables are present. Both the signing config and the
 * artifact name depend on it, so an unsigned build is named as one — AGP's own `-unsigned`
 * marker, kept because an APK that cannot be installed should not look like one that can.
 */
val hasReleaseSigning: Boolean = listOf(
    "WREN_RELEASE_KEYSTORE_PATH",
    "WREN_RELEASE_KEYSTORE_PASSWORD",
    "WREN_RELEASE_KEY_ALIAS",
    "WREN_RELEASE_KEY_PASSWORD",
).all { !System.getenv(it).isNullOrBlank() }

val gitCommitCount: Int = runCatching {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        workingDir = rootProject.projectDir
        isIgnoreExitValue = true
    }.standardOutput.asText.getOrNull()?.trim()?.toIntOrNull()
}.getOrNull() ?: 1

android {
    namespace = "com.wren.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wren.app"
        minSdk = 26
        targetSdk = 34
        versionCode = System.getenv("WREN_VERSION_CODE")?.toIntOrNull() ?: gitCommitCount
        versionName = appVersionName

        // Bundled Google OAuth client, same env vars CI uses for desktop. Desktop-app
        // clients have no real secret (Google documents this), so baking it in is safe;
        // users can still override it with <config>/oauth.json on device.
        buildConfigField("String", "WREN_GOOGLE_CLIENT_ID", "\"${System.getenv("WREN_GOOGLE_CLIENT_ID") ?: ""}\"")
        buildConfigField("String", "WREN_GOOGLE_CLIENT_SECRET", "\"${System.getenv("WREN_GOOGLE_CLIENT_SECRET") ?: ""}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Local release signing: credentials come from the environment (never the repo), and the
    // config is simply absent when they are not set, so debug builds and other machines are
    // unaffected. See the README for the `keytool` line and the variable names.
    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(System.getenv("WREN_RELEASE_KEYSTORE_PATH"))
                storePassword = System.getenv("WREN_RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("WREN_RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("WREN_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Unset variables leave the release build unsigned instead of failing the build.
            signingConfigs.findByName("release")?.takeIf { it.storeFile?.exists() == true }
                ?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            res.srcDirs("src/main/res")
            java.srcDirs("src/main/kotlin")
        }
    }
}

/**
 * AGP names every artifact `app-release.apk`, which says nothing about which build it is. The
 * release is handed out as `wren-<versionName>.apk` instead, so the file on a phone names its
 * own revision and two builds are never confused.
 */
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val unsigned = if (hasReleaseSigning) "" else "-unsigned"
        val name = "wren-${appVersionName.replace(Regex("[^A-Za-z0-9._-]"), "-")}$unsigned.apk"
        variant.outputs.forEach { output ->
            // Only AGP's impl type exposes the file name; if that ever changes the build keeps
            // working under AGP's default name rather than failing on an internal API.
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)
                ?.outputFileName
                ?.set(name)
        }
    }
}

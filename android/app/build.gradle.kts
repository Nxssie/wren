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
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.media3:media3-exoplayer:1.5.0")
    implementation("androidx.media3:media3-common:1.5.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.0")
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
        versionName = System.getenv("WREN_VERSION_NAME")
            ?: gitDescription?.removePrefix("v")
            ?: "1.0.0"

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
            val store = System.getenv("WREN_RELEASE_KEYSTORE_PATH")
            val storePassword = System.getenv("WREN_RELEASE_KEYSTORE_PASSWORD")
            val alias = System.getenv("WREN_RELEASE_KEY_ALIAS")
            val keyPassword = System.getenv("WREN_RELEASE_KEY_PASSWORD")
            if (store != null && storePassword != null && alias != null && keyPassword != null) {
                storeFile = file(store)
                this.storePassword = storePassword
                keyAlias = alias
                this.keyPassword = keyPassword
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

package com.wren.app.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import download.FileDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import update.UpdateRelease
import util.Http
import util.Log
import java.io.File

/**
 * Downloads a release APK into the app cache and hands it to Android's package installer.
 *
 * Android is the only target of this feature with no package manager behind it, so the whole
 * sideload — fetch, signature check, install dialog — lives here. Everything that can be decided
 * without the OS (version comparison, release parsing, the throttle) is `:shared`'s job; this
 * class is glue and is consequently verified on a device rather than by a JVM test.
 *
 * The app is sideloaded, so installing is a two-part user grant: `REQUEST_INSTALL_PACKAGES`
 * (manifest) plus "install unknown apps" for Wren, which [canInstall] reads and
 * [installPermissionIntent] routes to when it is missing.
 */
class ApkInstaller(private val context: Context) {

    /**
     * Downloads [release]'s APK into the cache, reporting progress from 0f to 1f, and returns the
     * finished file. Throws on any failure; the partially written file is discarded first, so a
     * caller never sees a truncated APK that looks complete.
     */
    suspend fun download(release: UpdateRelease, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            onProgress(0f)

            val request = Request.Builder().url(release.apkUrl).build()
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("APK download failed with HTTP ${response.code}")

                // FileDestination is reused rather than reimplemented: it already streams into a
                // `.part` sibling and renames only in `commit()`, which is exactly the guarantee
                // this needs, and it is what the app's other downloads use.
                //
                // The asset name arrives over the network and becomes a path under the cache, so
                // only its basename is used: `selectApk` constrains the `wren-`/`.apk` shape but
                // not traversal, and `File(dir, "../../x.apk")` would escape the updates dir.
                val output = FileDestination(updatesDir()).open(File(release.apkName).name)
                try {
                    val total = response.body?.contentLength() ?: -1L
                    var written = 0L
                    response.body?.byteStream()?.use { input ->
                        output.stream.use { sink ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                sink.write(buffer, 0, read)
                                written += read
                                // A -1 (or 0) length means GitHub sent a chunked body, so no
                                // fraction exists; reporting one anyway would divide by it and
                                // hand the UI a negative or NaN value. Silent is the honest state.
                                if (total > 0) {
                                    onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                                }
                            }
                            sink.flush()
                        }
                    } ?: error("APK download returned an empty body")
                    output.commit()
                } catch (failure: Throwable) {
                    // Cancellation lands here too: aborting removes the `.part` file before the
                    // exception is rethrown, so a cancelled download leaves nothing behind.
                    output.abort()
                    throw failure
                }

                val apk = File(output.path)
                verifyArchive(apk)
                onProgress(1f)
                Log.i(TAG, "downloaded ${apk.name} (${apk.length() / 1024} KiB)")
                apk
            }
        }

    /**
     * Opens the system installer for an APK this installer produced, sharing it as a
     * `content://` URI so the installer can read a file inside our private cache.
     *
     * `resolveActivity` is deliberately not used: it would need a `<queries>` element for the
     * installer package, and the `try`/`catch` gives the same answer without one.
     */
    fun install(apk: File) {
        require(apk.exists()) { "the downloaded APK is gone; download it again" }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // The installer is a system app, so it belongs in its own task; this also keeps
            // startActivity legal when `context` is the application context (task 6 wires the
            // installer from WrenApplication).
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(intent)
        } catch (notFound: ActivityNotFoundException) {
            throw IllegalStateException("no package installer is available to install ${apk.name}", notFound)
        }
    }

    /**
     * False when the user has not granted "install unknown apps" to Wren — the grant is
     * independent of `REQUEST_INSTALL_PACKAGES` and can be revoked at any time.
     */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The system screen where that grant is given, scoped to Wren. */
    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Deletes APKs left in the cache by an earlier run. An APK surviving a successful install is
     * dead weight, and the cache directory is not a place the system cleans up on its own here.
     */
    fun clearStale() {
        updatesDir().listFiles().orEmpty().forEach { file ->
            if (file.delete()) Log.i(TAG, "cleared stale ${file.name}")
            else Log.w(TAG, "could not clear stale ${file.name}")
        }
    }

    /**
     * Refuses an APK Android could not parse, and one signed with a different key.
     *
     * The parse check exists because a truncated or corrupt body would otherwise reach the
     * installer and fail there with a much worse error. The signing check exists because Android
     * refuses to replace an app signed with a different key, and "signed with another key" is a
     * clearer message than the installer's.
     *
     * The asymmetry is deliberate: when either side's signers cannot be read, the install is
     * allowed through. Android enforces the signature itself when the installer runs, and
     * blocking on an unreadable certificate here would strand a legitimate update behind a
     * failure that has nothing to do with the signature.
     */
    private fun verifyArchive(apk: File) {
        val archive = context.packageManager.getPackageArchiveInfo(apk.path, signingFlags())
        if (archive == null) {
            apk.delete()
            error("downloaded APK is not a readable package; the download was truncated or corrupt")
        }

        val archiveSigners = signers(archive)
        val installedSigners = signers(installedPackage())
        if (archiveSigners == null || installedSigners == null) {
            Log.w(TAG, "signing certificates unavailable; leaving the signature check to Android")
            return
        }

        if (archiveSigners.intersect(installedSigners).isEmpty()) {
            apk.delete()
            error("downloaded APK is signed with a different key than the installed app; it cannot replace it")
        }
    }

    /**
     * The signers of [info] as comparable byte lists, or null when they cannot be read.
     *
     * `signingInfo` only exists on API 28+, so older devices fall back to the deprecated
     * `signatures` array. The rotation-aware history is used for single-signer packages and the
     * APK's own signers for multi-signer ones, which is the pair Android compares itself.
     */
    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo?): Set<List<Byte>>? {
        if (info == null) return null

        val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return null
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else {
            info.signatures ?: return null
        }

        return signatures.mapTo(mutableSetOf()) { it.toByteArray().toList() }
    }

    private fun installedPackage(): PackageInfo? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, signingFlags())
    }.onFailure { Log.w(TAG, "could not read the installed package's signers", it) }.getOrNull()

    /** `GET_SIGNING_CERTIFICATES` is API 28+; below it the legacy `signatures` field needs no flag. */
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0

    private fun updatesDir(): File = File(context.cacheDir, UPDATES_DIR)

    private companion object {
        const val TAG = "ApkInstaller"
        const val UPDATES_DIR = "updates"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}

package update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A GitHub release that offers a newer Android APK.
 *
 * [version] is the parsed [AppVersion] from `tag_name` and [tag] is that raw tag (`v0.6.0`), kept
 * because the asset name is derived from it. [apkSize] is the asset's byte count, or zero when
 * GitHub omitted it, so a progress UI never divides by a size the payload did not provide.
 */
data class UpdateRelease(
    val version: AppVersion,
    val tag: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    val notes: String?,
)

/**
 * The newest release as an update candidate, or null when the payload offers none usable.
 *
 * Silently returns null for anything it cannot act on — malformed or reshaped JSON, a missing or
 * unparseable `tag_name`, a draft or prerelease, or a release whose assets carry no usable APK.
 * The caller is an unattended launch check, so parsing must never throw at it.
 */
fun parseLatestRelease(json: String): UpdateRelease? {
    val release = runCatching { parser.decodeFromString<GitHubRelease>(json) }.getOrNull() ?: return null

    if (release.draft || release.prerelease) return null

    val tag = release.tagName ?: return null
    val version = AppVersion.parse(tag) ?: return null

    val selected = selectApk(release.assets.orEmpty(), tag) ?: return null

    return UpdateRelease(
        version = version,
        tag = tag,
        apkName = selected.name,
        apkUrl = selected.browserDownloadUrl,
        apkSize = (selected.size ?: 0L).coerceAtLeast(0L),
        notes = release.body,
    )
}

/**
 * The APK asset to install, or null when the release offers no unambiguous one.
 *
 * Only assets named `wren-*.apk` count, and a `-unsigned` build never does: an APK without the
 * release key cannot replace the installed app. A lone candidate is accepted as-is; several are
 * only accepted when one is named for the release version (`wren-<version>.apk`, exactly what
 * `scripts/publish-android-release.sh` uploads), because guessing between them could offer the
 * wrong build.
 */
private fun selectApk(assets: List<GitHubAsset>, tag: String): GitHubAsset? {
    val candidates = assets.filter { it.name.startsWith("wren-") && it.name.endsWith(".apk", ignoreCase = true) }
        .filterNot { "-unsigned" in it.name }

    return when (candidates.size) {
        0 -> null
        1 -> candidates.single()
        else -> {
            // The published asset takes its version from the tag with the leading `v` stripped.
            val expected = "wren-${tag.removePrefix("v")}.apk"
            candidates.firstOrNull { it.name == expected }
        }
    }
}

/**
 * The subset of a `releases/latest` body this feature reads. GitHub tags `tag_name` and
 * `browser_download_url` with snake_case, and every other field — dozens per release and per
 * asset — is ignored rather than modelled.
 */
@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val body: String? = null,
    val assets: List<GitHubAsset>? = null,
)

@Serializable
private data class GitHubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    val size: Long? = null,
)

private val parser = Json { ignoreUnknownKeys = true }

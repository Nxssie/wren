package update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class UpdateReleaseTest {

    /**
     * A trimmed but realistically shaped `releases/latest` body. The real endpoint carries dozens
     * of fields per release and per asset, so this keeps the ones the model deliberately does not
     * declare (`id`, `node_id`, `uploader`, `state`, …). It also carries both an unsigned and a
     * signed APK, plus the checksum sidecar the release tooling may attach.
     */
    private val latestRelease = """
        {
          "url": "https://api.github.com/repos/Nxssie/wren/releases/18654321",
          "html_url": "https://github.com/Nxssie/wren/releases/tag/v0.6.0",
          "assets_url": "https://api.github.com/repos/Nxssie/wren/releases/18654321/assets",
          "upload_url": "https://uploads.github.com/repos/Nxssie/wren/releases/18654321/assets{?name,label}",
          "id": 18654321,
          "node_id": "RE_kwDOJ1a",
          "author": { "login": "Nxssie", "id": 1, "type": "User" },
          "tag_name": "v0.6.0",
          "target_commitish": "main",
          "name": "v0.6.0",
          "draft": false,
          "prerelease": false,
          "created_at": "2025-01-10T09:00:00Z",
          "published_at": "2025-01-10T09:05:00Z",
          "assets": [
            {
              "url": "https://api.github.com/repos/Nxssie/wren/releases/assets/1",
              "id": 1,
              "node_id": "RA_kwDOJ1a",
              "name": "Wren-0.6.0.dmg",
              "label": "",
              "uploader": { "login": "Nxssie", "id": 1, "type": "User" },
              "content_type": "application/octet-stream",
              "state": "uploaded",
              "size": 41234567,
              "download_count": 12,
              "created_at": "2025-01-10T09:04:00Z",
              "updated_at": "2025-01-10T09:04:00Z",
              "browser_download_url": "https://github.com/Nxssie/wren/releases/download/v0.6.0/Wren-0.6.0.dmg"
            },
            {
              "url": "https://api.github.com/repos/Nxssie/wren/releases/assets/2",
              "id": 2,
              "node_id": "RA_kwDOJ1b",
              "name": "Wren-0.6.0.msi",
              "label": "",
              "state": "uploaded",
              "content_type": "application/octet-stream",
              "size": 38900123,
              "download_count": 4,
              "browser_download_url": "https://github.com/Nxssie/wren/releases/download/v0.6.0/Wren-0.6.0.msi"
            },
            {
              "url": "https://api.github.com/repos/Nxssie/wren/releases/assets/3",
              "id": 3,
              "node_id": "RA_kwDOJ1c",
              "name": "wren_0.6.0_amd64.deb",
              "label": "",
              "state": "uploaded",
              "content_type": "application/octet-stream",
              "size": 36500111,
              "download_count": 7,
              "browser_download_url": "https://github.com/Nxssie/wren/releases/download/v0.6.0/wren_0.6.0_amd64.deb"
            },
            {
              "url": "https://api.github.com/repos/Nxssie/wren/releases/assets/4",
              "id": 4,
              "node_id": "RA_kwDOJ1d",
              "name": "wren-0.6.0-unsigned.apk",
              "label": "",
              "state": "uploaded",
              "content_type": "application/vnd.android.package-archive",
              "size": 34500000,
              "download_count": 0,
              "browser_download_url": "https://github.com/Nxssie/wren/releases/download/v0.6.0/wren-0.6.0-unsigned.apk"
            },
            {
              "url": "https://api.github.com/repos/Nxssie/wren/releases/assets/5",
              "id": 5,
              "node_id": "RA_kwDOJ1e",
              "name": "wren-0.6.0.apk.sha256",
              "label": "",
              "state": "uploaded",
              "content_type": "text/plain",
              "size": 64,
              "download_count": 1,
              "browser_download_url": "https://github.com/Nxssie/wren/releases/download/v0.6.0/wren-0.6.0.apk.sha256"
            },
            {
              "url": "https://api.github.com/repos/Nxssie/wren/releases/assets/6",
              "id": 6,
              "node_id": "RA_kwDOJ1f",
              "name": "wren-0.6.0.apk",
              "label": "",
              "state": "uploaded",
              "content_type": "application/vnd.android.package-archive",
              "size": 34567890,
              "download_count": 31,
              "browser_download_url": "https://github.com/Nxssie/wren/releases/download/v0.6.0/wren-0.6.0.apk"
            }
          ],
          "tarball_url": "https://api.github.com/repos/Nxssie/wren/tarball/v0.6.0",
          "zipball_url": "https://api.github.com/repos/Nxssie/wren/zipball/v0.6.0",
          "body": "What's new in 0.6.0:\n- faster playback\n"
        }
    """.trimIndent()

    @Test
    fun `parses the newest release into an update candidate`() {
        val release = parseLatestRelease(latestRelease)!!

        assertEquals(AppVersion.parse("0.6.0"), release.version)
        assertEquals("v0.6.0", release.tag)
        assertEquals("wren-0.6.0.apk", release.apkName)
        assertEquals(
            "https://github.com/Nxssie/wren/releases/download/v0.6.0/wren-0.6.0.apk",
            release.apkUrl,
        )
        assertEquals(34_567_890L, release.apkSize)
        assertEquals("What's new in 0.6.0:\n- faster playback\n", release.notes)
    }

    @Test
    fun `ignores release and asset fields the model does not declare`() {
        // latestRelease carries unknown scalar, nested-object and array fields on both objects.
        assertNotNull(parseLatestRelease(latestRelease))
    }

    @Test
    fun `selects the APK among the platform assets`() {
        val release = parseLatestRelease(
            release(
                assets = listOf(
                    asset("Wren-0.6.0.dmg"),
                    asset("Wren-0.6.0.msi"),
                    asset("wren_0.6.0_amd64.deb"),
                    asset("wren-0.6.0.apk"),
                ),
            ),
        )!!

        assertEquals("wren-0.6.0.apk", release.apkName)
    }

    @Test
    fun `never selects an unsigned APK`() {
        assertNull(parseLatestRelease(release(assets = listOf(asset("wren-0.6.0-unsigned.apk")))))
    }

    @Test
    fun `prefers the signed APK when both signed and unsigned are present`() {
        val release = parseLatestRelease(
            release(
                assets = listOf(
                    asset("wren-0.6.0-unsigned.apk"),
                    asset("wren-0.6.0.apk"),
                ),
            ),
        )!!

        assertEquals("wren-0.6.0.apk", release.apkName)
    }

    @Test
    fun `never selects an unsigned APK regardless of the marker's capitalisation`() {
        assertNull(parseLatestRelease(release(assets = listOf(asset("wren-0.6.0-Unsigned.apk")))))
    }

    @Test
    fun `prefers the signed APK when the unsigned one is capitalised differently`() {
        val release = parseLatestRelease(
            release(
                assets = listOf(
                    asset("wren-0.6.0-UNSIGNED.apk"),
                    asset("wren-0.6.0.apk"),
                ),
            ),
        )!!

        assertEquals("wren-0.6.0.apk", release.apkName)
    }

    @Test
    fun `matches the apk suffix case-insensitively`() {
        val release = parseLatestRelease(release(assets = listOf(asset("wren-0.6.0.APK"))))!!

        assertEquals("wren-0.6.0.APK", release.apkName)
    }

    @Test
    fun `ignores assets that are not APKs`() {
        assertNull(
            parseLatestRelease(
                release(
                    assets = listOf(
                        asset("wren-0.6.0.apk.sha256"),
                        asset("wren-0.6.0.apk.asc"),
                        asset("Wren-0.6.0.dmg"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `returns null when the release carries no APK`() {
        assertNull(
            parseLatestRelease(
                release(
                    assets = listOf(
                        asset("Wren-0.6.0.dmg"),
                        asset("Wren-0.6.0.msi"),
                        asset("wren_0.6.0_amd64.deb"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `selects the only APK even when its name does not carry the version`() {
        val release = parseLatestRelease(release(assets = listOf(asset("wren-release.apk"))))!!

        assertEquals("wren-release.apk", release.apkName)
    }

    @Test
    fun `disambiguates several APKs by the parsed tag`() {
        val release = parseLatestRelease(
            release(
                assets = listOf(
                    asset("wren-0.5.0.apk"),
                    asset("wren-0.6.0.apk"),
                ),
            ),
        )!!

        assertEquals("wren-0.6.0.apk", release.apkName)
    }

    @Test
    fun `returns null when several APKs match but none is the parsed version`() {
        assertNull(
            parseLatestRelease(
                release(
                    assets = listOf(
                        asset("wren-0.5.0.apk"),
                        asset("wren-0.7.0.apk"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `falls back to zero size when the asset has no size`() {
        val release = parseLatestRelease(release(assets = listOf(asset("wren-0.6.0.apk", size = null))))!!

        assertEquals(0L, release.apkSize)
    }

    @Test
    fun `clamps a negative size to zero`() {
        val release = parseLatestRelease(release(assets = listOf(asset("wren-0.6.0.apk", size = -1))))!!

        assertEquals(0L, release.apkSize)
    }

    @Test
    fun `nulls the notes when the release carries no body`() {
        val release = parseLatestRelease(release(assets = listOf(asset("wren-0.6.0.apk"))))!!

        assertNull(release.notes)
    }

    @Test
    fun `returns null when the tag is not a version`() {
        assertNull(parseLatestRelease(release(tagName = "nightly", assets = listOf(asset("wren-nightly.apk")))))
    }

    @Test
    fun `returns null for a draft release`() {
        assertNull(parseLatestRelease(release(draft = true, assets = listOf(asset("wren-0.6.0.apk")))))
    }

    @Test
    fun `returns null for a prerelease`() {
        assertNull(parseLatestRelease(release(prerelease = true, assets = listOf(asset("wren-0.6.0.apk")))))
    }

    @Test
    fun `returns null when tag_name is missing`() {
        assertNull(parseLatestRelease("""{ "draft": false, "prerelease": false, "assets": [] }"""))
    }

    @Test
    fun `returns null when assets is missing`() {
        assertNull(parseLatestRelease("""{ "tag_name": "v0.6.0", "draft": false, "prerelease": false }"""))
    }

    @Test
    fun `returns null when the assets array is empty`() {
        assertNull(parseLatestRelease(release(assets = emptyList())))
    }

    @Test
    fun `returns null for a malformed body`() {
        assertNull(parseLatestRelease("{ not json"))
    }

    @Test
    fun `returns null for an empty body`() {
        assertNull(parseLatestRelease(""))
        assertNull(parseLatestRelease("   "))
    }

    @ParameterizedTest
    @ValueSource(strings = ["[]", "\"v0.6.0\"", "null", "42", "true"])
    fun `returns null when the body is valid JSON but not an object`(body: String) {
        assertNull(parseLatestRelease(body))
    }

    /** A `releases/latest` object with only the fields this feature reads. */
    private fun release(
        tagName: String = "v0.6.0",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: List<String> = emptyList(),
    ): String = """
        {
          "url": "https://api.github.com/repos/Nxssie/wren/releases/18654321",
          "id": 18654321,
          "node_id": "RE_kwDOJ1a",
          "tag_name": "$tagName",
          "target_commitish": "main",
          "name": "$tagName",
          "draft": $draft,
          "prerelease": $prerelease,
          "created_at": "2025-01-10T09:00:00Z",
          "published_at": "2025-01-10T09:05:00Z",
          "assets": [${assets.joinToString(",")}],
          "body": null
        }
    """.trimIndent()

    /** A release asset with the unknown fields the GitHub payload always carries. */
    private fun asset(name: String, size: Long? = 1_234_567L): String = """
        {
          "url": "https://api.github.com/repos/Nxssie/wren/releases/assets/9",
          "id": 9,
          "node_id": "RA_kwDOJ1a",
          "name": "$name",
          "label": "",
          "state": "uploaded",
          "content_type": "application/octet-stream",
          "size": ${size ?: "null"},
          "download_count": 3,
          "browser_download_url": "https://github.com/Nxssie/wren/releases/download/v0.6.0/$name"
        }
    """.trimIndent()
}

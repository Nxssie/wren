package update

/**
 * A `git describe`-style app version, ordered so that "newer" agrees with what Android's
 * package installer will accept.
 *
 * A describe suffix means the build is *ahead* of its tag, not a semver pre-release:
 * `0.6.0-12-gabc1234` is twelve commits past the `0.6.0` tag and its `versionCode` (the commit
 * count) is higher than the tagged release's. Ordering it as older — the semver instinct —
 * would offer the `0.6.0` APK to that install, download it, and then have Android refuse the
 * downgrade. A suffixed version therefore outranks its own tag, and two suffixes compare by
 * commit count.
 *
 * [suffix] holds the text after the `major.minor.patch` triple without its leading dash
 * (`12-gabc1234`, `dirty`, `12-gabc1234-dirty`); it is null for a plain release tag.
 */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val suffix: String?,
    val commitsAhead: Int?,
) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int {
        val triple = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
        if (triple != 0) return triple

        val left = suffix
        val right = other.suffix
        if (left == null || right == null) {
            return when {
                left == null && right == null -> 0
                left == null -> -1
                else -> 1
            }
        }

        val leftAhead = commitsAhead
        val rightAhead = other.commitsAhead
        if (leftAhead != null && rightAhead != null) {
            val byCount = leftAhead.compareTo(rightAhead)
            if (byCount != 0) return byCount
        }

        // Same count (or none): tie-break on the text so ordering stays consistent with equals —
        // a `-dirty` build outranks the same describe without it, and equal counts with different
        // hashes stay distinguishable.
        return left.compareTo(right)
    }

    /** True when this version is strictly newer than [other]; false when equal or older. */
    fun isNewerThan(other: AppVersion?): Boolean = other != null && compareTo(other) > 0

    companion object {
        private val VERSION = Regex("""^[vV]?(\d+)\.(\d+)(?:\.(\d+))?(?:-(.+))?$""")
        private val DESCRIBE_SUFFIX = Regex("""^(\d+)-g[0-9a-fA-F]+(-dirty)?$""")

        /** [raw] as a version, or null when it is not a parseable `git describe`-style string. */
        fun parse(raw: String?): AppVersion? {
            val match = raw?.let { VERSION.matchEntire(it) } ?: return null
            val suffix = match.groupValues[4].ifEmpty { null }
            return AppVersion(
                major = match.groupValues[1].toInt(),
                minor = match.groupValues[2].toInt(),
                patch = match.groupValues[3].ifEmpty { "0" }.toInt(),
                suffix = suffix,
                commitsAhead = suffix?.let { DESCRIBE_SUFFIX.matchEntire(it)?.groupValues?.get(1)?.toInt() },
            )
        }
    }
}

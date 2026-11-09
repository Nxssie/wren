package mix

import kotlinx.serialization.Serializable

/**
 * A slot on the Camelot wheel: `number` 1..12 and side `A` (minor) or `B` (major).
 *
 * Stored as the wheel coordinates rather than as the musical key because that is what the
 * mixing rules read: two tracks mix when their slots are the same, the same number on the
 * other side, or one step around on the same side.
 */
@Serializable
data class CamelotKey(val number: Int, val letter: String) {

    init {
        require(number in 1..12) { "camelot number out of range: $number" }
        require(letter == "A" || letter == "B") { "camelot side must be A or B: $letter" }
    }

    override fun toString(): String = "$number$letter"

    /**
     * How [other] sits relative to this key, from the harmonic-mixing rules:
     * [HarmonicRelation.SAME], [HarmonicRelation.RELATIVE] (same number, other side),
     * [HarmonicRelation.ADJACENT] (one step around, same side), else [HarmonicRelation.NONE].
     */
    fun relationTo(other: CamelotKey): HarmonicRelation = when {
        number == other.number && letter == other.letter -> HarmonicRelation.SAME
        number == other.number -> HarmonicRelation.RELATIVE
        letter == other.letter && isNeighbour(number, other.number) -> HarmonicRelation.ADJACENT
        else -> HarmonicRelation.NONE
    }

    private fun isNeighbour(a: Int, b: Int): Boolean {
        val step = (a - b).let { if (it < 0) -it else it }
        return step == 1 || step == 11
    }

    companion object {
        // Pitch class per slot number, indexed by number - 1: slot 1A is A♭ minor (pitch class 8).
        private val MINOR_PITCH = intArrayOf(8, 3, 10, 5, 0, 7, 2, 9, 4, 11, 6, 1)
        private val MAJOR_PITCH = intArrayOf(11, 6, 1, 8, 3, 10, 5, 0, 7, 2, 9, 4)

        private val CAMELOT = Regex("""^\s*(\d{1,2})\s*([AB])\s*$""", RegexOption.IGNORE_CASE)
        private val MUSICAL =
            Regex("""^\s*([A-G])\s*([#♯b♭]?)\s*(maj|major|min|minor|m)?\s*$""", RegexOption.IGNORE_CASE)

        /** Slot for a pitch class (0 = C … 11 = B) in the requested mode. */
        fun ofPitchClass(pitchClass: Int, minor: Boolean): CamelotKey {
            val index = ((pitchClass % 12) + 12) % 12
            val wheel = if (minor) MINOR_PITCH else MAJOR_PITCH
            val slot = wheel.indexOf(index) + 1
            return CamelotKey(slot, if (minor) "A" else "B")
        }

        /**
         * Reads either notation into a slot: `8A`/`8a` from DJ tools, `Am`/`A min`/`C`/`Cmaj`
         * from track metadata. Anything else is null — an unreadable key is unknown, never a
         * guess, because a wrong slot silently poisons every suggestion built on it.
         */
        fun parse(text: String?): CamelotKey? {
            val raw = text?.trim().orEmpty()
            if (raw.isEmpty()) return null

            CAMELOT.matchEntire(raw)?.let { match ->
                val number = match.groupValues[1].toIntOrNull() ?: return null
                if (number !in 1..12) return null
                return CamelotKey(number, match.groupValues[2].uppercase())
            }

            val match = MUSICAL.matchEntire(raw) ?: return null
            val pitchClass = pitchClassOf(match.groupValues[1], match.groupValues[2]) ?: return null
            val mode = match.groupValues[3].lowercase()
            return ofPitchClass(pitchClass, minor = mode == "m" || mode == "min" || mode == "minor")
        }

        private fun pitchClassOf(letter: String, accidental: String): Int? {
            val base = when (letter.uppercase()) {
                "C" -> 0
                "D" -> 2
                "E" -> 4
                "F" -> 5
                "G" -> 7
                "A" -> 9
                "B" -> 11
                else -> return null
            }
            val shift = when (accidental) {
                "#", "♯" -> 1
                "b", "♭" -> -1
                else -> 0
            }
            return ((base + shift) % 12 + 12) % 12
        }
    }
}

enum class HarmonicRelation(val label: String) {
    SAME("same"),
    RELATIVE("relative"),
    ADJACENT("adjacent"),
    NONE("clash");
}

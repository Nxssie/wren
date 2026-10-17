package mix

/**
 * One row of the discover list: a candidate from the search, with its [affinity] to the anchor when
 * one could be computed, and `null` when the candidate has no tempo or key yet.
 */
data class DiscoverRow(val track: MixTrack, val affinity: Affinity?)

/**
 * What the discover pane offers for one search: the candidates that fit the anchor under the
 * filters ([ranked]), then the ones that could not be scored ([unranked]).
 *
 * Both lists are offered to the user. A set has to be fillable before anything in it can be ranked,
 * and a fresh search result has no tempo or key until it is analysed, so a candidate the engine
 * cannot place is still a candidate the user can add.
 */
data class Discovery(
    val ranked: List<DiscoverRow> = emptyList(),
    val unranked: List<DiscoverRow> = emptyList()
) {
    /** Every row to render, ranked first. */
    val all: List<DiscoverRow> get() = ranked + unranked

    val isEmpty: Boolean get() = ranked.isEmpty() && unranked.isEmpty()
}

object MixDiscovery {

    /**
     * Resolves [candidates] against [anchor], or against nothing when the set is still empty and
     * there is no anchor to rank from.
     *
     * The anchor itself is never offered, and the same candidate arriving from two searches is one
     * row, matching the identity [MixStore.addTrack] uses.
     */
    fun of(
        anchor: MixTrack?,
        candidates: List<MixTrack>,
        filters: MixFilters = MixFilters()
    ): Discovery {
        val pool = candidates.distinctBy { it.id }.filter { anchor == null || it.id != anchor.id }
        if (anchor == null) return Discovery(unranked = pool.map { DiscoverRow(it, affinity = null) })

        val ranking = MixSuggestions.rank(anchor, pool, filters)
        return Discovery(
            ranked = ranking.suggestions.map { DiscoverRow(it.track, it.affinity) },
            unranked = ranking.unranked.map { DiscoverRow(it, affinity = null) }
        )
    }
}

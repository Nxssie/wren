package api

import util.Log
import util.runCatchingExceptCancellation

/**
 * One page of a listing, with the cursor for the next one — `null` on the last page.
 *
 * Both platforms page the same way underneath: YouTube hands back a `nextPageToken` and
 * SoundCloud a `next_href` URL, and neither means anything to the caller, so the cursor is opaque
 * and travels back unread.
 */
data class Page<T>(val items: List<T>, val next: String? = null)

/**
 * Walks every page of a listing. A page that fails is fatal when nothing has arrived yet — an
 * empty result the caller cannot tell from the truth — and merely logged once something has.
 *
 * Cancellation is not a failure here: it propagates, so leaving a screen mid-walk neither caches
 * a half list nor reports an error.
 */
internal suspend fun <T> allPages(tag: String, page: suspend (cursor: String?) -> Page<T>): List<T> {
    val items = mutableListOf<T>()
    var cursor: String? = null
    var pages = 0
    while (true) {
        val next = runCatchingExceptCancellation { page(cursor) }.getOrElse { failure ->
            if (items.isEmpty()) throw failure
            Log.w(tag, "listing stopped early after $pages page(s): ${failure.message}")
            return items
        }
        items += next.items
        cursor = next.next ?: return items
        if (++pages >= MAX_PAGES) {
            Log.w(tag, "stopped listing after $MAX_PAGES pages")
            return items
        }
    }
}

/** Bounds a runaway cursor: 100 pages is 5.000 items, past any realistic library. */
private const val MAX_PAGES = 100

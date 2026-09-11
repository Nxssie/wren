package api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import util.Log
import util.TtlCache

/**
 * A listing as it arrives: the cached copy in one emission when it is still fresh, otherwise one
 * emission per page so a screen fills while the rest is still being walked.
 *
 * The finished list is stored, which is what makes the next visit instant — and it is stored only
 * once every page has arrived, so a cancelled walk leaves nothing behind and the next attempt
 * starts clean.
 */
internal fun <T> pagedFlow(
    tag: String,
    cache: TtlCache<String, List<T>>,
    key: String,
    page: suspend (cursor: String?) -> Page<T>,
): Flow<List<T>> = flow {
    cache.peek(key)?.let { cached ->
        emit(cached)
        return@flow
    }

    val accumulated = mutableListOf<T>()
    var cursor: String? = null
    var pages = 0
    while (true) {
        val next = page(cursor)
        accumulated += next.items
        emit(accumulated.toList())

        cursor = next.next ?: break
        if (++pages >= MAX_PAGES) {
            Log.w(tag, "listing stopped after $MAX_PAGES pages")
            break
        }
    }
    cache.put(key, accumulated.toList())
}

/** Bounds a runaway cursor: 100 pages is 5.000 items, past any realistic library. */
private const val MAX_PAGES = 100

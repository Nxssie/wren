package util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A short-lived cache for values that cost many round trips to build and are read again every time
 * a screen is re-entered — the library lists, which are rebuilt whenever a tab changes.
 *
 * The load runs while the lock is held, on purpose: when the background warm-up and a screen ask
 * for the same list at the same time, the second caller waits and gets the same value instead of
 * starting a second walk over every page.
 *
 * Entries are also evicted oldest-first past [maxEntries], so a session that opens twenty
 * playlists does not keep all twenty in memory.
 */
class TtlCache<K : Any, V : Any>(private val ttlMs: Long, private val maxEntries: Int = 16) {
    private class Entry<V>(val value: V, val loadedAt: Long)

    private val entries = LinkedHashMap<K, Entry<V>>()
    private val lock = Mutex()

    suspend fun getOrLoad(key: K, load: suspend () -> V): V = lock.withLock {
        val now = System.currentTimeMillis()
        entries[key]?.takeIf { now - it.loadedAt < ttlMs }?.let { return it.value }

        val fresh = load()
        entries[key] = Entry(fresh, System.currentTimeMillis())
        while (entries.size > maxEntries) {
            entries.remove(entries.keys.first())
        }
        fresh
    }

    suspend fun invalidate(key: K) {
        lock.withLock { entries.remove(key) }
    }

    suspend fun clear() {
        lock.withLock { entries.clear() }
    }
}

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
        store(key, fresh)
        fresh
    }

    private fun store(key: K, value: V) {
        entries[key] = Entry(value, System.currentTimeMillis())
        while (entries.size > maxEntries) {
            entries.remove(entries.keys.first())
        }
    }

    /** The cached value only when it is still fresh, without loading anything. */
    suspend fun peek(key: K): V? = lock.withLock {
        entries[key]?.takeIf { System.currentTimeMillis() - it.loadedAt < ttlMs }?.value
    }

    /** Stores a value built somewhere else, e.g. by a page-by-page walk. */
    suspend fun put(key: K, value: V) {
        lock.withLock { store(key, value) }
    }

    suspend fun invalidate(key: K) {
        lock.withLock { entries.remove(key) }
    }

    suspend fun clear() {
        lock.withLock { entries.clear() }
    }
}

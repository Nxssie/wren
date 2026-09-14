package util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger

class TtlCacheTest {

    @Test
    fun `loads once and serves the next calls from memory`() = runBlocking {
        val cache = TtlCache<String, Int>(ttlMs = 10_000)
        val loads = AtomicInteger()

        repeat(3) { cache.getOrLoad("songs") { loads.incrementAndGet() } }

        assertEquals(1, loads.get())
    }

    @Test
    fun `keys are independent`() = runBlocking {
        val cache = TtlCache<String, String>(ttlMs = 10_000)

        assertEquals("a", cache.getOrLoad("one") { "a" })
        assertEquals("b", cache.getOrLoad("two") { "b" })
    }

    @Test
    fun `reloads after the ttl`() = runBlocking {
        val cache = TtlCache<String, Int>(ttlMs = 30)
        val loads = AtomicInteger()

        cache.getOrLoad("songs") { loads.incrementAndGet() }
        delay(60)
        cache.getOrLoad("songs") { loads.incrementAndGet() }

        assertEquals(2, loads.get())
    }

    @Test
    fun `concurrent callers share one load`() = runBlocking {
        // The warm-up racing the screen: without this they would walk every page twice.
        val cache = TtlCache<String, Int>(ttlMs = 10_000)
        val loads = AtomicInteger()

        coroutineScope {
            (1..5).map {
                async { cache.getOrLoad("songs") { loads.incrementAndGet().also { delay(20) } } }
            }.awaitAll()
        }

        assertEquals(1, loads.get())
    }

    @Test
    fun `a failed load is not cached`() = runBlocking {
        val cache = TtlCache<String, Int>(ttlMs = 10_000)
        val attempts = AtomicInteger()

        assertThrows<IllegalStateException> {
            runBlocking {
                cache.getOrLoad("songs") {
                    attempts.incrementAndGet()
                    throw IllegalStateException("boom")
                }
            }
        }
        val value = cache.getOrLoad("songs") { attempts.incrementAndGet(); 42 }

        assertEquals(42, value)
        assertEquals(2, attempts.get())
    }

    @Test
    fun `cancellation leaves the cache usable`() = runBlocking {
        val cache = TtlCache<String, Int>(ttlMs = 10_000)

        assertThrows<CancellationException> {
            runBlocking { cache.getOrLoad("songs") { throw CancellationException("left the composition") } }
        }

        assertTrue(cache.getOrLoad("songs") { 7 } == 7)
    }

    @Test
    fun `evicts the oldest past the limit`() = runBlocking {
        val cache = TtlCache<String, Int>(ttlMs = 10_000, maxEntries = 2)
        val loads = AtomicInteger()
        suspend fun load(key: String) = cache.getOrLoad(key) { loads.incrementAndGet() }

        load("a")
        load("b")
        load("c")          // evicts "a"
        load("b")          // still cached
        val afterB = loads.get()
        load("a")          // gone, so it loads again

        assertEquals(3, afterB)
        assertEquals(4, loads.get())
    }

    @Test
    fun `evicts by age, keeping what is younger`() = runBlocking {
        val cache = TtlCache<String, String>(ttlMs = 60_000)
        cache.put("old", "a")
        Thread.sleep(30)
        cache.put("young", "b")

        cache.evictOlderThan(15)

        assertNull(cache.peek("old"))
        assertEquals("b", cache.peek("young"))
    }
}

package api

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import util.TtlCache
import java.util.concurrent.atomic.AtomicInteger

class PagedFlowTest {

    private fun pages(vararg pageItems: List<String>): suspend (String?) -> Page<String> {
        val page = AtomicInteger()
        return { cursor ->
            val index = page.getAndIncrement()
            assertEquals(cursor, if (index == 0) null else "cursor-$index", "cursor follows the previous page")
            val last = index == pageItems.lastIndex
            Page(pageItems[index], if (last) null else "cursor-${index + 1}")
        }
    }

    @Test
    fun `emits the list as each page arrives`() = runBlocking {
        val cache = TtlCache<String, List<String>>(ttlMs = 10_000)

        val emissions = pagedFlow("test", cache, "key", pages(listOf("a", "b"), listOf("c"), listOf("d")))
            .toList()

        assertEquals(listOf(listOf("a", "b"), listOf("a", "b", "c"), listOf("a", "b", "c", "d")), emissions)
    }

    @Test
    fun `stores the finished list for the next visit`() = runBlocking {
        val cache = TtlCache<String, List<String>>(ttlMs = 10_000)
        val page = pages(listOf("a"), listOf("b"))

        pagedFlow("test", cache, "key", page).toList()

        assertEquals(listOf("a", "b"), cache.peek("key"))
    }

    @Test
    fun `serves the cached copy without walking the pages`() = runBlocking {
        val cache = TtlCache<String, List<String>>(ttlMs = 10_000)
        cache.put("key", listOf("cached"))
        val calls = AtomicInteger()

        val emissions = pagedFlow<String>("test", cache, "key") {
            calls.incrementAndGet()
            Page(listOf("network"))
        }.toList()

        assertEquals(listOf(listOf("cached")), emissions)
        assertEquals(0, calls.get())
    }

    @Test
    fun `a page failure reaches the collector`() = runBlocking {
        val cache = TtlCache<String, List<String>>(ttlMs = 10_000)

        assertThrows<IllegalStateException> {
            runBlocking { pagedFlow<String>("test", cache, "key") { throw IllegalStateException("page failed") }.toList() }
        }
        assertNull(cache.peek("key"))
    }

    @Test
    fun `stopping mid-walk stores nothing`() = runBlocking {
        // Leaving the screen cancels the collector; a half list must not become the cached answer.
        val cache = TtlCache<String, List<String>>(ttlMs = 10_000)

        val first = pagedFlow("test", cache, "key", pages(listOf("a"), listOf("b"), listOf("c")))
            .take(1)
            .toList()

        assertEquals(listOf(listOf("a")), first)
        assertNull(cache.peek("key"))
    }

    @Test
    fun `a walk that ends at the page cap keeps what it has`() = runBlocking {
        // A cursor that never ends: bounded rather than an infinite loop.
        val cache = TtlCache<String, List<String>>(ttlMs = 10_000)
        var calls = 0

        val emissions = pagedFlow("test", cache, "key") { _ ->
            Page(listOf("item-${calls++}"), "more")
        }.toList()

        assertTrue(emissions.size > 1)
        assertEquals(calls, emissions.last().size)
    }
}

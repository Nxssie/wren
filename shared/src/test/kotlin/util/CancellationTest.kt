package util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CancellationTest {

    @Test
    fun `a real failure comes back as a failure`() = runBlocking {
        val result = runCatchingExceptCancellation<String> { throw IllegalStateException("boom") }

        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @Test
    fun `cancellation is rethrown instead of reported`() {
        // This is what Compose throws when a screen leaves mid-fetch: reporting it as a failure
        // is how "The coroutine scope left the composition" reached the UI.
        assertThrows<CancellationException> {
            runBlocking {
                runCatchingExceptCancellation<String> {
                    throw CancellationException("The coroutine scope left the composition")
                }
            }
        }
    }

    @Test
    fun `a value passes through untouched`() = runBlocking {
        val result = runCatchingExceptCancellation { 42 }

        assertEquals(42, result.getOrNull())
    }
}

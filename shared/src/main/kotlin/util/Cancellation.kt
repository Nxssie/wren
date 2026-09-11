package util

import kotlinx.coroutines.CancellationException

/**
 * `runCatching` catches [CancellationException] as well, which turns "the screen left while this
 * was in flight" into a failure the caller then treats as a result: an empty library cached as
 * data, or `The coroutine scope left the composition` shown to the user as if it were a network
 * error. Cancellation is not a failure to report — it propagates, and only real failures come
 * back as [Result.failure].
 */
suspend fun <T> runCatchingExceptCancellation(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

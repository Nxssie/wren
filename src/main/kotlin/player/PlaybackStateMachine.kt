package player

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Frame source that the playLoop polls. Used to abstract FFmpegFrameGrabber
 * for unit testing without needing native audio libraries.
 */
interface FrameSource {
    /** Returns a non-null frame if available, null if nothing to read yet or EOF. */
    fun tryGrab(): FrameResult
    /** Returns current timestamp in microseconds, or -1 if unavailable. */
    fun currentTimestampUs(): Long
}

/** Result of a frame grab attempt. */
data class FrameResult(
    val frame: Boolean,       // true = got a frame, false = nothing yet
    val timestamp: Long,      // current timestamp in us
    val exception: Exception? = null
)

/**
 * Encapsulates the EOF detection logic used by the playLoop.
 *
 * The state machine tracks:
 * - consecutive null frames (network hiccups produce temporary nulls)
 * - frames since last successful frame (total silence duration)
 * - position stuck detection (grabber timestamp stops advancing)
 * - exception retry budget (too many exceptions = broken stream)
 *
 * This is extracted from FFmpegPlayer.playLoop so it can be unit-tested
 * without needing FFmpegFrameGrabber or SourceDataLine.
 */
class PlaybackStateMachine {
    enum class State { PLAYING, EOF, BROKEN, STOPPED }

    private val _state = AtomicBoolean(false) // true = playing
    private var eofReached = AtomicBoolean(false)
    private var broken = AtomicBoolean(false)

    // Detection counters
    private var consecutiveNulls = 0
    private var framesSinceLastFrame = 0
    private var lastPositionUs: Long = -1
    private var positionStuck = false
    private var exceptionRetries = 0

    // Thresholds (must match FFmpegPlayer.playLoop)
    private val nullThreshold = 8
    private val framesBetweenNullsThreshold = 30
    private val maxExceptionRetries = 20

    val state: State
        get() = when {
            broken.get() -> State.BROKEN
            eofReached.get() -> State.EOF
            _state.get() -> State.PLAYING
            else -> State.STOPPED
        }

    val isEofReached: Boolean get() = eofReached.get()

    /**
     * Process one iteration of the play loop.
     *
     * @param hasFrame true if a frame was successfully grabbed
     * @param timestampUs current grabber timestamp, or -1 if unavailable
     * @param exception non-null if grabSamples() threw
     * @param isPlaying whether isPlayingInternal is still true
     * @return the current state after processing this iteration
     */
    fun processIteration(
        hasFrame: Boolean,
        timestampUs: Long,
        exception: Exception? = null,
        isPlaying: Boolean = _state.get()
    ): State {
        if (!isPlaying) {
            _state.set(false)
            return State.STOPPED
        }

        if (exception != null) {
            exceptionRetries++
            if (exceptionRetries > maxExceptionRetries) {
                broken.set(true)
                _state.set(false)
                return State.BROKEN
            }
            return State.PLAYING
        }

        if (hasFrame) {
            // Good frame — reset all counters
            consecutiveNulls = 0
            framesSinceLastFrame = 0
            positionStuck = false
            lastPositionUs = timestampUs
            return State.PLAYING
        }

        // Frame is null — network hiccup or EOF
        consecutiveNulls++
        framesSinceLastFrame++

        // Check if position stopped advancing
        if (timestampUs > 0 && timestampUs == lastPositionUs) {
            positionStuck = true
        } else if (timestampUs > 0) {
            lastPositionUs = timestampUs
            positionStuck = false
        }

        // EOF criteria: enough null frames AND (position stuck OR too many frames without data)
        if (consecutiveNulls >= nullThreshold && (positionStuck || framesSinceLastFrame >= framesBetweenNullsThreshold)) {
            eofReached.set(true)
            _state.set(false)
            return State.EOF
        }

        return State.PLAYING
    }

    /**
     * Simulate a period of normal playback: [frameCount] frames with timestamps advancing.
     * Returns the state after all iterations.
     */
    fun simulateNormalPlayback(frameCount: Int, timestampStep: Long = 1000): State {
        var s = State.PLAYING
        for (i in 0 until frameCount) {
            s = processIteration(
                hasFrame = true,
                timestampUs = i * timestampStep,
                isPlaying = true
            )
        }
        return s
    }

    /**
     * Simulate a burst of null frames (network hiccup) of [nullCount], then resume with frames.
     * Returns the state after all iterations.
     */
    fun simulateNetworkHiccup(nullCount: Int, resumeFrames: Int = 5, timestampStep: Long = 1000): State {
        var s = State.PLAYING
        // First, simulate some normal playback so lastPositionUs > 0
        s = simulateNormalPlayback(10, timestampStep)
        require(s == State.PLAYING) { "Expected PLAYING before hiccup, got $s" }

        // Now simulate null frames
        for (i in 0 until nullCount) {
            s = processIteration(
                hasFrame = false,
                timestampUs = 10 * timestampStep, // position stuck at last known
                isPlaying = true
            )
        }

        // Resume with real frames
        for (i in 0 until resumeFrames) {
            s = processIteration(
                hasFrame = true,
                timestampUs = (10 + i) * timestampStep,
                isPlaying = true
            )
        }
        return s
    }

    /**
     * Simulate EOF: play normally, then produce null frames until EOF threshold is hit.
     */
    fun simulateEOF(): State {
        var s = State.PLAYING
        // Normal playback
        s = simulateNormalPlayback(20, timestampStep = 1000)
        require(s == State.PLAYING) { "Expected PLAYING before EOF, got $s" }

        // Position is at 20000us. Now produce null frames with stuck position.
        for (i in 0 until nullThreshold + 2) {
            s = processIteration(
                hasFrame = false,
                timestampUs = 20000, // stuck
                isPlaying = true
            )
        }
        return s
    }

    /**
     * Simulate a broken stream: normal playback, then exceptions until retry budget exhausted.
     */
    fun simulateBrokenStream(): State {
        var s = State.PLAYING
        // Normal playback
        s = simulateNormalPlayback(10, timestampStep = 1000)
        require(s == State.PLAYING) { "Expected PLAYING before break, got $s" }

        // Exceptions
        for (i in 0 until maxExceptionRetries + 2) {
            s = processIteration(
                hasFrame = false,
                timestampUs = -1,
                exception = RuntimeException("Network error"),
                isPlaying = true
            )
        }
        return s
    }

    /**
     * Simulate a short null burst that's within tolerance (should NOT trigger EOF).
     */
    fun simulateShortNullBurstUnderThreshold(): State {
        var s = State.PLAYING
        s = simulateNormalPlayback(10, timestampStep = 1000)

        // Send null frames below the threshold AND with position still advancing
        for (i in 0 until nullThreshold - 2) {
            s = processIteration(
                hasFrame = false,
                timestampUs = (10000 + i * 100).toLong(), // position still advancing
                isPlaying = true
            )
        }
        return s
    }

    /**
     * Simulate frames arriving but with long gaps (position stuck between nulls).
     */
    fun simulateLongFrameGaps(): State {
        var s = State.PLAYING
        s = simulateNormalPlayback(10, timestampStep = 1000)

        // Send more null frames than framesBetweenNullsThreshold
        for (i in 0 until framesBetweenNullsThreshold + 5) {
            s = processIteration(
                hasFrame = false,
                timestampUs = 10000, // stuck
                isPlaying = true
            )
        }
        return s
    }

    /**
     * Simulate player being stopped externally (isPlaying = false).
     */
    fun simulateExternalStop(): State {
        var s = State.PLAYING
        s = simulateNormalPlayback(5, timestampStep = 1000)
        // Stop externally
        s = processIteration(
            hasFrame = true,
            timestampUs = 5000,
            isPlaying = false
        )
        return s
    }

    /**
     * Reset the state machine for a new playback session.
     */
    fun reset() {
        _state.set(true)
        eofReached.set(false)
        broken.set(false)
        consecutiveNulls = 0
        framesSinceLastFrame = 0
        lastPositionUs = -1
        positionStuck = false
        exceptionRetries = 0
    }

    /**
     * Simulate the timing behavior of the playLoop — null frames with exponential backoff delays.
     * Returns true if EOF would NOT be triggered by [nullCount] null frames within [maxIterations] total iterations.
     */
    fun simulateNullFrameBackoff(nullCount: Int, maxIterations: Int = 100): Boolean {
        var s = State.PLAYING
        var iterations = 0
        s = simulateNormalPlayback(10, timestampStep = 1000)

        while (s == State.PLAYING && iterations < maxIterations) {
            s = processIteration(
                hasFrame = false,
                timestampUs = 10000, // stuck
                isPlaying = true
            )
            iterations++
        }
        return s != State.EOF
    }
}

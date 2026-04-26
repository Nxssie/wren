package player

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("PlaybackStateMachine")
class PlaybackStateMachineTest {

    private lateinit var sm: PlaybackStateMachine

    @BeforeEach
    fun setUp() {
        sm = PlaybackStateMachine()
    }

    @Nested
    @DisplayName("Normal playback")
    inner class NormalPlaybackTest {

        @Test
        @DisplayName("should stay PLAYING during normal frame stream")
        fun `should stay playing during normal playback`() {
            val state = sm.simulateNormalPlayback(100, timestampStep = 1000)
            assertEquals(PlaybackStateMachine.State.PLAYING, state)
        }

        @Test
        @DisplayName("should handle short playback session")
        fun `should handle short session`() {
            val state = sm.simulateNormalPlayback(3, timestampStep = 1000)
            assertEquals(PlaybackStateMachine.State.PLAYING, state)
        }

        @Test
        @DisplayName("should handle external stop")
        fun `should stop on external stop`() {
            val state = sm.simulateExternalStop()
            assertEquals(PlaybackStateMachine.State.STOPPED, state)
        }
    }

    @Nested
    @DisplayName("Network hiccups")
    inner class NetworkHiccupTest {

        @Test
        @DisplayName("should NOT trigger EOF on short null burst below threshold")
        fun `should not eof on short null burst 1`() {
            val state = sm.simulateShortNullBurstUnderThreshold()
            assertEquals(PlaybackStateMachine.State.PLAYING, state)
        }

        @Test
        @DisplayName("should survive 2 null frames and resume normally")
        fun `should survive 2 null frames`() {
            val state = sm.simulateNetworkHiccup(nullCount = 2, resumeFrames = 5)
            assertEquals(PlaybackStateMachine.State.PLAYING, state)
        }

        @Test
        @DisplayName("should survive 4 null frames and resume normally")
        fun `should survive 4 null frames`() {
            val state = sm.simulateNetworkHiccup(nullCount = 4, resumeFrames = 5)
            assertEquals(PlaybackStateMachine.State.PLAYING, state)
        }

        @Test
        @DisplayName("should survive 6 null frames and resume normally")
        fun `should survive 6 null frames`() {
            val state = sm.simulateNetworkHiccup(nullCount = 6, resumeFrames = 5)
            assertEquals(PlaybackStateMachine.State.PLAYING, state)
        }

        @Test
        @DisplayName("should survive 8 null frames if position is still advancing")
        fun `should survive 8 null frames with advancing position`() {
            // This simulates a longer network hiccup where position keeps advancing
            var state = PlaybackStateMachine.State.PLAYING
            sm.simulateNormalPlayback(10, timestampStep = 1000)
            for (i in 0 until 8) {
                state = sm.processIteration(
                    hasFrame = false,
                    timestampUs = (10000 + i * 200).toLong(), // position advancing
                    isPlaying = true
                )
            }
            assertEquals(PlaybackStateMachine.State.PLAYING, state)
        }

        @Test
        @DisplayName("should trigger EOF when null burst exceeds threshold AND position stuck")
        fun `should eof on long null burst with stuck position`() {
            var state = PlaybackStateMachine.State.PLAYING
            sm.simulateNormalPlayback(10, timestampStep = 1000)

            // Send null frames with stuck position — exceeds threshold
            for (i in 0 until 13) {
                state = sm.processIteration(
                    hasFrame = false,
                    timestampUs = 10000, // stuck
                    isPlaying = true
                )
            }
            assertEquals(PlaybackStateMachine.State.EOF, state)
        }

        @Test
        @DisplayName("should trigger EOF via framesBetweenNullsThreshold even without exact null count")
        fun `should eof on too many frames between nulls`() {
            val state = sm.simulateLongFrameGaps()
            assertEquals(PlaybackStateMachine.State.EOF, state)
        }
    }

    @Nested
    @DisplayName("EOF detection")
    inner class EOFTest {

        @Test
        @DisplayName("should detect genuine EOF with stuck position")
        fun `should detect genuine eof`() {
            val state = sm.simulateEOF()
            assertEquals(PlaybackStateMachine.State.EOF, state)
        }

        @Test
        @DisplayName("should set eofReached flag on EOF")
        fun `should set eof flag`() {
            sm.simulateEOF()
            assertTrue(sm.isEofReached)
        }

        @Test
        @DisplayName("should NOT set eofReached flag during normal playback")
        fun `should not set eof flag during normal playback`() {
            sm.simulateNormalPlayback(50, timestampStep = 1000)
            assertFalse(sm.isEofReached)
        }

        @Test
        @DisplayName("should NOT set eofReached flag after network hiccup")
        fun `should not set eof flag after hiccup`() {
            sm.simulateNetworkHiccup(nullCount = 4, resumeFrames = 5)
            assertFalse(sm.isEofReached)
        }

        @Test
        @DisplayName("should NOT set eofReached flag when stream is broken")
        fun `should not set eof flag on broken stream`() {
            sm.simulateBrokenStream()
            assertFalse(sm.isEofReached)
        }
    }

    @Nested
    @DisplayName("Broken stream detection")
    inner class BrokenStreamTest {

        @Test
        @DisplayName("should detect broken stream after too many exceptions")
        fun `should detect broken stream`() {
            val state = sm.simulateBrokenStream()
            assertEquals(PlaybackStateMachine.State.BROKEN, state)
        }

        @Test
        @DisplayName("should NOT trigger EOF on broken stream")
        fun `should not eof on broken stream`() {
            sm.simulateBrokenStream()
            assertFalse(sm.isEofReached)
        }

        @Test
        @DisplayName("should survive few exceptions")
        fun `should survive few exceptions`() {
            var state = PlaybackStateMachine.State.PLAYING
            sm.simulateNormalPlayback(10, timestampStep = 1000)

            for (i in 0 until 5) {
                state = sm.processIteration(
                    hasFrame = false,
                    timestampUs = -1,
                    exception = RuntimeException("temp error"),
                    isPlaying = true
                )
            }
            assertEquals(PlaybackStateMachine.State.PLAYING, state)
        }

        @Test
        @DisplayName("should recover from exceptions after frames resume")
        fun `should recover from exceptions`() {
            var state = PlaybackStateMachine.State.PLAYING
            sm.simulateNormalPlayback(10, timestampStep = 1000)

            // A few exceptions
            for (i in 0 until 3) {
                state = sm.processIteration(
                    hasFrame = false,
                    timestampUs = -1,
                    exception = RuntimeException("temp error"),
                    isPlaying = true
                )
            }
            assertEquals(PlaybackStateMachine.State.PLAYING, state)

            // Then frames resume — should reset exception counter
            state = sm.processIteration(
                hasFrame = true,
                timestampUs = 10000,
                isPlaying = true
            )
            assertEquals(PlaybackStateMachine.State.PLAYING, state)
        }
    }

    @Nested
    @DisplayName("State transitions")
    inner class StateTransitionsTest {

        @Test
        @DisplayName("should transition PLAYING -> EOF -> STOPPED on new load")
        fun `should playin eof stopped`() {
            sm.simulateEOF()
            assertEquals(PlaybackStateMachine.State.EOF, sm.state)

            // Simulate new load calling stop
            val state = sm.processIteration(
                hasFrame = false,
                timestampUs = -1,
                isPlaying = false
            )
            assertEquals(PlaybackStateMachine.State.STOPPED, state)
        }

        @Test
        @DisplayName("should reset state for new session")
        fun `should reset state`() {
            sm.simulateEOF()
            assertEquals(PlaybackStateMachine.State.EOF, sm.state)

            sm.reset()
            assertFalse(sm.isEofReached)
            assertEquals(PlaybackStateMachine.State.PLAYING, sm.state)

            // Should be able to play normally after reset
            val state = sm.simulateNormalPlayback(20, timestampStep = 1000)
            assertEquals(PlaybackStateMachine.State.PLAYING, state)
        }

        @Test
        @DisplayName("should handle rapid play/pause/stop cycles")
        fun `should handle rapid cycles`() {
            var state = PlaybackStateMachine.State.PLAYING

            // Play
            state = sm.processIteration(true, 1000, isPlaying = true)
            assertEquals(PlaybackStateMachine.State.PLAYING, state)

            // Stop
            state = sm.processIteration(false, -1, isPlaying = false)
            assertEquals(PlaybackStateMachine.State.STOPPED, state)

            // Play again
            state = sm.processIteration(true, 1000, isPlaying = true)
            assertEquals(PlaybackStateMachine.State.PLAYING, state)

            // EOF
            sm.simulateNormalPlayback(10, timestampStep = 1000)
            for (i in 0 until 10) {
                state = sm.processIteration(false, 10000, isPlaying = true)
            }
            assertEquals(PlaybackStateMachine.State.EOF, state)
        }
    }

    @Nested
    @DisplayName("Edge cases")
    inner class EdgeCasesTest {

        @Test
        @DisplayName("should handle null timestamp gracefully")
        fun `should handle null timestamp`() {
            var state = PlaybackStateMachine.State.PLAYING
            sm.simulateNormalPlayback(5, timestampStep = 1000)

            // Timestamp of -1 (unavailable)
            state = sm.processIteration(
                hasFrame = false,
                timestampUs = -1,
                isPlaying = true
            )
            assertEquals(PlaybackStateMachine.State.PLAYING, state)
        }

        @Test
        @DisplayName("should handle zero timestamp")
        fun `should handle zero timestamp`() {
            var state = sm.processIteration(
                hasFrame = false,
                timestampUs = 0,
                isPlaying = true
            )
            assertEquals(PlaybackStateMachine.State.PLAYING, state)
        }

        @Test
        @DisplayName("should handle mixed exceptions and null frames before breaking")
        fun `should handle mixed exceptions and nulls`() {
            var state = PlaybackStateMachine.State.PLAYING
            sm.simulateNormalPlayback(10, timestampStep = 1000)

            // Send 15 exceptions — below the maxExceptionRetries (20) threshold
            for (i in 0 until 15) {
                state = sm.processIteration(
                    hasFrame = false,
                    timestampUs = -1,
                    exception = RuntimeException("error $i"),
                    isPlaying = true
                )
            }
            assertEquals(PlaybackStateMachine.State.PLAYING, state)

            // Send 6 more exceptions to exceed the threshold (15 + 6 = 21 > 20)
            for (i in 0 until 6) {
                state = sm.processIteration(
                    hasFrame = false,
                    timestampUs = -1,
                    exception = RuntimeException("error $i"),
                    isPlaying = true
                )
            }
            assertEquals(PlaybackStateMachine.State.BROKEN, state)
        }

        @Test
        @DisplayName("should handle very long timestamp values")
        fun `should handle large timestamps`() {
            var state = PlaybackStateMachine.State.PLAYING
            state = sm.processIteration(true, Long.MAX_VALUE / 2, isPlaying = true)
            assertEquals(PlaybackStateMachine.State.PLAYING, state)
        }
    }
}

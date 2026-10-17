package analysis

import api.resolveStreamUrl
import kotlinx.coroutines.runBlocking
import mix.BpmKey
import mix.BpmKeyProvider
import mix.MixTrack
import mix.ProviderUnavailableException
import mix.analysis.LocalAnalysis
import util.Log

/**
 * Tempo and key estimated from the track's own audio, which is the only source that needs no API
 * key and never runs out of quota.
 *
 * It answers for a [MixTrack] rather than for an artist and a title on purpose: the answer lives in
 * the samples, not in the metadata. The stream URL is resolved through the same path the player
 * uses, so a track that cannot be played cannot be analysed either.
 */
class LocalAnalysisProvider(
    private val decode: (String) -> FloatArray? = { url -> AudioSamples.decode(url) },
    private val resolve: (String) -> String? = { key -> runBlocking { resolveStreamUrl(key) } }
) : BpmKeyProvider {

    override val name: String = ANALYSIS_PROVIDER

    /** Nothing to configure: this provider is the one that always works. */
    override val isConfigured: Boolean = true

    /** No audio, no answer — a metadata-only call cannot be satisfied by analysing nothing. */
    override fun lookup(artist: String, title: String): BpmKey? = null

    override fun lookup(track: MixTrack): BpmKey? {
        // No stream URL or no samples is not an answer about the track: it is the pipeline failing,
        // so it throws instead of returning null and the caller does not remember it as a miss.
        val streamUrl = resolve(track.videoId)
            ?: throw ProviderUnavailableException("no stream url for ${track.id}")
        val samples = decode(streamUrl)
            ?: throw ProviderUnavailableException("no audio decoded for ${track.id}")

        val answer = LocalAnalysis.bpmKey(samples, AudioSamples.TARGET_SAMPLE_RATE)
        if (answer == null) Log.w("LocalAnalysis", "no tempo or key in ${track.id}")
        return answer
    }

    companion object {
        /** Kept in one place so the UI can say where an answer came from. */
        const val ANALYSIS_PROVIDER = "local"
    }
}

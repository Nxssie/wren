package api

import auth.AuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SearchResult(
    val videoId: String,
    val title: String,
    val artist: String,
    val duration: String,
    val thumbnailUrl: String
) {
    val url: String get() = "https://music.youtube.com/watch?v=$videoId"
}

object YoutubeMusic {
    suspend fun search(query: String, limit: Int = 20): List<SearchResult> {
        if (AuthManager.isAuthenticated) AuthManager.ensureValidToken()
        return searchYouTubeMusic(query, limit)
    }
}

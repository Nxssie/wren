package api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Response shapes copied from live `youtubei/v1/browse` calls: `FEmusic_home` for the shelves,
 * an album and a playlist for the collections. Only the fields the parser reads are kept.
 */
class YtMusicBrowseTest {

    private fun obj(body: String) = Json.parseToJsonElement(body).jsonObject

    private val home = obj(
        """
        {"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":
          {"sectionListRenderer":{"contents":[
            {"musicCarouselShelfRenderer":{
              "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Quick picks"}]}}},
              "contents":[{"musicResponsiveListItemRenderer":{
                "playlistItemData":{"videoId":"DYuhnVSOzwE"},
                "flexColumns":[
                  {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Eres Mía"}]}}},
                  {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                    {"text":"Romeo Santos","navigationEndpoint":{"browseEndpoint":{"browseId":"UCpB_98tUTs3zSiOxZuGPnOA"}}},
                    {"text":" • "},{"text":"2.1B plays"}]}}}
                ],
                "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[
                  {"url":"small","width":60},{"url":"big","width":226}]}}}
              }}]}},
            {"musicCarouselShelfRenderer":{
              "header":{"musicCarouselShelfBasicHeaderRenderer":{
                "title":{"runs":[{"text":"New releases"}]},
                "subtitle":{"runs":[{"text":"Popular right now"}]}}},
              "contents":[{"musicTwoRowItemRenderer":{
                "title":{"runs":[{"text":"Bee Gees Blanket the World"}]},
                "subtitle":{"runs":[{"text":"Album"},{"text":" • "},{"text":"Bee Gees"}]},
                "navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_NMxPCGyrsL3","params":"ggMvGilO"}},
                "thumbnailRenderer":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"card"}]}}}
              }}]}},
            {"musicCarouselShelfRenderer":{
              "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Moods & genres"}]}}},
              "contents":[{"musicNavigationButtonRenderer":{
                "buttonText":{"runs":[{"text":"Chill"}]},
                "clickCommand":{"browseEndpoint":{
                  "browseId":"FEmusic_moods_and_genres_category","params":"ggMPOg1u"}}
              }}]}},
            {"musicCarouselShelfRenderer":{
              "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Unopenable"}]}}},
              "contents":[{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"no target"}]}}}]}}
          ]}}}}]}}}
        """.trimIndent()
    )

    @Test
    fun `parses each shelf kind, in order, dropping ones with nothing openable`() {
        val shelves = parseShelves(home)

        assertEquals(listOf("quick picks", "new releases", "moods & genres"), shelves.map { it.title })
        assertEquals("Popular right now", shelves[1].caption)
        assertNull(shelves[0].caption)
    }

    @Test
    fun `a track shelf keeps the fields search results carry`() {
        val track = parseShelves(home)[0].tracks.single()

        assertEquals("DYuhnVSOzwE", track.videoId)
        assertEquals("Eres Mía", track.title)
        assertEquals("Romeo Santos", track.artist)
        assertEquals("UCpB_98tUTs3zSiOxZuGPnOA", track.artistId)
        assertEquals("", track.duration)
        assertEquals("big", track.thumbnailUrl)
        assertTrue(parseShelves(home)[0].cards.isEmpty())
    }

    @Test
    fun `an album card carries its params so the endpoint can be called back`() {
        val card = parseShelves(home)[1].cards.single()

        assertEquals("MPREb_NMxPCGyrsL3|ggMvGilO", card.id)
        assertEquals("Bee Gees Blanket the World", card.title)
        assertEquals("Album • Bee Gees", card.subtitle)
        assertEquals("card", card.artworkUrl)
    }

    @Test
    fun `a moods button is a card too, with no artwork`() {
        val card = parseShelves(home)[2].cards.single()

        assertEquals("FEmusic_moods_and_genres_category|ggMPOg1u", card.id)
        assertEquals("Chill", card.title)
        assertNull(card.artworkUrl)
    }

    private val playlist = obj(
        """
        {"contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":{"sectionListRenderer":{"contents":[
          {"musicPlaylistShelfRenderer":{"contents":[
            {"musicResponsiveListItemRenderer":{
              "playlistItemData":{"videoId":"rHpAXvqDzYY"},
              "flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Track A"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist A"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{}}}
              ]}}
          ],"continuations":[{"nextContinuationData":{"continuation":"TOKEN123"}}]}}
        ]}}}}}
        """.trimIndent()
    )

    @Test
    fun `collection tracks are found wherever the renderer sits`() {
        val track = parseCollectionTracks(playlist).single()

        assertEquals("rHpAXvqDzYY", track.videoId)
        assertEquals("Track A", track.title)
        assertEquals("Artist A", track.artist)
        assertEquals("", track.duration)
        assertEquals("https://i.ytimg.com/vi/rHpAXvqDzYY/mqdefault.jpg", track.thumbnailUrl)
    }

    @Test
    fun `a continuation is offered when the response has one`() {
        assertEquals("TOKEN123", continuationTokenOf(playlist))
    }

    @Test
    fun `a track with no videoId is not a track`() {
        val unusable = obj(
            """{"musicResponsiveListItemRenderer":{"flexColumns":[
                 {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"orphan"}]}}}]}}"""
        )

        assertTrue(parseCollectionTracks(unusable).isEmpty())
        assertNull(continuationTokenOf(unusable))
        assertTrue(parseShelves(unusable).isEmpty())
    }
}

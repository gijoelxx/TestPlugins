package com.example

import android.util.Base64
import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.Cache
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import java.io.IOException

@CloudstreamPlugin
class SuperStreamPlugin : Plugin() {
    override fun load(context: Context) {
    try {
        registerMainAPI(SuperStreamMovie2k())
    } catch (e: Exception) {
        // Protokollierung des Fehlers oder Anzeige einer Fehlermeldung
        e.printStackTrace() // Zum Beispiel: Protokollierung
    }
}

class SuperStreamMovie2k : MainAPI() {
    override var name = "Movie2K"
    override val mainUrl = "https://www2.movie2k.ch"
    override val hasChromecastSupport = true
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val baseApiUrl = "https://api.movie2k.ch/data/browse/"
    private val cache = Cache() // Caching-Mechanismus
    private val userFavorites = mutableListOf<SearchResponse>() // Liste der Favoriten
    private val coroutineScope = CoroutineScope(Dispatchers.IO) // CoroutineScope für Hintergrundoperationen

    // API-Anfrage für Kategorien, Filter, usw.
    private suspend fun queryApi(
        genre: String = "",
        orderBy: String = "trending",
        page: Int = 1,
        limit: Int = 20,
        keyword: String = "",
        year: String = "",
        rating: String = "",
        language: String = "2"
    ): NiceResponse {
        val url = "$baseApiUrl?lang=$language&genre=$genre&order_by=$orderBy&page=$page&limit=$limit&year=$year&rating=$rating&keyword=$keyword"
        return app.get(url)
    }

    // MainPage: Kategorien wie Trending, Releases, Action, usw.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val limit = 20

        val actionMovies = cache.getOrFetch("ActionPage_$page") {
            queryApi(genre = "Action", page = page, limit = limit).parsed<DataJSON>()
        }

        val trendingMovies = cache.getOrFetch("TrendingPage_$page") {
            queryApi(orderBy = "trending", page = page, limit = limit).parsed<DataJSON>()
        }

        val newReleases = cache.getOrFetch("ReleasesPage_$page") {
            queryApi(orderBy = "releases", page = page, limit = limit).parsed<DataJSON>()
        }

        val pages = listOf(
            HomePageList("Action Filme", actionMovies.toSearchResponseList()),
            HomePageList("Filme Im Trend", trendingMovies.toSearchResponseList()),
            HomePageList("Neue Releases", newReleases.toSearchResponseList())
        )

        val hasNextPage = actionMovies.list.size >= limit || trendingMovies.list.size >= limit || newReleases.list.size >= limit
        return HomePageResponse(pages, hasNext = hasNextPage)
    }

    // Methode für die Suche
  override suspend fun search(query: String): List<SearchResponse> {
    return try {
        val searchResult = queryApi(keyword = query, limit = 20).parsed<DataJSON>()
        searchResult.toSearchResponseList()
    } catch (e: Exception) {
        // Log error or return an empty list
        emptyList()
    }
}

    // Trailer-Unterstützung und Favoriten-Support
    data class MovieData(
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("year") val year: Int?,
        @JsonProperty("genre") val genre: String?,
        @JsonProperty("trailer") val trailer: String?,
        @JsonProperty("subtitles") val subtitles: List<SubtitleData>?
    ) {
        fun toSearchResponse(): SearchResponse {
            return MovieSearchResponse(
                title = this.title,
                url = "/watch/${this.id}",
                posterUrl = this.poster,
                year = this.year,
                genre = listOfNotNull(this.genre)
            ).apply {
                addTrailer(this@MovieData.trailer)
                this@MovieSearchResponse.addFavoriteOption()
            }
        }
    }

    fun DataJSON.toSearchResponseList(): List<SearchResponse> {
        return this.list.mapNotNull { it.toSearchResponse() }
    }

    // Caching-Logik und Methoden
    fun <T> Cache.getOrFetch(key: String, fetch: suspend () -> T): T {
        return get(key) ?: run {
            val value = fetch()
            set(key, value)
            value
        }
    }

    data class SubtitleData(
        @JsonProperty("lang") val lang: String,
        @JsonProperty("url") val url: String
    )

    fun SearchResponse.addFavoriteOption() {
        this.apply {
            this.addAction(
                ActionData("Add to Favorites", "Add to Favorites") {
                    synchronized(userFavorites) {
                        userFavorites.add(this)
                    }
                }
            )
        }
    }
    
    // Beispielmethoden zur Linkbeschaffung (JSoup-Nutzung)
    private fun extractVideoLink(url: String): List<VideoLink> {
        return when {
            url.matches(Regex("https://dood\\.(re|li|to)/d/([a-zA-Z0-9]+)")) -> fetchLinks(url)
            url.matches(Regex("https://(streamtape\\.(net|to|xyz|site|online)|strcloud\\.link|shavetape\\.cash|strtapeadblock\\.me|scloud\\.online|tapeblocker)/e/([a-zA-Z0-9]+)")) -> fetchLinks(url)
            url.matches(Regex("https://voe\\.sx/e/([a-zA-Z0-9]+)")) -> fetchLinks(url)
            url.matches(Regex("https://vinovo\\.to/e/([a-zA-Z0-9]+)")) -> fetchLinks(url)
            url.matches(Regex("https://filemoon\\.(sx|to)/e/([a-zA-Z0-9]+)")) -> fetchLinks(url)
            url.matches(Regex("https://mixdrop\\.(co|to|ag)/e/([a-zA-Z0-9]+)")) -> fetchLinks(url)
            url.matches(Regex("https://dropload\\.(io|to)/e/([a-zA-Z0-9]+)")) -> fetchLinks(url)
            url.matches(Regex("https://supervideo\\.cc/embed-([a-zA-Z0-9]+)\\.html")) -> fetchLinks(url)
            url.matches(Regex("https://swiftload\\.io/e/([a-zA-Z0-9]+)")) -> fetchLinks(url)
            else -> emptyList() // Rückgabe einer leeren Liste, wenn kein Hoster erkannt wurde
        }
    }

    private fun fetchLinks(url: String): List<VideoLink> {
        return try {
            val document = Jsoup.connect(url).get()
            document.select("video, source").mapNotNull { element ->
                val videoSrc = element.attr("src").takeIf { it.isNotEmpty() }
                videoSrc?.let {
                    VideoLink(
                        hosterName = determineHosterName(it),
                        url = it,
                        quality = determineQuality(it)
                    )
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
            emptyList() // Im Fehlerfall eine leere Liste zurückgeben
        }
    }

    private fun determineHosterName(link: String): String {
        return when {
            link.contains("dood.") -> "Dood"
            link.contains("streamtape.") -> "Streamtape"
            link.contains("voe.") -> "Voe"
            link.contains("vinovo.") -> "Vinovo"
            link.contains("filemoon.") -> "Filemoon"
            link.contains("mixdrop.") -> "Mixdrop"
            link.contains("dropload.") -> "Dropload"
            link.contains("supervideo.") -> "Supervideo"
            link.contains("swiftload.") -> "Swiftload"
            else -> "Unbekannt"
        }
    }

    private fun determineQuality(link: String): String {
        return when {
            link.contains("4k", ignoreCase = true) -> "UHD (4K)"
            link.contains("1080p") -> "Full HD (1080p)"
            link.contains("720p") -> "HD (720p)"
            link.contains("sd", ignoreCase = true) -> "SD"
            else -> "Standard" 
        }
    }

    // Datenklassen für VideoLink und DataJSON
    data class VideoLink(
        val hosterName: String,
        val url: String,
        val quality: String
    )

    data class DataJSON(
        @JsonProperty("list") val list: List<MovieData>
    )
   }
 }
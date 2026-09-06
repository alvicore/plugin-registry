package it.vito.home

import android.util.Log
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newHomePageResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Home personale: le righe non arrivano da un sito di streaming ma da AniList,
 * che e' stabile e non si rompe quando un provider cambia dominio o HTML.
 *
 * Le card puntano a anilist.co/anime/<id> (o myanimelist.net/anime/<id> nel ripiego
 * Jikan) e portano apiName = "AnimeUnity": al click CloudStream apre la scheda con il
 * repo di AnimeUnity e il suo SyncRedirector chiama AnimeUnity.getLoadUrl, che risolve
 * l'id nella pagina italiana. Cosi' segnalibri e "Continua a guardare" sono voci
 * AnimeUnity a tutti gli effetti, identiche a quelle aperte dal provider stesso.
 */
class HomeItaProvider : MainAPI() {
    override var name = "Home"
    override var mainUrl = "https://anilist.co"
    override var lang = "it"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // Senza questo CloudStream carica le righe con async, tutte insieme: cinque richieste
    // simultanee superano il limite di Jikan (~3/s) che risponde 429, e la home resta vuota.
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 500L
    override var sequentialMainPageScrollDelay = 500L

    override val mainPage = mainPageOf(
        "season" to "Stagione in corso",
        "trending" to "Di tendenza",
        "airing" to "In onda ora",
        "popular" to "I piu' seguiti",
        "top" to "I meglio votati",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val variables = JSONObject()
            .put("page", page)
            .put("perPage", PER_PAGE)

        when (request.data) {
            "season" -> {
                variables.put("sort", JSONArray(listOf("POPULARITY_DESC")))
                val (season, year) = currentSeason()
                variables.put("season", season).put("seasonYear", year)
                // una card che nessun provider puo' avere non serve in una home per guardare
                variables.put("statusNot", "NOT_YET_RELEASED")
            }

            "trending" -> variables.put("sort", JSONArray(listOf("TRENDING_DESC")))
            "airing" -> variables
                .put("sort", JSONArray(listOf("POPULARITY_DESC")))
                .put("status", "RELEASING")

            "popular" -> variables.put("sort", JSONArray(listOf("POPULARITY_DESC")))
            "top" -> variables.put("sort", JSONArray(listOf("SCORE_DESC")))
            else -> variables.put("sort", JSONArray(listOf("TRENDING_DESC")))
        }

        val page0 = graphQl(PAGE_QUERY, variables)?.optJSONObject("Page")

        // AniList cade piu' spesso di quanto si creda (a settembre 2026 l'API e' stata
        // disabilitata del tutto per giorni). Senza ripiego la home resta vuota anche
        // quando i provider italiani funzionano benissimo.
        if (page0 == null) {
            return newHomePageResponse(request.name, jikanFallback(request.data, page), false)
        }

        var items = cards(page0)

        // Nei primi giorni di un trimestre la stagione nuova ha pochissimi titoli usciti (a
        // volte nessuno): si accoda la stagione precedente, che e' ancora in onda. Va fatto
        // PRIMA del ripiego Jikan, che non sa filtrare i titoli non ancora usciti.
        if (request.data == "season" && page == 1 && items.size < MIN_SEASON_ITEMS) {
            val (season, year) = previousSeason()
            val previous = JSONObject()
                .put("page", 1).put("perPage", PER_PAGE)
                .put("sort", JSONArray(listOf("POPULARITY_DESC")))
                .put("season", season).put("seasonYear", year)
                .put("statusNot", "NOT_YET_RELEASED")
            items = (items + cards(graphQl(PAGE_QUERY, previous)?.optJSONObject("Page"))).distinctBy { it.url }
        }

        if (items.isEmpty()) {
            return newHomePageResponse(request.name, jikanFallback(request.data, page), false)
        }

        val hasNext = page0.optJSONObject("pageInfo")?.optBoolean("hasNextPage") ?: false

        return newHomePageResponse(request.name, items, hasNext)
    }

    private fun cards(page: JSONObject?): List<SearchResponse> {
        val media = page?.optJSONArray("media") ?: return emptyList()
        return (0 until media.length()).mapNotNull { i -> media.optJSONObject(i)?.let(::toCard) }
    }

    /**
     * Ripiego su Jikan (MyAnimeList) quando AniList non risponde.
     * Le card portano un id MAL invece che AniList: entrambi vanno bene per il ponte
     * verso i provider italiani, perche' AnimeUnity espone sia anilist_id sia mal_id.
     */
    private suspend fun jikanFallback(section: String, page: Int): List<SearchResponse> {
        val path = when (section) {
            "season" -> "seasons/now?page=$page"
            "trending" -> "top/anime?filter=airing&page=$page"
            "airing" -> "anime?status=airing&order_by=popularity&page=$page"
            "popular" -> "top/anime?filter=bypopularity&page=$page"
            "top" -> "top/anime?page=$page"
            else -> "top/anime?page=$page"
        }

        val response = runCatching { app.get("$JIKAN_URL/$path", cacheTime = CACHE_MINUTES) }
            .onFailure { Log.w(TAG, "Jikan irraggiungibile per '$section': ${it.message}") }
            .getOrNull() ?: return emptyList()
        if (!response.isSuccessful) {
            // 429 = troppe richieste, 504 = MyAnimeList non risponde a Jikan
            Log.w(TAG, "Jikan ha risposto ${response.code} per '$section'")
            return emptyList()
        }

        val data = runCatching { JSONObject(response.text).optJSONArray("data") }
            .getOrNull() ?: return emptyList()

        return (0 until data.length()).mapNotNull { i ->
            val entry = data.optJSONObject(i) ?: return@mapNotNull null
            val malId = entry.optInt("mal_id", -1).takeIf { it > 0 } ?: return@mapNotNull null
            val title = entry.optNullableString("title_english")
                ?: entry.optNullableString("title")
                ?: return@mapNotNull null
            val poster = entry.optJSONObject("images")
                ?.optJSONObject("jpg")
                ?.optNullableString("large_image_url")

            newAnimeSearchResponse(title, "$MAL_URL/anime/$malId", TvType.Anime, fix = false) {
                this.posterUrl = poster
                this.score = Score.from10(entry.optDouble("score").takeIf { !it.isNaN() })
                this.year = entry.optNullableInt("year")
            }.copy(apiName = ANIME_UNITY)
        }
    }

    /**
     * Serve SOLO all'anteprima in cima alla home: HomeViewModel carica le card in evidenza
     * con il repo della home (questo), senza passare dal SyncRedirector. I click sulle
     * card NON arrivano qui (apiName = "AnimeUnity"), e non devono: il core verifica che
     * repo e LoadResponse abbiano lo stesso apiName (debugAssert in toResultData), e la
     * risposta restituita qui e' di AnimeUnity. Il click sull'anteprima usa apiName e url
     * della LoadResponse, quindi finisce anch'esso su AnimeUnity.
     */
    override suspend fun load(url: String): LoadResponse {
        val (syncName, id) = parseCardUrl(url)
            ?: throw ErrorLoadingException("Card non riconosciuta: $url")
        val animeUnity = APIHolder.getApiFromNameNull(ANIME_UNITY)
            ?: throw ErrorLoadingException("Per aprire i titoli serve il plugin AnimeUnity")
        val target = animeUnity.getLoadUrl(syncName, id.toString())
            ?: throw ErrorLoadingException("Titolo non trovato su AnimeUnity ($syncName $id)")
        return animeUnity.load(target)
            ?: throw ErrorLoadingException("AnimeUnity non ha restituito la pagina")
    }

    private fun parseCardUrl(url: String): Pair<SyncIdName, Int>? {
        Regex("anilist\\.co/anime/(\\d+)").find(url)?.let { return SyncIdName.Anilist to it.groupValues[1].toInt() }
        Regex("myanimelist\\.net/anime/(\\d+)").find(url)?.let { return SyncIdName.MyAnimeList to it.groupValues[1].toInt() }
        return null
    }

    // Niente search(): CloudStream interrogherebbe anche "Home" nella ricerca globale e ne
    // intercalerebbe le card (round-robin, senza deduplica) a quelle di AnimeUnity e
    // AnimeWorld: ogni titolo comparirebbe due o tre volte. Senza override la classe base
    // lancia NotImplementedError e la ricerca scarta questa fonte in modo pulito.

    /** Titolo preferito: inglese se c'e', altrimenti romaji. */
    private fun preferredTitle(title: JSONObject?): String? {
        if (title == null) return null
        return title.optNullableString("english")
            ?: title.optNullableString("romaji")
            ?: title.optNullableString("native")
    }

    private fun toCard(entry: JSONObject): SearchResponse? {
        val id = entry.optInt("id", -1).takeIf { it > 0 } ?: return null
        val title = preferredTitle(entry.optJSONObject("title")) ?: return null
        val poster = entry.optJSONObject("coverImage")?.let {
            it.optNullableString("extraLarge") ?: it.optNullableString("large")
        }

        // Con l'id MAL il ponte risale al titolo da Jikan e solo in seconda battuta da AniList:
        // al click non si dipende da AniList, che va a singhiozzo (timeout e 500 il 6/9/2026).
        // L'id AniList resta per i pochi titoli senza controparte MAL.
        val url = entry.optNullableInt("idMal")?.let { "$MAL_URL/anime/$it" } ?: "$mainUrl/anime/$id"
        // apiName e' in sola lettura negli stub dei plugin: si imposta con copy() della data class
        return newAnimeSearchResponse(title, url, TvType.Anime, fix = false) {
            this.posterUrl = poster
            this.score = Score.from100(entry.optNullableInt("averageScore"))
            this.year = entry.optNullableInt("seasonYear")
        }.copy(apiName = ANIME_UNITY)
    }

    private suspend fun graphQl(query: String, variables: JSONObject): JSONObject? {
        val body = JSONObject()
            .put("query", query)
            .put("variables", variables)
            .toString()

        val response = runCatching {
            app.post(
                API_URL,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                ),
                requestBody = body.toRequestBody(JSON_MEDIA_TYPE),
                cacheTime = CACHE_MINUTES,
            )
        }.onFailure { Log.w(TAG, "AniList irraggiungibile: ${it.message}") }
            .getOrNull() ?: return null

        if (!response.isSuccessful) {
            Log.w(TAG, "AniList ha risposto ${response.code}")
            return null
        }
        return runCatching { JSONObject(response.text).optJSONObject("data") }.getOrNull()
    }

    /**
     * "In corso" nel senso di cio' che si puo' guardare: trimestri solari, che e' come
     * AniList etichetta le uscite (una serie che parte a ottobre e' FALL). A settembre la
     * stagione in onda e' ancora SUMMER; chiedere FALL darebbe una riga di titoli non
     * ancora usciti, che nessun provider ha (verificato il 6 settembre 2026).
     */
    private fun currentSeason(): Pair<String, Int> {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val season = when (calendar.get(Calendar.MONTH) + 1) {
            1, 2, 3 -> "WINTER"
            4, 5, 6 -> "SPRING"
            7, 8, 9 -> "SUMMER"
            else -> "FALL"
        }
        return season to year
    }

    private fun previousSeason(): Pair<String, Int> {
        val (season, year) = currentSeason()
        return when (season) {
            "WINTER" -> "FALL" to year - 1
            "SPRING" -> "WINTER" to year
            "SUMMER" -> "SPRING" to year
            else -> "SUMMER" to year
        }
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.optNullableInt(key: String): Int? =
        if (isNull(key)) null else optInt(key, -1).takeIf { it >= 0 }

    companion object {
        private const val TAG = "HomeIta"
        private const val ANIME_UNITY = "AnimeUnity"
        private const val API_URL = "https://graphql.anilist.co"
        private const val JIKAN_URL = "https://api.jikan.moe/v4"
        private const val MAL_URL = "https://myanimelist.net"
        private const val PER_PAGE = 30
        private const val MIN_SEASON_ITEMS = 10
        private const val CACHE_MINUTES = 10
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val PAGE_QUERY = """
            query (${'$'}page: Int, ${'$'}perPage: Int, ${'$'}sort: [MediaSort], ${'$'}status: MediaStatus,
                   ${'$'}statusNot: MediaStatus, ${'$'}season: MediaSeason, ${'$'}seasonYear: Int, ${'$'}search: String) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { hasNextPage }
                media(type: ANIME, sort: ${'$'}sort, status: ${'$'}status, status_not: ${'$'}statusNot, season: ${'$'}season,
                      seasonYear: ${'$'}seasonYear, search: ${'$'}search, isAdult: false) {
                  id
                  idMal
                  title { romaji english native }
                  coverImage { extraLarge large }
                  averageScore
                  episodes
                  seasonYear
                }
              }
            }
        """.trimIndent()
    }
}

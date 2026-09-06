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
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.nicehttp.NiceResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    // Con AniList in salute le cinque righe partono davvero insieme: una andata e ritorno
    // invece di cinque piu' le pause. Quando si ripiega su Jikan si riserializzano comunque
    // sul jikanMutex, quindi li' il guadagno e' minimo: il tempo vero l'ha tolto load(), che
    // non passa piu' da AnimeUnity. La pausa fra le espansioni resta, non c'entra con questo.
    override var sequentialMainPage = false
    override var sequentialMainPageScrollDelay = 500L

    override val mainPage = mainPageOf(
        "season" to "Stagione in corso",
        "trending" to "Di tendenza",
        "airing" to "In onda ora",
        "popular" to "I piu' seguiti",
        "top" to "I meglio votati",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val startedAt = System.currentTimeMillis()
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
            val fallback = jikanFallback(request.data, page)
            Log.i(TAG, "riga '${request.name}' da Jikan in ${System.currentTimeMillis() - startedAt}ms (${fallback.size} card)")
            return newHomePageResponse(request.name, fallback, false)
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
            val fallback = jikanFallback(request.data, page)
            Log.i(TAG, "riga '${request.name}' da Jikan in ${System.currentTimeMillis() - startedAt}ms (${fallback.size} card)")
            return newHomePageResponse(request.name, fallback, false)
        }

        val hasNext = page0.optJSONObject("pageInfo")?.optBoolean("hasNextPage") ?: false

        Log.i(TAG, "riga '${request.name}' pronta in ${System.currentTimeMillis() - startedAt}ms (${items.size} card)")
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
        // Jikan risponde 504 a intermittenza su singole classifiche (misurato il 6/9/2026:
        // "top/anime?filter=airing" e "anime?status=airing" giu', "seasons/now" e "top/anime"
        // in piedi). Ogni riga ha quindi un secondo percorso equivalente: meglio una riga con
        // contenuto simile che una riga vuota.
        val paths = when (section) {
            "season" -> listOf("seasons/now?page=$page", "top/anime?filter=airing&page=$page")
            "trending" -> listOf("top/anime?filter=airing&page=$page", "seasons/now?page=$page")
            "airing" -> listOf("anime?status=airing&order_by=popularity&page=$page", "seasons/now?page=$page")
            "popular" -> listOf("top/anime?filter=bypopularity&page=$page", "top/anime?page=$page")
            else -> listOf("top/anime?page=$page")
        }

        val response = paths.firstNotNullOfOrNull { jikanGet(it, section) } ?: return emptyList()

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

            val url = "$MAL_URL/anime/$malId"
            remember(
                url, Details(
                    title = title,
                    poster = poster,
                    plot = entry.optNullableString("synopsis")?.plainToHtml(),
                    tags = entry.optJSONArray("genres")?.let { g ->
                        (0 until g.length()).mapNotNull { g.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
                    },
                    year = entry.optNullableInt("year"),
                    score = Score.from10(entry.optDouble("score").takeIf { !it.isNaN() }),
                )
            )
            newAnimeSearchResponse(title, url, TvType.Anime, fix = false) {
                this.posterUrl = poster
                this.score = Score.from10(entry.optDouble("score").takeIf { !it.isNaN() })
                this.year = entry.optNullableInt("year")
            }.copy(apiName = ANIME_UNITY)
        }
    }

    /**
     * Jikan misura le richieste: alla quinta a raffica risponde 429 (verificato il 6/9/2026).
     * Le righe della home ora partono insieme, quindi qui si passa uno alla volta e a distanza,
     * con un solo secondo tentativo. Senza questo freno, con l'API di AniList disabilitata
     * (succede: 403 "temporarily disabled") la home resterebbe mezza vuota.
     */
    private suspend fun jikanGet(path: String, section: String): NiceResponse? {
        repeat(2) { attempt ->
            val response = jikanOnce(path, section)
            if (response != null && response.isSuccessful) return response
            val code = response?.code
            Log.w(TAG, "Jikan ha risposto $code per '$section'${if (attempt == 0 && code == 429) ", riprovo" else ""}")
            // 429 = troppe richieste: vale un secondo tentativo, ma l'attesa va fatta FUORI dal
            // lock, altrimenti la riga sfortunata ferma anche le altre quattro.
            if (code != 429) return null
            if (attempt == 0) delay(JIKAN_RETRY_MS)
        }
        return null
    }

    /** Una sola richiesta a Jikan, in fila e a distanza dalla precedente. */
    private suspend fun jikanOnce(path: String, section: String): NiceResponse? =
        jikanMutex.withLock {
            val wait = JIKAN_MIN_GAP_MS - (System.currentTimeMillis() - lastJikanCall)
            if (wait > 0) delay(wait)
            val response = runCatching { app.get("$JIKAN_URL/$path", cacheTime = CACHE_MINUTES) }
                .onFailure { Log.w(TAG, "Jikan irraggiungibile per '$section': ${it.message}") }
                .getOrNull()
            lastJikanCall = System.currentTimeMillis()
            response
        }

    /** Dettagli da Jikan: servono quando AniList non risponde, cioe' spesso. */
    private suspend fun jikanDetails(malId: Int): Details? {
        val response = jikanGet("anime/$malId", "dettagli") ?: return null
        val data = runCatching { JSONObject(response.text).getJSONObject("data") }.getOrNull() ?: return null
        val title = data.optNullableString("title_english") ?: data.optNullableString("title") ?: return null
        return Details(
            title = title,
            poster = data.optJSONObject("images")?.optJSONObject("jpg")?.optNullableString("large_image_url"),
            plot = data.optNullableString("synopsis")?.plainToHtml(),
            tags = data.optJSONArray("genres")?.let { g ->
                (0 until g.length()).mapNotNull { g.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
            },
            year = data.optNullableInt("year"),
            // Jikan scrive "24 min per ep" per le serie ma "1 hr 39 min" per i film:
            // prendendo solo i minuti un film di 99 minuti ne dichiarerebbe 39.
            duration = parseJikanDuration(data.optString("duration")),
            score = Score.from10(data.optDouble("score").takeIf { !it.isNaN() }),
        )
    }

    /**
     * Serve SOLO all'anteprima in cima alla home: HomeViewModel carica tre schede con il repo
     * della home e pubblica le righe solo DOPO che sono tornate (updatePreviewResponses, poi
     * _page.postValue). Ogni secondo speso qui e' un secondo di home vuota, e passare da
     * AnimeUnity (Jikan + AniList + archivio + pagina, per tre titoli) costava piu' di quattro
     * minuti sul Fire TV Stick: misurato il 6/9/2026.
     *
     * Quindi qui non si contatta nessuno: si riusano i dettagli che AniList ha gia' mandato
     * insieme alle righe. La risposta porta apiName "AnimeUnity" e l'URL della card, cosi' il
     * click sull'anteprima segue la stessa strada delle card (SyncRedirector -> getLoadUrl).
     *
     * PREZZO DA PAGARE, scelto consapevolmente: prima il ponte faceva anche da filtro, perche'
     * una scheda che AnimeUnity non aveva falliva e il core la scartava dall'anteprima. Ora
     * l'anteprima e' ottimistica: puo' proporre un titolo che al click non si apre, e l'errore
     * si vede li' invece che mai. Quattro minuti di home vuota erano peggio.
     * Limite noto, solo sul layout telefono: il segnalibro salvato dall'anteprima usa l'URL
     * AniList/MAL, quindi un id diverso da quello della scheda AnimeUnity. Sul layout TV, che
     * e' quello dei Fire TV Stick, il pulsante segnalibro dell'anteprima non esiste.
     */
    override suspend fun load(url: String): LoadResponse {
        // Senza AnimeUnity non c'e' nessuno che sappia aprire questi id: meglio dirlo qui che
        // lasciare una scheda vuota con "nessun episodio".
        if (APIHolder.getApiFromNameNull(ANIME_UNITY) == null) {
            throw ErrorLoadingException("Per aprire i titoli serve il plugin AnimeUnity")
        }
        val details = readDetails(url) ?: fetchDetails(url)
            ?: throw ErrorLoadingException("Dettagli non disponibili per $url")

        return newAnimeLoadResponse(details.title, url, TvType.Anime, comingSoonIfNone = false) {
            this.posterUrl = details.poster
            this.backgroundPosterUrl = details.banner ?: details.poster
            this.plot = details.plot
            this.tags = details.tags
            this.year = details.year
            this.duration = details.duration
            this.score = details.score
        }.copy(apiName = ANIME_UNITY)
    }

    /** Ripiego per un URL che non viene dalle righe appena caricate (una sola richiesta). */
    private suspend fun fetchDetails(url: String): Details? {
        val (syncName, id) = parseCardUrl(url) ?: return null
        val field = if (syncName == SyncIdName.MyAnimeList) "idMal" else "id"
        val variables = JSONObject().put("id", id)
        val media = graphQl(MEDIA_QUERY.replace("FIELD", field), variables)?.optJSONObject("Media")
            ?: return if (syncName == SyncIdName.MyAnimeList) jikanDetails(id) else null
        val title = preferredTitle(media.optJSONObject("title")) ?: return null
        return Details(
            title = title,
            poster = media.optJSONObject("coverImage")?.let {
                it.optNullableString("extraLarge") ?: it.optNullableString("large")
            },
            banner = media.optNullableString("bannerImage"),
            plot = media.optNullableString("description"),
            tags = media.optJSONArray("genres")?.let { g -> (0 until g.length()).mapNotNull { g.optString(it).takeIf(String::isNotBlank) } },
            year = media.optNullableInt("seasonYear"),
            duration = media.optNullableInt("duration"),
            score = Score.from100(media.optNullableInt("averageScore")),
        )
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
    private fun parseJikanDuration(raw: String): Int? {
        val hours = Regex("(\\d+)\\s*hr").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = Regex("(\\d+)\\s*min").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return (hours * 60 + minutes).takeIf { it > 0 }
    }

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

        // La stessa risposta di AniList porta gia' tutto quello che l'anteprima mostra: si tiene
        // da parte, cosi' load() non deve chiedere niente a nessuno (vedi il commento in load).
        remember(
            url, Details(
                title = title,
                poster = poster,
                banner = entry.optNullableString("bannerImage"),
                plot = entry.optNullableString("description"),
                tags = entry.optJSONArray("genres")?.let { g -> (0 until g.length()).mapNotNull { g.optString(it).takeIf(String::isNotBlank) } },
                year = entry.optNullableInt("seasonYear"),
                duration = entry.optNullableInt("duration"),
                score = Score.from100(entry.optNullableInt("averageScore")),
            )
        )

        // apiName e' in sola lettura negli stub dei plugin: si imposta con copy() della data class
        return newAnimeSearchResponse(title, url, TvType.Anime, fix = false) {
            this.posterUrl = poster
            this.score = Score.from100(entry.optNullableInt("averageScore"))
            this.year = entry.optNullableInt("seasonYear")
        }.copy(apiName = ANIME_UNITY)
    }

    private data class Details(
        val title: String,
        val poster: String?,
        val banner: String? = null,
        val plot: String? = null,
        val tags: List<String>? = null,
        val year: Int? = null,
        val duration: Int? = null,
        val score: Score? = null,
    )

    private fun remember(url: String, details: Details) = synchronized(detailsCache) {
        detailsCache[url] = details
    }

    private fun readDetails(url: String): Details? = synchronized(detailsCache) { detailsCache[url] }

    /**
     * Il core passa la trama a HtmlCompat.fromHtml prima di mostrarla, quindi l'HTML di AniList
     * va lasciato com'e' (i <br> diventano a capo, <i> corsivo). Jikan invece manda testo
     * semplice: li' i ritorni a capo vanno tradotti, o il parser HTML li mangia.
     */
    private fun String.plainToHtml(): String = trim().replace("\n", "<br>")

    /** Una richiesta ad AniList, con un solo secondo tentativo se risponde 429. */
    private suspend fun graphQl(query: String, variables: JSONObject): JSONObject? {
        val response = graphQlOnce(query, variables) ?: return null
        if (response.isSuccessful) {
            return runCatching { JSONObject(response.text).optJSONObject("data") }.getOrNull()
        }
        // 429 = solo troppe richieste: aspettare un attimo costa meno che ripiegare su Jikan,
        // che e' piu' lento e a sua volta contingentato. Ogni altro codice (il 403 di "API
        // temporaneamente disabilitata", i 5xx) e' un problema che l'attesa non risolve.
        if (response.code != 429) {
            Log.w(TAG, "AniList ha risposto ${response.code}")
            return null
        }
        val wait = response.headers["Retry-After"]?.toLongOrNull()?.coerceAtMost(5)?.times(1000)
            ?: ANILIST_RETRY_MS
        Log.w(TAG, "AniList ha risposto 429, riprovo fra ${wait}ms")
        delay(wait)
        val retry = graphQlOnce(query, variables)?.takeIf { it.isSuccessful } ?: return null
        return runCatching { JSONObject(retry.text).optJSONObject("data") }.getOrNull()
    }

    private suspend fun graphQlOnce(query: String, variables: JSONObject): NiceResponse? {
        val body = JSONObject()
            .put("query", query)
            .put("variables", variables)
            .toString()
        return runCatching {
            app.post(
                API_URL,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                ),
                requestBody = body.toRequestBody(JSON_MEDIA_TYPE),
                cacheTime = CACHE_MINUTES,
            )
        }.onFailure { Log.w(TAG, "AniList irraggiungibile: ${it.message}") }.getOrNull()
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
        private const val MAX_DETAILS = 400
        private val jikanMutex = Mutex()
        private const val JIKAN_MIN_GAP_MS = 400L
        private const val JIKAN_RETRY_MS = 1500L
        private const val ANILIST_RETRY_MS = 1000L
        @Volatile
        private var lastJikanCall = 0L
        // i dettagli arrivati con le righe, riusati dall'anteprima senza altre richieste
        // LinkedHashMap in ordine di accesso: quando si sfratta se ne va la voce usata da
        // piu' tempo, non una a caso (ConcurrentHashMap.keys.first e' il primo bucket, che
        // con la home aperta a lungo poteva buttare proprio le card dell'anteprima).
        private val detailsCache = object : LinkedHashMap<String, Details>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Details>?) = size > MAX_DETAILS
        }
        private const val CACHE_MINUTES = 10
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val MEDIA_QUERY = """
            query (${'$'}id: Int) {
              Media(FIELD: ${'$'}id, type: ANIME) {
                id
                idMal
                title { romaji english native }
                coverImage { extraLarge large }
                bannerImage
                description(asHtml: false)
                genres
                duration
                averageScore
                seasonYear
              }
            }
        """.trimIndent()

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
                  bannerImage
                  description(asHtml: false)
                  genres
                  duration
                  averageScore
                  episodes
                  seasonYear
                }
              }
            }
        """.trimIndent()
    }
}

package it.dogior.hadEnough

import android.content.SharedPreferences
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.addPoster
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import android.util.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Element
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

typealias Str = BooleanOrString.AsString

@Suppress("unused")
class AnimeUnity(
    private val sharedPref: SharedPreferences?,
) : MainAPI() {
    override var mainUrl = AnimeUnityPlugin.getConfiguredBaseUrl(sharedPref)
        get() = AnimeUnityPlugin.getConfiguredBaseUrl(sharedPref)
        set(value) {
            field = value
        }
    override var name = Companion.name
    override var supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override var lang = "it"
    override val hasMainPage = true

    companion object {
        @Suppress("ConstPropertyName")
        const val mainUrl = "https://www.animeunity.so"
        const val ARCHIVE_BATCH_SIZE = 30
        // URL che arrivano a load() solo se il SyncRedirector non ha trovato il titolo
        val SYNC_HOSTS = listOf("anilist.co/", "myanimelist.net/")
        // Perche' l'ultimo getLoadUrl per quell'id e' fallito: load() lo mostra al posto di un
        // generico "non trovato", che sarebbe falso quando a mancare sono Jikan/AniList o l'archivio.
        // La chiave porta il namespace: l'id 204431 di AniList non e' l'id 204431 di MyAnimeList.
        val lookupFailures = ConcurrentHashMap<String, String>()
        // NiceHttp non puo' mettere in cache queste risposte: OkHttp memorizza solo le GET (le
        // query AniList sono POST) e Jikan serve gia' scaduto. Senza questa mappa ogni card
        // dell'anteprima e ogni click rifanno le stesse richieste ad AniList, che in modalita'
        // degradata concede 30 richieste al minuto.
        val metadataCache = ConcurrentHashMap<String, Pair<Long, Metadata>>()
        const val MAX_LOOKUP_PAGES = 3
        const val MAX_LOOKUP_FAILURES = 200
        const val METADATA_TTL_MS = 60 * 60 * 1000L
        const val METADATA_FAIL_TTL_MS = 2 * 60 * 1000L
        val SEASON_SUFFIX = Regex(
            """\s*[:\-\u2013]?\s*(?:(?:\d+(?:st|nd|rd|th)|final)\s+(?:season|part|cour)|(?:season|part|cour)\s+\d+|\b(?:ii|iii|iv|v|vi)|\d+)\s*$""",
            RegexOption.IGNORE_CASE
        )
        const val advancedSearchSectionName = "Ricerca avanzata"
        const val latestEpisodesSectionName = "Ultimi Episodi"
        const val calendarSectionName = "Calendario"
        const val randomSectionName = "Random"
        const val ongoingSectionName = "In Corso"
        const val popularSectionName = "Popolari"
        const val bestSectionName = "I migliori"
        const val upcomingSectionName = "In Arrivo"
        
        var name = "AnimeUnity"
        // headers e' condivisa fra tutte le coroutine. Due regole: il mutex serializza la
        // handshake, e la mappa e' uno snapshot IMMUTABILE sostituito con una sola
        // assegnazione. Mai clear()+putAll(): fra i due passi c'e' una richiesta di rete, e
        // chi leggeva in quel momento mandava all'archivio solo Host+User-Agent, che il
        // server rifiuta con 419 e una pagina HTML (visto nei log quando la home apre piu'
        // card in parallelo).
        val headersMutex = Mutex()
        @Volatile
        var headers: Map<String, String> = baseHeaders(mainUrl.toHttpUrl().host)

        fun baseHeaders(host: String) = mapOf(
            "Host" to host,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"
        )
    }

    private data class ArchivePageResult(
        val titles: List<Anime>,
        val hasNextPage: Boolean,
    )

    private data class MainPageSectionData(
        val key: String,
        val baseUrl: String,
    )

    private data class GroupedAnimeCard(
        val anime: Anime,
        val hasDub: Boolean,
    )

    private data class GroupedLatestEpisodeCard(
        val anime: LatestEpisodeAnime,
        val hasDub: Boolean,
        val episodeNumber: String,
    )

    private data class AnimePageData(
        val anime: Anime,
        val relatedAnime: List<Anime>,
        val episodes: List<Episode>,
    )

    private data class EpisodeSource(
        val number: String,
        val url: String,
    )

    private data class EpisodePlaybackData(
        val preferredUrl: String,
        val subUrl: String?,
        val dubUrl: String?,
    )

    private data class PlayerSourceOption(
        val label: String,
        val url: String,
    )

    override val mainPage: List<MainPageData>
        get() = buildSectionNamesList()

    private fun encodeMainPageSectionData(sectionKey: String, baseUrl: String): String {
        return "$sectionKey|$baseUrl"
    }

    private fun decodeMainPageSectionData(data: String): MainPageSectionData {
        val separatorIndex = data.indexOf('|')
        return if (separatorIndex == -1) {
            MainPageSectionData(key = data, baseUrl = data)
        } else {
            MainPageSectionData(
                key = data.substring(0, separatorIndex),
                baseUrl = data.substring(separatorIndex + 1),
            )
        }
    }

    private fun getSectionDisplayTitle(sectionKey: String): String {
        return when (sectionKey) {
            "latest" -> AnimeUnityPlugin.getConfiguredSectionTitle(
                sharedPref,
                AnimeUnityPlugin.PREF_LATEST_TITLE,
                latestEpisodesSectionName,
            )
            "calendar" -> AnimeUnityPlugin.getConfiguredSectionTitle(
                sharedPref,
                AnimeUnityPlugin.PREF_CALENDAR_TITLE,
                calendarSectionName,
            )
            "ongoing" -> AnimeUnityPlugin.getConfiguredSectionTitle(
                sharedPref,
                AnimeUnityPlugin.PREF_ONGOING_TITLE,
                ongoingSectionName,
            )
            "popular" -> AnimeUnityPlugin.getConfiguredSectionTitle(
                sharedPref,
                AnimeUnityPlugin.PREF_POPULAR_TITLE,
                popularSectionName,
            )
            "best" -> AnimeUnityPlugin.getConfiguredSectionTitle(
                sharedPref,
                AnimeUnityPlugin.PREF_BEST_TITLE,
                bestSectionName,
            )
            "upcoming" -> AnimeUnityPlugin.getConfiguredSectionTitle(
                sharedPref,
                AnimeUnityPlugin.PREF_UPCOMING_TITLE,
                upcomingSectionName,
            )
            "random" -> AnimeUnityPlugin.getConfiguredSectionTitle(
                sharedPref,
                AnimeUnityPlugin.PREF_RANDOM_TITLE,
                randomSectionName,
            )
            else -> advancedSearchSectionName
        }
    }

    private fun buildSectionNamesList(): List<MainPageData> {
        val order = AnimeUnityPlugin.getConfiguredSectionOrder(sharedPref)
        val sections = buildList {
            if (AnimeUnityPlugin.isAdvancedSearchEnabled(sharedPref)) {
                add("advanced")
            }
            addAll(order.split(","))
        }
        
        return mainPageOf(
            *sections.mapNotNull { section ->
                when (section) {
                    "advanced" -> encodeMainPageSectionData("advanced", "$mainUrl/archivio/") to advancedSearchSectionName
                    "latest" -> if (isSectionEnabled(AnimeUnityPlugin.PREF_SHOW_LATEST_EPISODES)) {
                        encodeMainPageSectionData("latest", "$mainUrl/") to getSectionDisplayTitle("latest")
                    } else null
                    "calendar" -> if (isSectionEnabled(AnimeUnityPlugin.PREF_SHOW_CALENDAR)) {
                        encodeMainPageSectionData("calendar", "$mainUrl/calendario") to getSectionDisplayTitle("calendar")
                    } else null
                    "ongoing" -> if (isSectionEnabled(AnimeUnityPlugin.PREF_SHOW_ONGOING)) {
                        encodeMainPageSectionData("ongoing", "$mainUrl/archivio/") to getSectionDisplayTitle("ongoing")
                    } else null
                    "popular" -> if (isSectionEnabled(AnimeUnityPlugin.PREF_SHOW_POPULAR)) {
                        encodeMainPageSectionData("popular", "$mainUrl/archivio/") to getSectionDisplayTitle("popular")
                    } else null
                    "best" -> if (isSectionEnabled(AnimeUnityPlugin.PREF_SHOW_BEST)) {
                        encodeMainPageSectionData("best", "$mainUrl/archivio/") to getSectionDisplayTitle("best")
                    } else null
                    "upcoming" -> if (isSectionEnabled(AnimeUnityPlugin.PREF_SHOW_UPCOMING)) {
                        encodeMainPageSectionData("upcoming", "$mainUrl/archivio/") to getSectionDisplayTitle("upcoming")
                    } else null
                    "random" -> if (isSectionEnabled(AnimeUnityPlugin.PREF_SHOW_RANDOM)) {
                        encodeMainPageSectionData("random", "$mainUrl/archivio/") to getSectionDisplayTitle("random")
                    } else null
                    else -> null
                }
            }.toTypedArray()
        )
    }

    private fun isSectionEnabled(prefKey: String): Boolean {
        return sharedPref?.getBoolean(prefKey, true) ?: true
    }

    private fun getSectionCount(sectionKey: String): Int {
        val (key, defaultCount) = when (sectionKey) {
            "advanced" -> AnimeUnityPlugin.PREF_ADVANCED_SEARCH_COUNT to
                AnimeUnityPlugin.DEFAULT_ADVANCED_SEARCH_COUNT
            "latest" -> AnimeUnityPlugin.PREF_LATEST_COUNT to
                AnimeUnityPlugin.DEFAULT_SECTION_COUNT
            "calendar" -> AnimeUnityPlugin.PREF_CALENDAR_COUNT to
                AnimeUnityPlugin.DEFAULT_SECTION_COUNT
            "ongoing" -> AnimeUnityPlugin.PREF_ONGOING_COUNT to
                AnimeUnityPlugin.DEFAULT_SECTION_COUNT
            "popular" -> AnimeUnityPlugin.PREF_POPULAR_COUNT to
                AnimeUnityPlugin.DEFAULT_SECTION_COUNT
            "best" -> AnimeUnityPlugin.PREF_BEST_COUNT to
                AnimeUnityPlugin.DEFAULT_SECTION_COUNT
            "upcoming" -> AnimeUnityPlugin.PREF_UPCOMING_COUNT to
                AnimeUnityPlugin.DEFAULT_SECTION_COUNT
            "random" -> AnimeUnityPlugin.PREF_RANDOM_COUNT to
                AnimeUnityPlugin.DEFAULT_SECTION_COUNT
            else -> return AnimeUnityPlugin.DEFAULT_SECTION_COUNT
        }
        return (sharedPref?.getInt(key, defaultCount)
            ?: defaultCount).coerceIn(1, AnimeUnityPlugin.MAX_SECTION_COUNT)
    }

    private fun shouldShowScore(): Boolean {
        return sharedPref?.getBoolean(AnimeUnityPlugin.PREF_SHOW_SCORE, true) ?: true
    }

    private fun shouldShowDubSub(): Boolean {
        return sharedPref?.getBoolean(AnimeUnityPlugin.PREF_SHOW_DUB_SUB, true) ?: true
    }

    private fun shouldShowEpisodeNumber(): Boolean {
        return sharedPref?.getBoolean(AnimeUnityPlugin.PREF_SHOW_EPISODE_NUMBER, true) ?: true
    }

    private fun shouldUseUnifiedDubSubCards(): Boolean {
        return AnimeUnityPlugin.shouldUseUnifiedDubSubCards(sharedPref)
    }

    private fun withoutDubSuffix(title: String): String {
        return title.replace(" (ITA)", "")
    }

    private fun getAnimeTitle(anime: Anime): String {
        return anime.titleIt ?: anime.titleEng ?: anime.title!!
    }

    private fun getAnimeTitle(anime: LatestEpisodeAnime): String {
        return anime.titleIt ?: anime.titleEng ?: anime.title!!
    }

    private fun isDubAnime(anime: Anime): Boolean {
        return anime.dub == 1 || getAnimeTitle(anime).contains("(ITA)")
    }

    private fun isDubAnime(anime: LatestEpisodeAnime): Boolean {
        return anime.dub == 1 || getAnimeTitle(anime).contains("(ITA)")
    }

    private fun Anime.contentKey(): String {
        return when {
            anilistId != null -> "anilist:$anilistId"
            malId != null -> "mal:$malId"
            else -> "title:${normalizeJikanTitle(withoutDubSuffix(getAnimeTitle(this)))}"
        }
    }

    private fun LatestEpisodeAnime.contentKey(): String {
        return when {
            anilistId != null -> "anilist:$anilistId"
            else -> "title:${normalizeJikanTitle(withoutDubSuffix(getAnimeTitle(this)))}"
        }
    }

    private fun Anime.cardKey(): String {
        return if (shouldUseUnifiedDubSubCards()) contentKey() else "anime:$id"
    }

    private fun preferAnime(primary: Anime, candidate: Anime): Anime {
        val primaryIsDub = isDubAnime(primary)
        val candidateIsDub = isDubAnime(candidate)

        return when {
            primaryIsDub && !candidateIsDub -> candidate
            !primaryIsDub && candidateIsDub -> primary
            candidate.episodesCount > primary.episodesCount -> candidate
            else -> primary
        }
    }

    private fun preferAnime(primary: LatestEpisodeAnime, candidate: LatestEpisodeAnime): LatestEpisodeAnime {
        val primaryIsDub = isDubAnime(primary)
        val candidateIsDub = isDubAnime(candidate)

        return when {
            primaryIsDub && !candidateIsDub -> candidate
            !primaryIsDub && candidateIsDub -> primary
            candidate.episodesCount > primary.episodesCount -> candidate
            else -> primary
        }
    }

    private fun groupAnimeCards(animes: List<Anime>): List<GroupedAnimeCard> {
        if (animes.isEmpty()) return emptyList()
        if (!shouldUseUnifiedDubSubCards()) {
            return animes.map { anime ->
                GroupedAnimeCard(
                    anime = anime,
                    hasDub = isDubAnime(anime),
                )
            }
        }

        return animes
            .groupBy { it.contentKey() }
            .values
            .map { variants ->
                GroupedAnimeCard(
                    anime = variants.reduce { primary, candidate -> preferAnime(primary, candidate) },
                    hasDub = variants.any { isDubAnime(it) },
                )
            }
    }

    private fun groupLatestEpisodeCards(items: List<LatestEpisodeItem>): List<GroupedLatestEpisodeCard> {
        if (items.isEmpty()) return emptyList()
        if (!shouldUseUnifiedDubSubCards()) {
            return items.map { item ->
                GroupedLatestEpisodeCard(
                    anime = item.anime,
                    hasDub = isDubAnime(item.anime),
                    episodeNumber = item.number,
                )
            }
        }

        return items
            .groupBy { it.anime.contentKey() }
            .values
            .map { variants ->
                val latestEpisode = variants.maxWithOrNull(
                    compareBy<LatestEpisodeItem>(
                        { parseEpisodeSortValue(it.number) ?: Double.NEGATIVE_INFINITY },
                        { it.number }
                    )
                ) ?: variants.first()

                GroupedLatestEpisodeCard(
                    anime = variants.map { it.anime }.reduce { primary, candidate -> preferAnime(primary, candidate) },
                    hasDub = variants.any { isDubAnime(it.anime) },
                    episodeNumber = latestEpisode.number,
                )
            }
    }

    private fun getShowStatus(status: String?): ShowStatus? {
        return when (status?.trim()?.lowercase(Locale.ROOT)) {
            "terminato" -> ShowStatus.Completed
            "in corso" -> ShowStatus.Ongoing
            else -> null
        }
    }

    private fun getStatusTag(status: String?): String? {
        val normalizedStatus = status?.trim().orEmpty()
        if (normalizedStatus.isBlank()) return null
        return if (getShowStatus(normalizedStatus) == null) "Stato: $normalizedStatus" else null
    }

    private fun buildDisplayTitle(title: String, episodeNumber: Int?): String {
        val baseTitle = withoutDubSuffix(title)

        return if (!shouldShowDubSub() && shouldShowEpisodeNumber() && episodeNumber != null) {
            "$baseTitle - Ep. $episodeNumber"
        } else {
            baseTitle
        }
    }

    private fun getMiniCardDubStatus(hasDub: Boolean): DubStatus {
        return if (hasDub) DubStatus.Dubbed else DubStatus.Subbed
    }

    private fun applyCardDisplayState(
        response: AnimeSearchResponse,
        dubStatus: DubStatus,
        poster: String?,
        score: String?,
        episodeNumber: Int? = null,
    ) {
        if (shouldShowDubSub()) {
            if (shouldShowEpisodeNumber()) {
                response.addDubStatus(dubStatus, episodeNumber)
            } else {
                response.addDubStatus(dubStatus)
            }
        }

        response.addPoster(poster)

        if (shouldShowScore()) {
            score?.let {
                response.score = Score.from(it, 10)
            }
        }
    }

    private suspend fun ensureHeadersAndCookies() = headersMutex.withLock {
        val current = headers
        val shouldRefreshHeaders =
            current["Host"] != mainUrl.toHttpUrl().host ||
            current["Referer"] != mainUrl ||
            !current.containsKey("Cookie")

        if (shouldRefreshHeaders) {
            setupHeadersAndCookies()
        }
    }

    /** Da chiamare con headersMutex preso. Nuova sessione, pubblicata con una sola assegnazione. */
    private suspend fun setupHeadersAndCookies() {
        val base = baseHeaders(mainUrl.toHttpUrl().host)
        val response = app.get("$mainUrl/archivio", headers = base)

        val csrfToken = response.document.head().select("meta[name=csrf-token]").attr("content")
        val xsrf = response.cookies["XSRF-TOKEN"]
        val session = response.cookies["animeunity_session"]
        if (csrfToken.isBlank() || xsrf == null || session == null) {
            // pagina senza handshake (challenge, manutenzione): meglio nessuna sessione, cosi' il
            // prossimo ensure riprova, che una sessione con cookie "null" creduta valida
            Log.w("AnimeUnity", "Handshake senza CSRF/cookie (HTTP ${response.code}): sessione non aggiornata")
            headers = base
            return
        }
        headers = base + mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/json;charset=utf-8",
            "X-CSRF-Token" to csrfToken,
            "Referer" to mainUrl,
            "Cookie" to "XSRF-TOKEN=$xsrf; animeunity_session=$session"
        )
    }

    private suspend fun searchResponseBuilder(objectList: List<Anime>, episodeNumber: Int? = null): List<SearchResponse> {
        return groupAnimeCards(objectList).amap { entry ->
            val anime = entry.anime
            val title = getAnimeTitle(anime)
            val poster = getImage(anime.imageUrl, anime.anilistId)

            newAnimeSearchResponse(
                name = buildDisplayTitle(title, episodeNumber),
                url = "$mainUrl/anime/${anime.id}-${anime.slug}",
                type = when {
                    anime.type == "TV" -> TvType.Anime
                    anime.type == "Movie" || anime.episodesCount == 1 -> TvType.AnimeMovie
                    else -> TvType.OVA
                }
            ).apply {
                applyCardDisplayState(
                    response = this,
                    dubStatus = getMiniCardDubStatus(entry.hasDub),
                    poster = poster,
                    score = anime.score,
                    episodeNumber = episodeNumber
                )
            }
        }
    }

    /**
     * Unica porta verso l'archivio. Una sessione scaduta o mancante vale un 419: si rinnova
     * UNA volta e si riprova, ma solo se nessun'altra coroutine l'ha gia' rinnovata nel
     * frattempo (identita' dello snapshot). Cosi' load() e getLoadUrl non forzano piu' una
     * handshake a ogni chiamata, che moltiplicava le GET /archivio e apriva la corsa.
     */
    private suspend fun fetchArchiveBatch(url: String, requestData: RequestData): ApiResponse {
        ensureHeadersAndCookies()
        val used = headers
        val first = app.post(url, headers = used, requestBody = requestData.toRequestBody())
        if (first.isSuccessful) return parseJson<ApiResponse>(first.text)

        Log.w("AnimeUnity", "Archivio: HTTP ${first.code}, rinnovo la sessione e riprovo")
        headersMutex.withLock { if (headers === used) setupHeadersAndCookies() }
        // Se la handshake non ha prodotto una sessione, la seconda POST fallirebbe uguale:
        // meglio fermarsi subito che raddoppiare le richieste verso un sito in difficolta'.
        val renewed = headers
        if (!renewed.containsKey("Cookie")) throw ErrorLoadingException("Sessione AnimeUnity non disponibile (HTTP ${first.code})")
        val second = app.post(url, headers = renewed, requestBody = requestData.toRequestBody())
        if (!second.isSuccessful) throw ErrorLoadingException("Archivio AnimeUnity: HTTP ${second.code}")
        return parseJson<ApiResponse>(second.text)
    }

    private suspend fun fetchArchiveSectionPage(
        url: String,
        requestData: RequestData,
        page: Int,
        sectionCount: Int,
    ): ArchivePageResult {
        val collectedTitles = mutableListOf<Anime>()
        val uniqueContentKeys = linkedSetOf<String>()
        var nextOffset = (page - 1) * sectionCount
        var total = 0

        while (uniqueContentKeys.size < sectionCount) {
            val responseObject = fetchArchiveBatch(url, requestData.copy(offset = nextOffset))
            total = responseObject.total

            val batchTitles = responseObject.titles.orEmpty()
            if (batchTitles.isEmpty()) break

            collectedTitles += batchTitles
            batchTitles.forEach { uniqueContentKeys += it.cardKey() }
            nextOffset += batchTitles.size

            if (nextOffset >= total || batchTitles.size < ARCHIVE_BATCH_SIZE) {
                break
            }
        }

        return ArchivePageResult(
            titles = collectedTitles,
            hasNextPage = nextOffset < total,
        )
    }

    private suspend fun fetchRandomTitles(url: String, sectionCount: Int): Pair<List<Anime>, Int> {
        val requestData = RequestData(dubbed = 0)
        val initialResponse = fetchArchiveBatch(url, requestData.copy(offset = 0))
        val total = initialResponse.total
        val collectedTitles = linkedMapOf<Int, Anime>()
        val requestedOffsets = mutableSetOf<Int>()
        val maxAttempts = ((sectionCount + ARCHIVE_BATCH_SIZE - 1) / ARCHIVE_BATCH_SIZE) * 3

        fun collectBatch(batch: List<Anime>) {
            batch.forEach { anime ->
                collectedTitles.putIfAbsent(anime.id, anime)
            }
        }

        if (total <= ARCHIVE_BATCH_SIZE) {
            collectBatch(initialResponse.titles.orEmpty())
            return collectedTitles.values.shuffled().take(sectionCount) to total
        }

        repeat(maxAttempts) {
            if (collectedTitles.size >= sectionCount) {
                return@repeat
            }

            val maxOffset = (total - ARCHIVE_BATCH_SIZE).coerceAtLeast(0)
            val randomOffset = if (maxOffset == 0) 0 else (0..maxOffset).random()
            if (!requestedOffsets.add(randomOffset)) {
                return@repeat
            }

            collectBatch(fetchArchiveBatch(url, requestData.copy(offset = randomOffset)).titles.orEmpty())
        }

        if (collectedTitles.isEmpty()) {
            collectBatch(initialResponse.titles.orEmpty())
        }

        return collectedTitles.values.shuffled().take(sectionCount) to total
    }

    private suspend fun latestEpisodesResponseBuilder(objectList: List<LatestEpisodeItem>): List<SearchResponse> {
        return groupLatestEpisodeCards(objectList).amap { entry ->
            val anime = entry.anime
            val title = getAnimeTitle(anime)
            val poster = getImage(anime.imageUrl, anime.anilistId)

            newAnimeSearchResponse(
                name = buildDisplayTitle(title, entry.episodeNumber.toIntOrNull()),
                url = "$mainUrl/anime/${anime.id}-${anime.slug}",
                type = when {
                    anime.type == "TV" -> TvType.Anime
                    anime.type == "Movie" || anime.episodesCount == 1 -> TvType.AnimeMovie
                    else -> TvType.OVA
                }
            ).apply {
                applyCardDisplayState(
                    response = this,
                    dubStatus = getMiniCardDubStatus(entry.hasDub),
                    poster = poster,
                    score = anime.score,
                    episodeNumber = entry.episodeNumber.toIntOrNull()
                )
            }
        }
    }

    private fun getImageCdnHost(): String {
        val host = mainUrl.toHttpUrl().host
        return when {
            host == "animeunity.so" -> "img.animeunity.so"
            host.startsWith("www.") -> host.replaceFirst("www.", "img.")
            host.startsWith("img.") -> host
            else -> "img.$host"
        }
    }

    private suspend fun getImage(imageUrl: String?, anilistId: Int?): String? {
        if (!imageUrl.isNullOrEmpty()) {
            try {
                val fileName = imageUrl.substringAfterLast("/")
                return "https://${getImageCdnHost()}/anime/$fileName"
            } catch (_: Exception) {}
        }
        return anilistId?.let { getAnilistPoster(it) }
    }

    private fun getAnimeUrl(anime: Anime): String {
        return "$mainUrl/anime/${anime.id}-${anime.slug}"
    }

    private fun getEpisodeUrl(anime: Anime, episode: Episode): String {
        return "${getAnimeUrl(anime)}/${episode.id}"
    }

    private fun parseEpisodeSortValue(number: String?): Double? {
        return number
            ?.trim()
            ?.replace(',', '.')
            ?.toDoubleOrNull()
    }

    private fun buildEpisodeDisplayName(number: String): String {
        return "Episodio $number"
    }

    private fun buildPlayerSourceOptions(playbackData: EpisodePlaybackData): List<PlayerSourceOption> {
        val orderedSources = mutableListOf<PlayerSourceOption>()
        val seenUrls = linkedSetOf<String>()

        fun addSource(url: String?, label: String) {
            val normalizedUrl = url?.takeIf(String::isNotBlank) ?: return
            if (seenUrls.add(normalizedUrl)) {
                orderedSources += PlayerSourceOption(label = label, url = normalizedUrl)
            }
        }

        when (playbackData.preferredUrl) {
            playbackData.dubUrl -> {
                addSource(playbackData.dubUrl, "[DUB]")
                addSource(playbackData.subUrl, "[SUB]")
            }
            playbackData.subUrl -> {
                addSource(playbackData.subUrl, "[SUB]")
                addSource(playbackData.dubUrl, "[DUB]")
            }
            else -> {
                addSource(playbackData.preferredUrl, "[SOURCE]")
                addSource(playbackData.dubUrl, "[DUB]")
                addSource(playbackData.subUrl, "[SUB]")
            }
        }

        return orderedSources
    }

    private fun buildEpisodeSourceMap(anime: Anime?, episodes: List<Episode>): LinkedHashMap<String, EpisodeSource> {
        val sourceAnime = anime ?: return linkedMapOf()
        val sourceMap = linkedMapOf<String, EpisodeSource>()

        episodes.forEach { episode ->
            val rawNumber = episode.number.trim()
            sourceMap.putIfAbsent(
                rawNumber,
                EpisodeSource(
                    number = rawNumber,
                    url = getEpisodeUrl(sourceAnime, episode),
                )
            )
        }

        return sourceMap
    }

    private fun buildMergedEpisodes(
        primaryEpisodes: LinkedHashMap<String, EpisodeSource>,
        fallbackEpisodes: LinkedHashMap<String, EpisodeSource>,
        subEpisodes: LinkedHashMap<String, EpisodeSource>,
        dubEpisodes: LinkedHashMap<String, EpisodeSource>,
        fallbackNamePrefix: String? = null,
    ): List<com.lagradost.cloudstream3.Episode> {
        return (primaryEpisodes.keys + fallbackEpisodes.keys)
            .distinct()
            .sortedWith(
                compareBy<String>(
                    { parseEpisodeSortValue(it) ?: Double.POSITIVE_INFINITY },
                    { it }
                )
            )
            .map { episodeNumber ->
                val source = primaryEpisodes[episodeNumber] ?: fallbackEpisodes[episodeNumber]!!
                val isFallbackEpisode = primaryEpisodes[episodeNumber] == null
                val playbackData = EpisodePlaybackData(
                    preferredUrl = source.url,
                    subUrl = subEpisodes[episodeNumber]?.url,
                    dubUrl = dubEpisodes[episodeNumber]?.url,
                )
                newEpisode(playbackData) {
                    this.episode = source.number.toIntOrNull()
                    if (isFallbackEpisode && fallbackNamePrefix != null) {
                        this.name = "$fallbackNamePrefix${buildEpisodeDisplayName(source.number)}"
                    } else if (this.episode == null) {
                        this.name = buildEpisodeDisplayName(source.number)
                    }
                }
            }
    }

    private suspend fun parseAnimePageData(animePage: org.jsoup.nodes.Document): AnimePageData {
        val relatedAnimeJsonArray = animePage.select("layout-items").attr("items-json")
        val relatedAnime = relatedAnimeJsonArray
            .takeIf(String::isNotBlank)
            ?.let { runCatching { parseJson<List<Anime>>(it) }.getOrDefault(emptyList()) }
            ?: emptyList()

        val videoPlayer = animePage.select("video-player")
        val anime = parseJson<Anime>(videoPlayer.attr("anime"))
        val initialEpisodes = parseJson<List<Episode>>(videoPlayer.attr("episodes"))
        val totalEpisodes = videoPlayer.attr("episodes_count").toIntOrNull() ?: initialEpisodes.size

        return AnimePageData(
            anime = anime,
            relatedAnime = relatedAnime,
            episodes = getAllEpisodes(anime, initialEpisodes, totalEpisodes),
        )
    }

    private suspend fun fetchAnimePageData(url: String): AnimePageData {
        val animePage = app.get(url).document
        return parseAnimePageData(animePage)
    }

    private suspend fun getAllEpisodes(
        anime: Anime,
        initialEpisodes: List<Episode>,
        totalEpisodes: Int,
    ): List<Episode> {
        val episodes = initialEpisodes.toMutableList()
        val isEpisodeNumberMultipleOfRange = totalEpisodes % 120 == 0
        val range = if (isEpisodeNumberMultipleOfRange) totalEpisodes / 120 else (totalEpisodes / 120) + 1

        if (totalEpisodes > 120) {
            for (i in 2..range) {
                val endRange = if (i == range) totalEpisodes else i * 120
                val infoUrl = "$mainUrl/info_api/${anime.id}/1?start_range=${1 + (i - 1) * 120}&end_range=${endRange}"
                val info = app.get(infoUrl).text
                val animeInfo = parseJson<AnimeInfo>(info)
                episodes.addAll(animeInfo.episodes)
            }
        }

        return episodes
    }

    private suspend fun findAnimeVariants(currentAnime: Anime): List<Anime> {
        val searchTitle = withoutDubSuffix(getAnimeTitle(currentAnime))
        val responseObject = fetchArchiveBatch(
            "$mainUrl/archivio/get-animes",
            RequestData(title = searchTitle, dubbed = 0)
        )

        return (responseObject.titles.orEmpty() + currentAnime)
            .filter { it.contentKey() == currentAnime.contentKey() }
            .distinctBy(Anime::id)
    }

    private suspend fun getAnilistPoster(anilistId: Int): String {
        val query = """
        query (${'$'}id: Int) {
            Media(id: ${'$'}id, type: ANIME) {
                coverImage {
                    large
                    medium
                }
            }
        }
    """.trimIndent()

        val body = mapOf("query" to query, "variables" to """{"id":$anilistId}""")
        val response = app.post("https://graphql.anilist.co", data = body)
        val anilistObj = parseJson<AnilistResponse>(response.text)

        return anilistObj.data.media.coverImage?.let { coverImage ->
            coverImage.large ?: coverImage.medium!!
        } ?: throw IllegalStateException("No valid image found")
    }

    private suspend fun getTrailerUrl(anime: Anime): String? {
        getAniListTrailer(anime)?.let { return it }
        return getJikanTrailer(anime)
    }

    private suspend fun getAniListTrailer(anime: Anime): String? {
        val searchTitle = anime.titleEng ?: anime.titleIt ?: anime.title

        val (query, variables) = when {
            anime.anilistId != null -> {
                """
                query (${'$'}id: Int) {
                    Media(id: ${'$'}id, type: ANIME) {
                        trailer {
                            id
                            site
                        }
                    }
                }
                """.trimIndent() to """{"id":${anime.anilistId}}"""
            }

            anime.malId != null -> {
                """
                query (${'$'}idMal: Int) {
                    Media(idMal: ${'$'}idMal, type: ANIME) {
                        trailer {
                            id
                            site
                        }
                    }
                }
                """.trimIndent() to """{"idMal":${anime.malId}}"""
            }

            !searchTitle.isNullOrBlank() -> {
                """
                query (${'$'}search: String) {
                    Media(search: ${'$'}search, type: ANIME) {
                        trailer {
                            id
                            site
                        }
                    }
                }
                """.trimIndent() to """{"search":${org.json.JSONObject.quote(searchTitle)}}"""
            }

            else -> return null
        }

        val body = mapOf("query" to query, "variables" to variables)
        val response = runCatching { app.post("https://graphql.anilist.co", data = body).text }.getOrNull()
            ?: return null
        val media = runCatching { parseJson<AnilistResponse>(response).data.media }.getOrNull()
            ?: return null

        return normalizeAniListTrailerUrl(media.trailer)
    }

    private fun normalizeAniListTrailerUrl(trailer: AnilistTrailer?): String? {
        if (trailer?.site?.equals("youtube", ignoreCase = true) == true && !trailer.id.isNullOrBlank()) {
            return "https://www.youtube.com/watch?v=${trailer.id}"
        }

        return null
    }

    private fun normalizeTrailerUrl(trailer: JikanTrailer?): String? {
        val directUrl = trailer?.url?.takeIf(String::isNotBlank)
        if (directUrl != null) return directUrl

        val youtubeId = trailer?.youtubeId?.takeIf(String::isNotBlank)
            ?: trailer?.embedUrl
                ?.substringAfter("/embed/", "")
                ?.substringBefore("?")
                ?.substringBefore("/")
                ?.takeIf(String::isNotBlank)

        if (youtubeId != null) {
            return "https://www.youtube.com/watch?v=$youtubeId"
        }

        return trailer?.embedUrl?.takeIf(String::isNotBlank)
    }

    private fun normalizeJikanTitle(title: String): String {
        return Normalizer.normalize(title, Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .lowercase(Locale.ROOT)
            .replace("[^a-z0-9]+".toRegex(), " ")
            .trim()
    }

    private suspend fun getJikanTrailer(anime: Anime): String? {
        anime.malId?.let { malId ->
            val url = "https://api.jikan.moe/v4/anime/$malId/full"
            val response = runCatching { app.get(url).text }.getOrNull() ?: return null
            val trailer = runCatching { parseJson<JikanFullResponse>(response).data.trailer }.getOrNull()
            return normalizeTrailerUrl(trailer)
        }

        val searchTitle = anime.titleEng ?: anime.titleIt ?: anime.title ?: return null
        val searchUrl = "https://api.jikan.moe/v4/anime".toHttpUrl().newBuilder()
            .addQueryParameter("q", searchTitle)
            .addQueryParameter("limit", "5")
            .build()
            .toString()

        val response = runCatching { app.get(searchUrl).text }.getOrNull() ?: return null
        val candidates = runCatching { parseJson<JikanSearchResponse>(response).data }.getOrNull().orEmpty()
        if (candidates.isEmpty()) return null

        val searchTitles = listOfNotNull(anime.titleIt, anime.titleEng, anime.title)
            .map { normalizeJikanTitle(it) }
            .filter { it.isNotBlank() }

        return candidates.firstNotNullOfOrNull { candidate ->
            val trailerUrl = normalizeTrailerUrl(candidate.trailer) ?: return@firstNotNullOfOrNull null
            val candidateTitles = buildList {
                add(candidate.title)
                candidate.titleEnglish?.let(::add)
                candidate.titleJapanese?.let(::add)
                addAll(candidate.titleSynonyms.orEmpty())
            }.map(::normalizeJikanTitle)

            trailerUrl.takeIf {
                searchTitles.isEmpty() || searchTitles.any(candidateTitles::contains)
            }
        } ?: candidates.firstNotNullOfOrNull { normalizeTrailerUrl(it.trailer) }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sectionData = decodeMainPageSectionData(request.data)

        if (sectionData.key == "latest") {
            return getLatestEpisodesMainPage(page, request.name)
        }
        if (sectionData.key == "calendar") {
            return getCalendarMainPage(page, request.name, sectionData.baseUrl)
        }
        if (sectionData.key == "random") {
            return getRandomMainPage(page, request.name, sectionData.baseUrl)
        }

        val url = sectionData.baseUrl + "get-animes"

        val requestData = getDataPerHomeSection(sectionData.key)
        val sectionCount = getSectionCount(sectionData.key)
        val archivePage = fetchArchiveSectionPage(url, requestData, page, sectionCount)
        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = if (archivePage.titles.isNotEmpty()) {
                    searchResponseBuilder(archivePage.titles)
                } else {
                    emptyList()
                },
                isHorizontalImages = false
            ),
            archivePage.hasNextPage
        )
    }

    private suspend fun getLatestEpisodesMainPage(page: Int, sectionTitle: String): HomePageResponse {
        val sectionCount = getSectionCount("latest")
        if (page > 1) {
            return newHomePageResponse(
                HomePageList(name = sectionTitle, list = emptyList(), isHorizontalImages = false),
                false
            )
        }

        val latestEpisodesJson = app.get("$mainUrl/?page=1").document
            .selectFirst("#ultimi-episodi layout-items")
            ?.attr("items-json")
            .orEmpty()

        val latestEpisodes = latestEpisodesJson
            .takeIf(String::isNotBlank)
            ?.let { json ->
                runCatching { parseJson<LatestEpisodesPage>(json).episodes }.getOrDefault(emptyList())
            }
            ?.take(sectionCount)
            ?: emptyList()

        return newHomePageResponse(
            HomePageList(
                name = sectionTitle,
                list = latestEpisodesResponseBuilder(latestEpisodes),
                isHorizontalImages = false
            ),
            false
        )
    }

    private suspend fun getCalendarMainPage(
        page: Int,
        sectionTitle: String,
        requestUrl: String,
    ): HomePageResponse {
        val currentDay = getCurrentItalianDayName()
        val calendarTitle = "$sectionTitle ($currentDay)"
        val sectionCount = getSectionCount("calendar")

        if (page > 1) {
            return newHomePageResponse(
                HomePageList(name = calendarTitle, list = emptyList(), isHorizontalImages = false),
                false
            )
        }

        val calendarAnime = app.get(requestUrl).document
            .select("calendario-item")
            .mapNotNull { item ->
                val animeJson = item.attr("a")
                if (animeJson.isBlank()) return@mapNotNull null

                val anime = runCatching { parseJson<Anime>(animeJson) }.getOrNull() ?: return@mapNotNull null
                val episodeNumber = extractCalendarEpisodeNumber(item, anime)

                if (normalizeDayName(anime.day) == normalizeDayName(currentDay)) {
                    anime to episodeNumber
                } else {
                    null
                }
            }
            .distinctBy { it.first.contentKey() }
            .take(sectionCount)

        return newHomePageResponse(
            HomePageList(
                name = calendarTitle,
                list = calendarAnime.amap { (anime, ep) ->
                    searchResponseBuilder(listOf(anime), ep).first()
                },
                isHorizontalImages = false
            ),
            false
        )
    }

    private fun extractCalendarEpisodeNumber(item: Element, anime: Anime): Int? {
        item.attr("episodes_count")
            .trim()
            .toIntOrNull()
            ?.let { releasedEpisodes ->
                return releasedEpisodes + 1
            }

        return anime.episodes
            ?.mapNotNull { it.number.toIntOrNull() }
            ?.maxOrNull()
            ?.plus(1)
    }

    private suspend fun getRandomMainPage(
        page: Int,
        sectionTitle: String,
        requestUrl: String,
    ): HomePageResponse {
        val url = "${requestUrl}get-animes"

        val sectionCount = getSectionCount("random")
        val (titles, total) = fetchRandomTitles(url, sectionCount)

        return newHomePageResponse(
            HomePageList(
                name = sectionTitle,
                list = searchResponseBuilder(titles),
                isHorizontalImages = false
            ),
            page < 5 && total > sectionCount
        )
    }

    private fun getCurrentItalianDayName(): String {
        val formatter = SimpleDateFormat("EEEE", Locale.ITALIAN)
        return formatter.format(Date()).replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.ITALIAN) else it.toString()
        }
    }

    private fun normalizeDayName(dayName: String?): String {
        return Normalizer.normalize(dayName.orEmpty(), Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .trim()
            .lowercase(Locale.ROOT)
    }

    private fun getDataPerHomeSection(section: String) = when (section) {
        "advanced" -> AnimeUnityPlugin.getAdvancedSearchRequestData(sharedPref)
        "popular" -> RequestData(orderBy = Str("Popolarità"), dubbed = 0)
        "upcoming" -> RequestData(status = Str("In Uscita"), dubbed = 0)
        "best" -> RequestData(orderBy = Str("Valutazione"), dubbed = 0)
        "ongoing" -> RequestData(orderBy = Str("Popolarità"), status = Str("In Corso"), dubbed = 0)
        else -> RequestData()
    }

    // FORK: la ricerca faceva una sola POST con offset 0 e ignorava "tot", e siccome
    // l'overload senza pagina viene incapsulato da CloudStream con hasNext=false, oltre i
    // primi 30 risultati non si andava mai. Ora si impagina a blocchi di ARCHIVE_BATCH_SIZE.
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = "$mainUrl/archivio/get-animes"
        val offset = (page - 1).coerceAtLeast(0) * ARCHIVE_BATCH_SIZE
        val responseObject = fetchArchiveBatch(url, RequestData(title = query, offset = offset, dubbed = 0))
        val titles = responseObject.titles ?: emptyList()
        val hasNext = titles.isNotEmpty() && offset + titles.size < responseObject.total

        return newSearchResponseList(searchResponseBuilder(titles), hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1)?.items ?: emptyList()

    // FORK: ponte INVERSO da id AniList/MyAnimeList alla pagina del titolo. Lo usano HomeIta
    // (le sue card portano anilist.co/anime/<id>) e la Libreria di CloudStream (aprire un
    // titolo dalla propria lista). L'archivio si interroga per titolo — l'endpoint non filtra
    // per id — ma poi si sceglie SOLO il record il cui anilist_id/mal_id coincide esattamente:
    // mai per somiglianza di titolo, che e' la fonte classica di "episodio sbagliato".
    override val supportedSyncNames = setOf(SyncIdName.Anilist, SyncIdName.MyAnimeList)

    override suspend fun getLoadUrl(name: SyncIdName, id: String): String? {
        // SyncRedirector passa l'INTERO match della sua regex ("anilist.co/anime/16498"),
        // HomeIta il numero nudo: il primo gruppo di cifre e' giusto in entrambi i casi (gli
        // host non hanno cifre) e resta giusto anche con uno slug dopo l'id.
        val numericId = Regex("\\d+").find(id)?.value?.toIntOrNull() ?: return null
        val lookup = ArchiveLookup()
        var metadataFailed = false

        // I record recenti dell'archivio hanno spesso anilist_id ma NON mal_id: un record vale
        // se combacia su UNO dei due id, quindi per un id MAL serve anche l'id AniList gemello.
        // Prima Jikan (AniList va a singhiozzo), AniList solo se il primo giro non basta.
        val found = if (name == SyncIdName.MyAnimeList) {
            val jikan = metadata("jikan", numericId) { jikanTitles(numericId) }
            metadataFailed = jikan.failed
            lookup.search(jikan.titles, anilistId = null, malId = numericId)
                ?: metadata("idMal", numericId) { aniListMedia("idMal", numericId) }.let { al ->
                    metadataFailed = metadataFailed || al.failed
                    lookup.search(al.titles, al.anilistId, numericId)
                }
        } else {
            metadata("id", numericId) { aniListMedia("id", numericId) }.let { al ->
                metadataFailed = al.failed
                lookup.search(al.titles, numericId, al.malId)
            }
        }

        val key = "$name:$numericId"
        if (found != null) {
            lookupFailures.remove(key)
            return found
        }
        // Il motivo va detto per intero: "non trovato" quando i metadati non sono arrivati
        // sarebbe una bugia, e l'utente rinuncerebbe a un titolo che c'e'.
        val reason = when {
            lookup.archiveError != null -> "Archivio AnimeUnity non raggiungibile (${lookup.archiveError})"
            metadataFailed && lookup.sawRecords -> "AniList non risponde: impossibile confermare il titolo, riprova"
            metadataFailed -> "MyAnimeList e AniList non rispondono: riprova piu' tardi"
            lookup.queries == 0 -> "Id sconosciuto a MyAnimeList e AniList"
            else -> "Titolo non trovato su AnimeUnity"
        }
        if (lookupFailures.size > MAX_LOOKUP_FAILURES) {
            lookupFailures.keys.firstOrNull()?.let(lookupFailures::remove)
        }
        lookupFailures[key] = reason
        Log.w("AnimeUnity", "getLoadUrl($name $numericId): $reason")
        return null
    }

    /** Metadati di un titolo: `failed` distingue "non ha risposto" da "ha risposto: non esiste". */
    data class Metadata(
        val anilistId: Int? = null,
        val malId: Int? = null,
        val titles: List<String> = emptyList(),
        val failed: Boolean = false,
    )

    /** Le stesse domande tornano a ogni card e a ogni click: la risposta si tiene in memoria. */
    private suspend fun metadata(field: String, id: Int, fetch: suspend () -> Metadata): Metadata {
        val key = "$field:$id"
        val now = System.currentTimeMillis()
        metadataCache[key]?.let { (stamp, cached) ->
            val ttl = if (cached.failed) METADATA_FAIL_TTL_MS else METADATA_TTL_MS
            if (now - stamp < ttl) return cached
        }
        val fresh = fetch()
        metadataCache[key] = now to fresh
        return fresh
    }

    /**
     * Una risoluzione: ricorda i record gia' scaricati (valgono anche per gli id scoperti dopo),
     * i titoli gia' interrogati e l'eventuale errore dell'archivio, per spiegare un fallimento.
     */
    private inner class ArchiveLookup {
        var queries = 0
        var archiveError: String? = null
        private val seen = mutableListOf<Anime>()
        private val queried = mutableSetOf<String>()
        val sawRecords get() = seen.isNotEmpty()

        private fun pick(anilistId: Int?, malId: Int?): String? {
            val matches = seen.filter { r ->
                (anilistId != null && r.anilistId == anilistId) || (malId != null && r.malId == malId)
            }
            // sub e dub sono record distinti con lo stesso id: preferisco il sub
            val hit = matches.firstOrNull { it.dub == 0 } ?: matches.firstOrNull() ?: return null
            return "$mainUrl/anime/${hit.id}-${hit.slug}"
        }

        /**
         * Cerca per titolo (intero, poi senza suffisso di stagione: l'archivio cerca per
         * sottostringa del SUO titolo) e accetta SOLO un record con id esatto; al massimo
         * MAX_LOOKUP_PAGES blocchi per query, perche' un franchise con piu' di 30 record puo'
         * nascondere il bersaglio.
         */
        suspend fun search(titles: List<String>, anilistId: Int?, malId: Int?): String? {
            // i record del giro precedente valgono anche per gli id appena scoperti
            pick(anilistId, malId)?.let { return it }
            if (archiveError != null) return null

            val queryTitles = (titles + titles.map(::withoutSeasonSuffix)).filter { it.isNotBlank() }.distinct()
            for (title in queryTitles) {
                if (!queried.add(title)) continue
                var offset = 0
                while (true) {
                    val response = runCatching {
                        fetchArchiveBatch("$mainUrl/archivio/get-animes", RequestData(title = title, offset = offset, dubbed = 0))
                    }.onFailure {
                        if (it is CancellationException) throw it
                        archiveError = it.message ?: it.javaClass.simpleName
                        Log.w("AnimeUnity", "getLoadUrl: archivio fallito per '$title': ${it.message}")
                    }.getOrNull()
                    // un archivio in errore lo e' per tutti i titoli: insistere significherebbe
                    // decine di handshake e di POST per un solo click
                    ?: return null
                    queries++
                    val records = response.titles.orEmpty()
                    seen += records
                    pick(anilistId, malId)?.let { return it }
                    offset += records.size
                    if (records.isEmpty() || offset >= response.total || offset >= ARCHIVE_BATCH_SIZE * MAX_LOOKUP_PAGES) break
                }
            }
            return null
        }
    }

    private suspend fun jikanTitles(malId: Int): Metadata {
        val text = runCatching { app.get("https://api.jikan.moe/v4/anime/$malId").let { it to it.text } }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull() ?: return Metadata(failed = true)
        // 404 = "non esiste", 429/5xx = "non risponde": all'utente vanno detti in modo diverso
        if (!text.first.isSuccessful) return Metadata(failed = text.first.code != 404)
        val data = runCatching { JSONObject(text.second).getJSONObject("data") }.getOrNull()
            ?: return Metadata(failed = true)
        val titles = listOf("title", "title_english")
            .mapNotNull { k -> data.optString(k).takeIf { it.isNotBlank() && it != "null" } }
            .distinct()
        return Metadata(malId = malId, titles = titles)
    }

    /** Id gemello e titoli (romaji, inglese) di un media AniList, cercato per `id` o per `idMal`. */
    private suspend fun aniListMedia(field: String, id: Int): Metadata {
        val query = "query (\$id: Int) { Media($field: \$id, type: ANIME) { id idMal title { romaji english } } }"
        val payload = JSONObject().put("query", query).put("variables", JSONObject().put("id", id)).toString()
        val response = runCatching {
            app.post(
                "https://graphql.anilist.co",
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                requestBody = payload.toRequestBody("application/json; charset=utf-8".toMediaType()),
            ).let { it to it.text }
        }.onFailure { if (it is CancellationException) throw it }
            .getOrNull() ?: return Metadata(failed = true)
        if (!response.first.isSuccessful && response.first.code != 404) return Metadata(failed = true)
        val media = runCatching { JSONObject(response.second).getJSONObject("data").getJSONObject("Media") }.getOrNull()
            ?: return Metadata(failed = false) // AniList ha risposto: quell'id non esiste
        val t = media.optJSONObject("title")
        val titles = listOf("romaji", "english")
            .mapNotNull { k -> t?.optString(k)?.takeIf { it.isNotBlank() && it != "null" } }
            .distinct()
        return Metadata(
            anilistId = media.optInt("id").takeIf { it > 0 },
            malId = media.optInt("idMal").takeIf { it > 0 },
            titles = titles,
        )
    }

    /** "Blue Box Season 2" -> "Blue Box", "Re:Zero Season 3 Part 2" -> "Re:Zero" (al massimo due suffissi). */
    private fun withoutSeasonSuffix(title: String): String {
        var t = title
        repeat(2) {
            val stripped = t.replace(SEASON_SUFFIX, "").trimEnd(' ', ':', '-', '\u2013')
            if (stripped == t || stripped.isEmpty()) return t
            t = stripped
        }
        return t
    }

    override suspend fun load(url: String): LoadResponse {
        // Un URL AniList/MAL arriva qui solo se il SyncRedirector non ha trovato il titolo
        // (getLoadUrl -> null, ripiego sull'URL originale): meglio un messaggio chiaro che
        // un errore di parsing sulla pagina di anilist.co.
        if (SYNC_HOSTS.any { url.contains(it) }) {
            val name = if (url.contains("myanimelist.net/")) SyncIdName.MyAnimeList else SyncIdName.Anilist
            val id = Regex("\\d+").find(url)?.value?.toIntOrNull()
            throw ErrorLoadingException(
                id?.let { lookupFailures["$name:$it"] } ?: "Titolo non trovato su AnimeUnity"
            )
        }
        val animePage = app.get(url).document
        val currentPageData = parseAnimePageData(animePage)
        val currentAnime = currentPageData.anime
        val shouldMergeVariants = shouldUseUnifiedDubSubCards()
        val variants = if (shouldMergeVariants) findAnimeVariants(currentAnime) else listOf(currentAnime)

        val subAnime = variants.firstOrNull { !isDubAnime(it) } ?: currentAnime.takeIf { !isDubAnime(it) }
        val dubAnime = variants.firstOrNull { isDubAnime(it) } ?: currentAnime.takeIf { isDubAnime(it) }

        val subPageData = when {
            subAnime == null -> null
            subAnime.id == currentAnime.id -> currentPageData
            !shouldMergeVariants -> null
            else -> fetchAnimePageData(getAnimeUrl(subAnime))
        }

        val dubPageData = when {
            dubAnime == null -> null
            dubAnime.id == currentAnime.id -> currentPageData
            !shouldMergeVariants -> null
            else -> fetchAnimePageData(getAnimeUrl(dubAnime))
        }

        val primaryAnime = subPageData?.anime ?: dubPageData?.anime ?: currentAnime
        val title = getAnimeTitle(primaryAnime)
        val relatedAnimes = groupAnimeCards(currentPageData.relatedAnime).amap { entry ->
            val anime = entry.anime
            val relatedTitle = getAnimeTitle(anime)
            val poster = getImage(anime.imageUrl, anime.anilistId)
            newAnimeSearchResponse(
                name = withoutDubSuffix(relatedTitle),
                url = "$mainUrl/anime/${anime.id}-${anime.slug}",
                type = if (anime.type == "TV") TvType.Anime
                else if (anime.type == "Movie" || anime.episodesCount == 1) TvType.AnimeMovie
                else TvType.OVA
            ) {
                if (shouldShowDubSub()) {
                    addDubStatus(getMiniCardDubStatus(entry.hasDub))
                }
                addPoster(poster)
            }
        }

        val subEpisodeMap = buildEpisodeSourceMap(subPageData?.anime, subPageData?.episodes.orEmpty())
        val dubEpisodeMap = buildEpisodeSourceMap(dubPageData?.anime, dubPageData?.episodes.orEmpty())
        val subEpisodes = if (shouldMergeVariants) {
            buildMergedEpisodes(
                primaryEpisodes = subEpisodeMap,
                fallbackEpisodes = dubEpisodeMap,
                subEpisodes = subEpisodeMap,
                dubEpisodes = dubEpisodeMap,
            )
        } else {
            buildMergedEpisodes(
                primaryEpisodes = subEpisodeMap,
                fallbackEpisodes = linkedMapOf(),
                subEpisodes = subEpisodeMap,
                dubEpisodes = linkedMapOf(),
            )
        }
        val dubEpisodes = if (shouldMergeVariants) {
            buildMergedEpisodes(
                primaryEpisodes = dubEpisodeMap,
                fallbackEpisodes = subEpisodeMap,
                subEpisodes = subEpisodeMap,
                dubEpisodes = dubEpisodeMap,
                fallbackNamePrefix = "[SUB] - ",
            )
        } else {
            buildMergedEpisodes(
                primaryEpisodes = dubEpisodeMap,
                fallbackEpisodes = linkedMapOf(),
                subEpisodes = linkedMapOf(),
                dubEpisodes = dubEpisodeMap,
            )
        }
        val hasSub = subEpisodeMap.isNotEmpty()
        val hasDub = dubEpisodeMap.isNotEmpty()
        val trailerUrl = getTrailerUrl(primaryAnime)

        return newAnimeLoadResponse(
            name = title.replace(" (ITA)", ""),
            url = getAnimeUrl(primaryAnime),
            type = if (primaryAnime.type == "TV") TvType.Anime
            else if (primaryAnime.type == "Movie" || primaryAnime.episodesCount == 1) TvType.AnimeMovie
            else TvType.OVA,
        ) {
            this.posterUrl = getImage(primaryAnime.imageUrl, primaryAnime.anilistId)
            primaryAnime.cover?.let { this.backgroundPosterUrl = getBanner(it) }
            this.year = primaryAnime.date.toIntOrNull()
            addScore(primaryAnime.score)
            addDuration(primaryAnime.episodesLength.toString() + " minuti")
            when {
                hasSub && hasDub -> {
                    addEpisodes(DubStatus.Subbed, subEpisodes)
                    addEpisodes(DubStatus.Dubbed, dubEpisodes)
                }
                hasDub -> {
                    addEpisodes(DubStatus.Dubbed, dubEpisodes)
                }
                hasSub -> {
                    addEpisodes(DubStatus.Subbed, subEpisodes)
                }
            }
            addAniListId(primaryAnime.anilistId)
            addMalId(primaryAnime.malId)
            if (trailerUrl != null) {
                addTrailer(trailerUrl)
            }
            this.showStatus = getShowStatus(primaryAnime.status)
            this.plot = primaryAnime.plot
            val audioTag = when {
                hasSub && hasDub -> "\uD83C\uDDEE\uD83C\uDDF9  Italiano / \uD83C\uDDEF\uD83C\uDDF5  Giapponese"
                hasDub -> "\uD83C\uDDEE\uD83C\uDDF9  Italiano"
                else -> "\uD83C\uDDEF\uD83C\uDDF5  Giapponese"
            }
            this.tags = listOfNotNull(audioTag, getStatusTag(primaryAnime.status)) + primaryAnime.genres.map { genre ->
                genre.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            }
            this.comingSoon = primaryAnime.status == "In uscita prossimamente"
            this.recommendations = relatedAnimes
        }
    }

    private fun getBanner(imageUrl: String): String {
        if (imageUrl.isNotEmpty()) {
            try {
                val fileName = imageUrl.substringAfterLast("/")
                return "https://${getImageCdnHost()}/anime/$fileName"
            } catch (_: Exception) {}
        }
        return imageUrl
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val playbackData = runCatching { parseJson<EpisodePlaybackData>(data) }.getOrNull()
        val playerSources = playbackData?.let(::buildPlayerSourceOptions)
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(PlayerSourceOption(label = "[SOURCE]", url = data))

        val shouldLabelSources = playerSources.size > 1

        // FORK: il ciclo non isolava le sorgenti, quindi un'eccezione sulla PRIMA (rete,
        // pagina 5xx, script VixCloud assente) usciva da loadLinks e le successive non
        // venivano nemmeno provate — annullando il fallback fra sub e dub. Si restituiva
        // inoltre sempre true anche senza aver emesso un link: oggi l'app non consuma quel
        // valore, quindi il cambio non altera il comportamento, ma rende onesto il contratto
        // della funzione e corregge la schermata di test dei provider.
        var emitted = false
        playerSources.forEach { playerSource ->
            runCatching {
                val document = app.get(playerSource.url).document
                val sourceUrl = document.select("video-player").attr("embed_url")
                if (sourceUrl.isBlank()) return@runCatching

                val sourceSuffix = if (shouldLabelSources) " ${playerSource.label}" else ""
                VixCloudExtractor(
                    sourceName = "VixCloud$sourceSuffix",
                    displayName = "AnimeUnity$sourceSuffix",
                ).getUrl(
                    url = sourceUrl,
                    referer = mainUrl,
                    subtitleCallback = subtitleCallback,
                    callback = { link -> emitted = true; callback(link) }
                )
            }.onFailure {
                Log.w("AnimeUnity", "Sorgente '${playerSource.label}' fallita: ${it.message}")
            }
        }

        return emitted
    }
}

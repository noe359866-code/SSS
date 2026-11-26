package app.cineflux.data.catalog

import app.cineflux.core.CatalogPlanRequest
import app.cineflux.core.CatalogPlanRow
import app.cineflux.core.CatalogPlanSource
import app.cineflux.core.Messages
import app.cineflux.core.NativeCore
import app.cineflux.data.Http
import app.cineflux.data.SettingsStore
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Catálogo construido con addons de catálogo (Nuvio y Cinemeta por defecto).
 *
 * El núcleo C++ decide qué filas pedir a partir de los manifests; aquí solo se
 * descargan, se mapean a [MediaItem] y se cachean. Las fichas salen del recurso
 * `meta` del addon (Nuvio primero, Cinemeta como respaldo). Sin addons no se
 * inventan títulos: la pantalla queda vacía con un aviso humano.
 */
class CatalogRepository(
    private val settings: SettingsStore,
    private val http: Http,
    private val rt: RtApi
) {
    private data class Source(
        val manifestUrl: String,
        val baseUrl: String,
        val manifest: JsonObject,
        val id: String,
        val name: String
    )

    @Volatile
    private var sourceCache: List<Source> = emptyList()

    @Volatile
    private var sourceAt: Long = 0

    @Volatile
    private var cachedHome: CatalogSnapshot? = null

    @Volatile
    private var cachedAt: Long = 0

    @Volatile
    private var cachedSignature: String = ""

    private val detailCache = ConcurrentHashMap<String, DetailData>()

    suspend fun home(force: Boolean = false): CatalogSnapshot {
        val signature = settings.value.catalogSources.joinToString("|")
        if (!force && signature == cachedSignature && cachedHome != null &&
            System.currentTimeMillis() - cachedAt < 10 * 60_000
        ) {
            return cachedHome!!
        }
        val sources = loadSources(force)
        if (sources.isEmpty()) {
            return CatalogSnapshot(message = Messages.catalog)
        }
        val rows = plan(query = "", limit = 10, perSource = 5)
        if (rows.isEmpty()) {
            return CatalogSnapshot(message = Messages.catalog)
        }
        val built = coroutineScope {
            rows.map { row -> async { fetchRow(row) } }.awaitAll()
        }.filterNotNull()

        if (built.isEmpty()) {
            return CatalogSnapshot(message = Messages.network)
        }

        // Rotten Tomatoes solo en la primera fila: suficiente para el héroe y
        // sin gastar la cuota de OMDb en toda la portada.
        val scored = built.mapIndexed { index, row ->
            if (index == 0) row.copy(items = enrich(row.items, 6)) else row
        }
        return CatalogSnapshot(rows = scored).also {
            cachedHome = it
            cachedAt = System.currentTimeMillis()
            cachedSignature = signature
        }
    }

    suspend fun browse(type: String): List<HomeRow> {
        if (loadSources().isEmpty()) return emptyList()
        val rows = plan(query = "", types = listOf(type), limit = 8, perSource = 6)
        return coroutineScope {
            rows.map { row -> async { fetchRow(row, type) } }.awaitAll()
        }.filterNotNull()
    }

    suspend fun search(query: String): List<MediaItem> {
        if (query.isBlank()) return emptyList()
        val rows = plan(query = query, limit = 6, perSource = 3)
        if (rows.isEmpty()) return emptyList()
        val items = coroutineScope {
            rows.map { row -> async { fetchItems(row, limit = 20) } }.awaitAll()
        }.flatten()
        return items.distinctBy { it.key }
            .sortedByDescending { maxOf(it.tmdbRating, it.imdbRating) }
            .take(40)
    }

    suspend fun detail(key: String, type: String): DetailData {
        detailCache[key]?.let { return it }
        val sources = loadSources()
        if (sources.isEmpty()) throw IllegalArgumentException(Messages.catalog)

        // Nuvio primero (metadatos TMDB en español/inglés), Cinemeta después.
        val ordered = sources.sortedBy { if (it.id.contains("nuvio", true)) 0 else 1 }
        for (source in ordered) {
            val payload = http.tryJson("${source.baseUrl}/meta/$type/$key.json") ?: continue
            val meta = payload.obj("meta") ?: continue
            val item = metaToItem(meta, type) ?: continue
            val seasons = seasonsFrom(meta, item)
            val scores = runCatching { rt.scores(item) }.getOrDefault(RtScores())
            val full = item.copy(
                tomato = scores.tomato,
                audience = scores.audience,
                seasons = seasons.size
            )
            val detail = DetailData(item = full, seasons = seasons)
            detailCache[key] = detail
            detailCache[full.key] = detail
            return detail
        }
        throw IllegalArgumentException(Messages.catalog)
    }

    suspend fun seasonEpisodes(item: MediaItem, season: Int): List<EpisodeInfo> {
        detailCache[item.key]?.let { detail ->
            detail.seasons.firstOrNull { it.number == season }?.let { return it.episodes }
        }
        val detail = runCatching { detail(item.key, item.type) }.getOrNull() ?: return emptyList()
        return detail.seasons.firstOrNull { it.number == season }?.episodes ?: emptyList()
    }

    /** Identificadores para los plugins: IMDb es el preferido. */
    suspend fun identifiers(item: MediaItem): MediaItem {
        var imdb = if (item.key.startsWith("tt")) item.key else item.imdbId
        var tmdb = when {
            item.key.startsWith("tmdb:") -> item.key.removePrefix("tmdb:")
            else -> item.tmdbId
        }
        if (imdb.isBlank() && tmdb.isNotBlank()) {
            // Nuvio resuelve tmdb:… a tt…; conviene tener IMDb antes de pedir streams.
            val resolved = runCatching { detail(item.key, item.type) }.getOrNull()
            if (resolved != null) {
                imdb = resolved.item.imdbId
                if (tmdb.isBlank()) tmdb = resolved.item.tmdbId
            }
        }
        if (imdb == item.imdbId && tmdb == item.tmdbId) return item
        return item.copy(imdbId = imdb, tmdbId = tmdb)
    }

    // MARK: - Manifests y planificación

    private suspend fun loadSources(force: Boolean = false): List<Source> {
        if (settings.value.catalogSources.isEmpty()) {
            sourceCache = emptyList()
            return emptyList()
        }
        if (!force && sourceCache.isNotEmpty() &&
            System.currentTimeMillis() - sourceAt < 30 * 60_000
        ) {
            return sourceCache
        }
        val urls = settings.value.catalogSources
        if (urls.isEmpty()) return emptyList()
        val loaded = coroutineScope {
            urls.map { url -> async { fetchSource(url) } }.awaitAll()
        }.filterNotNull()
        if (loaded.isNotEmpty()) {
            sourceCache = loaded
            sourceAt = System.currentTimeMillis()
        }
        return loaded.ifEmpty { sourceCache }
    }

    private suspend fun fetchSource(url: String): Source? {
        val text = runCatching { http.text(url) }.getOrNull() ?: return null
        val manifest = runCatching { Json.parseToJsonElement(text) as? JsonObject }
            .getOrNull() ?: return null
        val id = manifest.str("id")
        if (id.isBlank()) return null
        return Source(
            manifestUrl = url,
            baseUrl = baseUrlOf(url),
            manifest = manifest,
            id = id,
            name = manifest.str("name").ifBlank { "Catálogo" }
        )
    }

    private suspend fun plan(
        query: String,
        types: List<String> = listOf("movie", "series"),
        limit: Int,
        perSource: Int
    ): List<CatalogPlanRow> {
        val sources = loadSources()
        if (sources.isEmpty()) return emptyList()
        val request = CatalogPlanRequest(
            types = types,
            query = query,
            limit = limit,
            perSource = perSource,
            sources = sources.map { CatalogPlanSource(it.baseUrl, it.manifest) }
        )
        return NativeCore.catalogPlan(request).rows
    }

    private suspend fun fetchRow(row: CatalogPlanRow, fallbackType: String? = null): HomeRow? {
        val items = fetchItems(row, limit = 20, fallbackType = fallbackType)
        if (items.isEmpty()) return null
        return HomeRow(
            title = prettyRowName(row),
            subtitle = "",
            badge = "",
            items = items
        )
    }

    private suspend fun fetchItems(
        row: CatalogPlanRow,
        limit: Int,
        fallbackType: String? = null
    ): List<MediaItem> {
        val payload = http.tryJson(row.url) ?: return emptyList()
        return payload.arr("metas")
            .mapNotNull { metaToItem(it, fallbackType ?: row.type) }
            .take(limit)
    }

    // MARK: - Mapeo de metadatos

    private fun metaToItem(meta: JsonObject, fallbackType: String): MediaItem? {
        val id = meta.str("id").ifBlank { meta.str("imdb_id") }
        if (id.isBlank()) return null
        val type = meta.str("type").ifBlank { fallbackType }
        if (type != "movie" && type != "series") return null
        val title = meta.str("name").ifBlank { meta.str("title") }
        if (title.isBlank()) return null

        val imdb = if (id.startsWith("tt")) id else meta.str("imdb_id")
        val tmdb = when {
            id.startsWith("tmdb:") -> id.removePrefix("tmdb:")
            meta.int("moviedb_id") > 0 -> meta.int("moviedb_id").toString()
            else -> ""
        }
        val release = meta.str("releaseInfo").ifBlank { meta.str("year") }
        val genres = (meta.strList("genres") + meta.strList("genre")).distinct()
        val tmdbRating = meta.obj("app_extras")
            ?.obj("ratings")?.obj("tmdb")?.str("rating")?.toDoubleOrNull() ?: 0.0
        val imdbRating = meta.str("imdbRating").toDoubleOrNull()
            ?: meta.obj("app_extras")?.obj("ratings")?.obj("imdb")
                ?.str("rating")?.toDoubleOrNull() ?: 0.0
        val trailer = meta.arr("trailers")
            .firstOrNull { it.str("type").equals("Trailer", true) }
            ?.str("source").orEmpty()

        return MediaItem(
            key = id,
            type = type,
            tmdbId = tmdb,
            imdbId = imdb,
            title = title,
            originalTitle = title,
            year = release.take(4).toIntOrNull() ?: 0,
            overview = meta.str("description"),
            poster = upscale(meta.str("poster")),
            backdrop = upscale(
                meta.str("background").ifBlank { meta.str("poster") }
            ),
            tmdbRating = tmdbRating,
            genres = genres,
            runtime = parseRuntime(meta.str("runtime")),
            cast = meta.strList("cast").take(10),
            trailerKey = trailer,
            originalLanguage = meta.str("language"),
            logo = meta.str("logo"),
            imdbRating = imdbRating
        )
    }

    private fun seasonsFrom(meta: JsonObject, item: MediaItem): List<SeasonInfo> {
        if (item.type != "series") return emptyList()
        val grouped = meta.arr("videos")
            .mapNotNull { video ->
                val season = video.int("season")
                val number = video.int("episode").let { if (it > 0) it else video.int("number") }
                if (season <= 0 || number <= 0) return@mapNotNull null
                season to EpisodeInfo(
                    number = number,
                    title = video.str("title").ifBlank { video.str("name") },
                    overview = video.str("overview").ifBlank { video.str("description") },
                    still = video.str("thumbnail"),
                    runtime = parseRuntime(video.str("runtime")),
                    airDate = video.str("released").ifBlank { video.str("firstAired") }
                )
            }
            .groupBy({ it.first }, { it.second })
        return grouped.keys.sorted().map { season ->
            SeasonInfo(
                number = season,
                name = "Temporada $season",
                episodes = grouped.getValue(season).sortedBy { it.number }
            )
        }
    }

    private suspend fun enrich(items: List<MediaItem>, limit: Int): List<MediaItem> {
        if (items.isEmpty() || !settings.value.rtScoresAvailable) return items
        return coroutineScope {
            items.take(limit).map { item ->
                async {
                    val scores = runCatching { rt.scores(item) }.getOrDefault(RtScores())
                    item.copy(tomato = scores.tomato, audience = scores.audience)
                }
            }.awaitAll() + items.drop(limit)
        }
    }

    private fun baseUrlOf(manifestUrl: String): String {
        val clean = manifestUrl.substringBefore("?").trimEnd('/')
        return clean.removeSuffix("/manifest.json").ifEmpty { clean }
    }

    private fun upscale(url: String): String =
        url.replace("/poster/small/", "/poster/medium/")
            .replace("/background/small/", "/background/medium/")

    private fun parseRuntime(value: String): Int {
        val text = value.lowercase()
        val hoursAt = text.indexOf('h')
        return if (hoursAt >= 0) {
            val hours = firstInt(text.substring(0, hoursAt)) ?: 0
            val minutes = firstInt(text.substring(hoursAt + 1)) ?: 0
            hours * 60 + minutes
        } else {
            firstInt(text) ?: 0
        }
    }

    private fun firstInt(text: String): Int? =
        text.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }.toIntOrNull()

    private fun prettyRowName(row: CatalogPlanRow): String {
        val key = row.catalogId
        val names = if (row.addonId.contains("cinemeta", true)) {
            mapOf(
                "top" to "Popular en IMDb",
                "imdbRating" to "Mejor valoradas"
            )
        } else {
            mapOf(
                "trending-movies" to "Tendencias en películas",
                "trending-series" to "Series en tendencia",
                "popular-movies" to "Películas populares",
                "popular-series" to "Series populares",
                "critically-acclaimed-movies" to "Aclamadas por la crítica",
                "critically-acclaimed-series" to "Series aclamadas",
                "airing-today-series" to "Hoy en emisión",
                "new-and-upcoming-movies" to "Estrenos y próximos estrenos",
                "new-and-returning-series" to "Series nuevas y de regreso",
                "netflix-movies" to "Netflix · películas",
                "netflix-series" to "Netflix · series",
                "disney-plus-movies" to "Disney+ · películas",
                "disney-plus-series" to "Disney+ · series",
                "prime-video-movies" to "Prime Video · películas",
                "prime-video-series" to "Prime Video · series",
                "international-cinema" to "Cine internacional",
                "international-series" to "Series internacionales",
                "independent-and-festival-picks" to "Independientes y festivales",
                "limited-series" to "Miniseries",
                "documentaries" to "Documentales",
                "documentary-series" to "Series documentales",
                "family-movies" to "Para toda la familia",
                "family-series" to "Series para toda la familia"
            )
        }
        return names[key] ?: row.name.ifBlank { key }
    }
}

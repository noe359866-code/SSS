package app.cineflux.data

import app.cineflux.core.AppJson
import app.cineflux.core.NativeCore
import app.cineflux.core.SkipSegment
import app.cineflux.core.SkipSource
import app.cineflux.data.catalog.MediaItem
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Saltos de intro, recap y créditos.
 *
 * Descarga los datos de TheIntroDB, IntroDB, AniSkip y Anime Skip en paralelo y
 * deja que el núcleo C++ los entienda, recorte y combine. Cualquier fallo de un
 * servicio se ignora: nunca hay mensajes de error ni esperas bloqueantes.
 */
class SkipTimesRepository(
    private val settings: SettingsStore,
    private val http: Http
) {
    private val memory = ConcurrentHashMap<String, List<SkipSegment>>()
    private val malIds = ConcurrentHashMap<String, Int>()
    private val anilistIds = ConcurrentHashMap<String, Int>()

    suspend fun segments(
        item: MediaItem,
        season: Int,
        episode: Int,
        durationMs: Long
    ): List<SkipSegment> {
        val config = settings.value
        if (!config.skipSegmentsEnabled || durationMs < 60_000) return emptyList()
        if (item.imdbId.isBlank() && item.tmdbId.isBlank()) return emptyList()

        val key = cacheKey(item, season, episode)
        memory[key]?.let { return it }
        readCache(key)?.let {
            memory[key] = it
            return it
        }

        val sources = coroutineScope {
            val theIntroDb = async {
                runCatching { fetchTheIntroDb(item, season, episode, durationMs) }.getOrNull()
            }
            val introDb = async {
                runCatching { fetchIntroDb(item, season, episode) }.getOrNull()
            }
            val aniSkip = async {
                runCatching { fetchAniSkip(item, episode, durationMs) }.getOrNull()
            }
            val animeSkip = async {
                runCatching { fetchAnimeSkip(item, season, episode) }.getOrNull()
            }
            listOfNotNull(
                theIntroDb.await(),
                introDb.await(),
                aniSkip.await(),
                animeSkip.await()
            )
        }
        if (sources.isEmpty()) return emptyList()

        val merged = NativeCore.skip(durationMs, sources).segments
        memory[key] = merged
        if (merged.isNotEmpty()) writeCache(key, merged)
        return merged
    }

    /** Olvida la caché de un título tras cambiar de perfil o de duración. */
    fun invalidate(item: MediaItem) {
        val prefix = "${item.key}|"
        memory.keys.filter { it.startsWith(prefix) }.forEach { memory.remove(it) }
    }

    // TheIntroDB (v4)

    private suspend fun fetchTheIntroDb(
        item: MediaItem,
        season: Int,
        episode: Int,
        durationMs: Long
    ): SkipSource? {
        val query = when {
            item.imdbId.isNotBlank() -> "imdb_id=${enc(item.imdbId)}"
            item.tmdbId.isNotBlank() -> "tmdb_id=${enc(item.tmdbId)}"
            else -> return null
        }
        val extra = if (item.isSeries) {
            "&season=$season&episode=$episode&duration_ms=$durationMs"
        } else {
            "&duration_ms=$durationMs"
        }
        val payload = http.tryJson("https://api.theintrodb.org/v4/media?$query$extra") ?: return null
        return SkipSource("theintrodb", payload)
    }

    // IntroDB.app

    private suspend fun fetchIntroDb(item: MediaItem, season: Int, episode: Int): SkipSource? {
        if (item.imdbId.isBlank()) return null
        val query = if (item.isSeries) {
            "imdb_id=${enc(item.imdbId)}&season=$season&episode=$episode"
        } else {
            "imdb_id=${enc(item.imdbId)}&is_movie=true"
        }
        val payload = http.tryJson("https://api.introdb.app/segments?$query") ?: return null
        return SkipSource("introdb", payload)
    }

    // AniSkip (MyAnimeList)

    private suspend fun fetchAniSkip(
        item: MediaItem,
        episode: Int,
        durationMs: Long
    ): SkipSource? {
        if (!item.isAnime || episode <= 0) return null
        val malId = resolveMalId(item) ?: return null
        val seconds = durationMs / 1000
        val types = "types%5B%5D=op&types%5B%5D=ed&types%5B%5D=mixed-op" +
            "&types%5B%5D=mixed-ed&types%5B%5D=recap"
        val url = "https://api.aniskip.com/v2/skip-times/$malId/$episode?$types&episodeLength=$seconds"
        val payload = http.tryJson(url) ?: return null
        if (payload["found"]?.let { it is JsonPrimitive && it.content == "false" } == true) return null
        return SkipSource("aniskip", payload)
    }

    private suspend fun resolveMalId(item: MediaItem): Int? {
        val key = normalize(item.title)
        malIds[key]?.let { return it }
        val search = item.title.ifBlank { item.originalTitle }
        if (search.isBlank()) return null
        val payload = http.tryJson(
            "https://api.jikan.moe/v4/anime?q=${enc(search)}&limit=5&type=tv"
        ) ?: return null
        val candidates = (payload["data"] as? JsonArray).orEmpty()
        var best: Int? = null
        var bestScore = Int.MAX_VALUE
        for (candidate in candidates) {
            val node = candidate as? JsonObject ?: continue
            val id = (node["mal_id"] as? JsonPrimitive)?.intOrNull ?: continue
            val titles = buildList {
                (node["title"] as? JsonPrimitive)?.content?.let { add(it) }
                ((node["titles"] as? JsonArray).orEmpty()).forEach { entry ->
                    ((entry as? JsonObject)?.get("title") as? JsonPrimitive)?.content?.let { add(it) }
                }
            }
            val normalized = titles.map { normalize(it) }
            val exact = normalized.any { it == key }
            val related = normalized.any { it.isNotEmpty() && (it.contains(key) || key.contains(it)) }
            val year = (node["year"] as? JsonPrimitive)?.intOrNull ?: 0
            val yearClose = item.year <= 0 || year <= 0 || kotlin.math.abs(year - item.year) <= 1
            // Sin coincidencia razonable no se consulta AniSkip: evita saltos de
            // otra serie con el mismo nombre traducido.
            if (!exact && !(related && yearClose)) continue
            val score = if (exact) 0 else 20 + kotlin.math.abs(year - item.year)
            if (score < bestScore) {
                bestScore = score
                best = id
            }
        }
        if (best != null) malIds[key] = best
        return best
    }

    // Anime Skip (GraphQL, opcional)

    private suspend fun fetchAnimeSkip(
        item: MediaItem,
        season: Int,
        episode: Int
    ): SkipSource? {
        val clientId = settings.value.animeSkipClientId.trim()
        if (clientId.isBlank() || !item.isAnime || episode <= 0) return null
        val anilistId = resolveAniListId(item) ?: return null
        val headers = mapOf(
            "X-Client-ID" to clientId,
            "Content-Type" to "application/json"
        )

        val showQuery = """
            query FindShow($externalId: Int!, $service: ExternalService!) {
              findShowsByExternalId(externalId: $externalId, service: $service) { id name }
            }
        """.trimIndent()
        val showPayload = http.postJson(
            ANIME_SKIP_GRAPHQL,
            graphqlBody(showQuery, buildJsonObject {
                put("externalId", anilistId)
                put("service", "ANILIST")
            }),
            headers
        )
        val shows = ((showPayload["data"] as? JsonObject)
            ?.get("findShowsByExternalId") as? JsonArray).orEmpty()
        val showId = (shows.firstOrNull() as? JsonObject)?.get("id")?.let {
            (it as? JsonPrimitive)?.content
        } ?: return null

        val episodesQuery = """
            query Episodes($showId: ID!) {
              findEpisodesByShowId(showId: $showId) {
                number absoluteNumber season
                timestamps { at type { name } }
              }
            }
        """.trimIndent()
        val episodesPayload = http.postJson(
            ANIME_SKIP_GRAPHQL,
            graphqlBody(episodesQuery, buildJsonObject { put("showId", showId) }),
            headers
        )
        val episodes = ((episodesPayload["data"] as? JsonObject)
            ?.get("findEpisodesByShowId") as? JsonArray).orEmpty()
        val chosen = episodes
            .mapNotNull { it as? JsonObject }
            .firstOrNull { node ->
                val absolute = (node["absoluteNumber"] as? JsonPrimitive)?.intOrNull ?: 0
                val number = (node["number"] as? JsonPrimitive)?.intOrNull ?: 0
                val nodeSeason = (node["season"] as? JsonPrimitive)?.intOrNull ?: 0
                absolute == episode || (number == episode && (season <= 0 || nodeSeason == 0 || nodeSeason == season))
            } ?: return null
        val timestamps = (chosen["timestamps"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
        if (timestamps.isEmpty()) return null
        return SkipSource("animeskip", buildJsonObject {
            put("timestamps", buildJsonArray {
                timestamps.forEach { stamp ->
                    add(buildJsonObject {
                        (stamp["at"] as? JsonPrimitive)?.let { put("at", it) }
                        val type = stamp["type"] as? JsonObject
                        put("type", buildJsonObject {
                            (type?.get("name") as? JsonPrimitive)?.let { put("name", it) }
                        })
                    })
                }
            })
        })
    }

    private suspend fun resolveAniListId(item: MediaItem): Int? {
        val key = normalize(item.title)
        anilistIds[key]?.let { return it }
        val search = item.title.ifBlank { item.originalTitle }
        if (search.isBlank()) return null
        val payload = runCatching {
            http.postJson(
                ANILIST_GRAPHQL,
                graphqlBody(
                    "query Search(\$search: String) { Media(search: \$search, type: ANIME) { id } }",
                    buildJsonObject { put("search", search) }
                )
            )
        }.getOrNull() ?: return null
        val id = (((payload["data"] as? JsonObject)?.get("Media") as? JsonObject)
            ?.get("id") as? JsonPrimitive)?.intOrNull ?: return null
        anilistIds[key] = id
        return id
    }

    // Utilidades

    private fun cacheKey(item: MediaItem, season: Int, episode: Int): String =
        "${item.key}|$season|$episode"

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9áéíóúüñ]+"), " ").trim()

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun graphqlBody(query: String, variables: JsonObject): String =
        buildJsonObject {
            put("query", query)
            put("variables", variables)
        }.toString()

    private fun readCache(key: String): List<SkipSegment>? {
        val raw = settings.raw(CACHE_PREFIX + key.hashCode().toString()) ?: return null
        val cached = runCatching { AppJson.decodeFromString<SkipCache>(raw) }.getOrNull() ?: return null
        if (System.currentTimeMillis() - cached.savedAt > CACHE_TTL_MS) return null
        return cached.segments
    }

    private fun writeCache(key: String, segments: List<SkipSegment>) {
        val cache = SkipCache(savedAt = System.currentTimeMillis(), segments = segments)
        settings.putRaw(
            CACHE_PREFIX + key.hashCode().toString(),
            AppJson.encodeToString(SkipCache.serializer(), cache)
        )
    }

    @kotlinx.serialization.Serializable
    private data class SkipCache(
        val savedAt: Long = 0,
        val segments: List<SkipSegment> = emptyList()
    )

    private companion object {
        const val ANIME_SKIP_GRAPHQL = "https://api.anime-skip.com/graphql"
        const val ANILIST_GRAPHQL = "https://graphql.anilist.co"
        const val CACHE_PREFIX = "skip.v1."
        const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}

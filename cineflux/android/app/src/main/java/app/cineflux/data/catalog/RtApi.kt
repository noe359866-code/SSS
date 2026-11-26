package app.cineflux.data.catalog

import app.cineflux.data.Http
import app.cineflux.data.SettingsStore
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonObject

data class RtScores(val tomato: Int? = null, val audience: Int? = null) {
    val hasAny: Boolean get() = tomato != null || audience != null
}

/**
 * Puntuaciones de Rotten Tomatoes.
 *
 * Dos caminos, ambos opcionales y silenciosos:
 *  1. OMDb (clave gratuita) → devuelve el Tomatometer, sin proxies.
 *  2. Búsqueda privada de RT (mejor con proxy de red configurado).
 *
 * Si ninguna responde, la ficha simplemente no muestra tomate: nunca se
 * enseña un error de la fuente.
 */
class RtApi(private val settings: SettingsStore, private val http: Http) {

    private val cache = ConcurrentHashMap<String, RtScores>()

    suspend fun scores(item: MediaItem): RtScores {
        val key = cacheKey(item.title, item.year, item.type)
        cache[key]?.let { return it }
        var scores = if (item.imdbId.isNotBlank()) fromOmdbId(item.imdbId) else RtScores()
        if (!scores.hasAny) scores = fromOmdb(item.title, item.year, item.type)
        scores = scores.merge(fromRtSearch(item.title, item.year, item.type))
        if (scores.hasAny) cache[key] = scores
        return scores
    }

    private suspend fun fromOmdbId(imdbId: String): RtScores {
        val key = settings.value.omdbKey.trim()
        if (key.isEmpty() || imdbId.isBlank()) return RtScores()
        val params = "apikey=" + URLEncoder.encode(key, "UTF-8") +
            "&i=" + URLEncoder.encode(imdbId, "UTF-8")
        val payload = http.tryJson("https://www.omdbapi.com/?$params") ?: return RtScores()
        if (payload.str("Response").equals("False", true)) return RtScores()
        var tomato: Int? = null
        payload.arr("Ratings").forEach { rating ->
            if (rating.str("Source").contains("Rotten", true)) {
                tomato = rating.str("Value").filter { it.isDigit() }.toIntOrNull()
            }
        }
        return RtScores(tomato = tomato)
    }

    suspend fun scoresFor(title: String, year: Int, type: String): RtScores {
        if (title.isBlank()) return RtScores()
        val key = cacheKey(title, year, type)
        cache[key]?.let { return it }
        val scores = fromOmdb(title, year, type).merge(fromRtSearch(title, year, type))
        if (scores.hasAny) cache[key] = scores
        return scores
    }

    private suspend fun fromOmdb(title: String, year: Int, type: String): RtScores {
        val key = settings.value.omdbKey.trim()
        if (key.isEmpty()) return RtScores()
        val params = buildList {
            add("apikey=" + URLEncoder.encode(key, "UTF-8"))
            add("t=" + URLEncoder.encode(title, "UTF-8"))
            if (year > 0) add("y=$year")
            add("type=" + if (type == "series") "series" else "movie")
        }.joinToString("&")
        val payload = http.tryJson("https://www.omdbapi.com/?$params") ?: return RtScores()
        if (payload.str("Response").equals("False", true)) return RtScores()
        var tomato: Int? = null
        val ratings = payload.arr("Ratings")
        ratings.forEach { rating ->
            if (rating.str("Source").contains("Rotten", true)) {
                tomato = rating.str("Value").filter { it.isDigit() }.toIntOrNull()
            }
        }
        return RtScores(tomato = tomato)
    }

    private suspend fun fromRtSearch(title: String, year: Int, type: String): RtScores {
        val query = URLEncoder.encode(title, "UTF-8")
        val payload = http.tryJson(
            "https://www.rottentomatoes.com/api/private/v2.0/search?q=$query&limit=12"
        ) ?: return RtScores()

        val candidates = buildList {
            addAll(payload.arr("movies"))
            addAll(payload.arr("tvSeries"))
        }
        val target = normalize(title)
        val best = candidates
            .filter { normalize(it.str("name")).isNotEmpty() }
            .sortedByDescending { candidate ->
                var score = 0
                val name = normalize(candidate.str("name"))
                if (name == target) score += 100
                else if (name.startsWith(target) || target.startsWith(name)) score += 40
                val candidateYear = candidate.int("year")
                if (year > 0 && candidateYear > 0) score += (5 - kotlin.math.abs(candidateYear - year) * 2)
                if (type == "series" && candidate.str("type").contains("tv", true)) score += 5
                score
            }
            .firstOrNull() ?: return RtScores()

        return RtScores(
            tomato = best.int("meterScore").takeIf { it > 0 } ?: best.int("criticsScore").takeIf { it > 0 },
            audience = best.int("audienceScore").takeIf { it > 0 }
        )
    }

    private fun normalize(value: String): String =
        value.lowercase()
            .replace(Regex("[^a-z0-9áéíóúñü ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun cacheKey(title: String, year: Int, type: String) =
        "${title.lowercase()}|$year|$type"

    private fun RtScores.merge(other: RtScores): RtScores = RtScores(
        tomato = tomato ?: other.tomato,
        audience = audience ?: other.audience
    )
}

package app.cineflux.data.catalog

import kotlinx.serialization.Serializable

/** Título del catálogo (addons Nuvio/Cinemeta + Rotten Tomatoes). */
@Serializable
data class MediaItem(
    val key: String,
    val type: String,              // movie | series
    val tmdbId: String = "",
    val imdbId: String = "",
    val title: String = "",
    val originalTitle: String = "",
    val year: Int = 0,
    val overview: String = "",
    val poster: String = "",
    val backdrop: String = "",
    val tmdbRating: Double = 0.0,
    val tmdbVotes: Int = 0,
    val tomato: Int? = null,
    val audience: Int? = null,
    val genres: List<String> = emptyList(),
    val runtime: Int = 0,
    val seasons: Int = 0,
    val cast: List<String> = emptyList(),
    val trailerKey: String = "",
    val originalLanguage: String = "",
    val logo: String = "",
    val imdbRating: Double = 0.0
) {
    val isSeries: Boolean get() = type == "series"
    val isAnime: Boolean
        get() = genres.any { it.contains("anim", ignoreCase = true) } ||
            originalLanguage.equals("ja", ignoreCase = true)
    val scoreLabel: String
        get() {
            val value = if (tmdbRating > 0) tmdbRating else imdbRating
            return if (value > 0) String.format(java.util.Locale.US, "%.1f", value) else ""
        }
}

@Serializable
data class EpisodeInfo(
    val number: Int,
    val title: String = "",
    val overview: String = "",
    val still: String = "",
    val runtime: Int = 0,
    val airDate: String = ""
)

@Serializable
data class SeasonInfo(
    val number: Int,
    val name: String = "Temporada $number",
    val episodes: List<EpisodeInfo> = emptyList()
)

@Serializable
data class DetailData(
    val item: MediaItem,
    val seasons: List<SeasonInfo> = emptyList(),
    val similar: List<MediaItem> = emptyList()
)

@Serializable
data class HomeRow(
    val title: String,
    val subtitle: String = "",
    val badge: String = "",       // "RT" | "TMDB" | ""
    val items: List<MediaItem> = emptyList()
)

@Serializable
data class CatalogSnapshot(
    val rows: List<HomeRow> = emptyList(),
    val message: String = ""
)

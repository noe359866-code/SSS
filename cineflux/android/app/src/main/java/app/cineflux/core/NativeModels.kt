package app.cineflux.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Configuración JSON compartida por todas las capas. */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
}

/** Mensajes humanos. La app nunca muestra códigos de estado ni errores de fuentes. */
object Messages {
    const val noOptions = "No hay ninguna opción disponible ahora mismo para este perfil."
    const val playbackExhausted = "No encontramos una opción compatible. Prueba con otro perfil."
    const val network = "No hemos podido conectar con el servicio. Inténtalo de nuevo en un momento."
    const val manifest = "Ese enlace no es válido o está incompleto."
    const val generic = "Algo no ha salido como esperábamos. Inténtalo de nuevo."
    const val notFound = "No encontramos ese título."
    const val catalog = "No hemos podido cargar el catálogo. Revisa tus fuentes en Ajustes."
    const val needBaseUrl = "Añade el enlace del plugin."

    /** Red de seguridad: elimina códigos y URLs de cualquier texto mostrable. */
    fun scrub(text: String): String {
        var clean = text
        listOf(
            "HTTP", "http://", "https://", "CORS", "ECONN", "ERR_", "status",
            "401", "403", "404", "408", "429", "500", "502", "503", "504",
            "undefined", "null"
        ).forEach { token ->
            clean = clean.replace(token, "", ignoreCase = true)
        }
        return clean.replace(Regex("\\s+"), " ").trim()
    }
}

/** Error de dominio con mensaje ya listo para la interfaz. */
class FriendlyException(val friendly: String = Messages.generic, cause: Throwable? = null) :
    Exception(friendly, cause)

// ---------------------------------------------------------------------------
// Manifest de plugin
// ---------------------------------------------------------------------------

@Serializable
data class ManifestInfo(
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val description: String = "",
    val logo: String = "",
    val background: String = "",
    val baseUrl: String = "",
    val manifestUrl: String = "",
    val types: List<String> = emptyList(),
    val resources: List<ResourceInfo> = emptyList(),
    val catalogs: List<CatalogInfo> = emptyList(),
    val idPrefixes: List<String> = emptyList(),
    val capabilities: Capabilities = Capabilities()
)

@Serializable
data class ResourceInfo(
    val name: String = "",
    val types: List<String> = emptyList(),
    val idPrefixes: List<String> = emptyList()
)

@Serializable
data class CatalogInfo(
    val type: String = "",
    val id: String = "",
    val name: String = "",
    val extra: List<String> = emptyList()
)

@Serializable
data class Capabilities(
    val streams: Boolean = false,
    val catalog: Boolean = false,
    val meta: Boolean = false,
    val subtitles: Boolean = false
)

@Serializable
data class ManifestEnvelope(
    val ok: Boolean = false,
    val manifest: ManifestInfo? = null,
    val warnings: List<String> = emptyList(),
    val message: String = ""
)

/** Plugin instalado. `rawManifest` conserva el JSON original (manifest en crudo). */
@Serializable
data class Plugin(
    val key: String,
    val manifest: ManifestInfo,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val installedAt: Long = System.currentTimeMillis(),
    val origin: String = "url",
    val rawManifest: String = ""
) {
    val streams: Boolean get() = manifest.capabilities.streams
    val catalogs: Boolean get() = manifest.capabilities.catalog && manifest.catalogs.isNotEmpty()
    val subtitle: String get() = "${manifest.types.joinToString(", ")} · v${manifest.version}"
}

// ---------------------------------------------------------------------------
// Perfiles de reproducción
// ---------------------------------------------------------------------------

@Serializable
data class Profile(
    val id: String,
    val name: String,
    val description: String = "",
    val accent: String = "#22d3ee",
    val languages: List<String> = emptyList(),
    val targetQuality: Int = 1080,
    val maxQuality: Int = 2160,
    val allowCam: Boolean = false,
    val allowTorrent: Boolean = false,
    val preferHdr: Boolean = false,
    val preferSubtitles: Boolean = false,
    val preferDual: Boolean = true,
    val strictLanguage: Boolean = false,
    val dataSaver: Boolean = false
) {
    val summary: String
        get() {
            val languagesText = if (languages.isEmpty()) "cualquier idioma" else languages.joinToString(" · ")
            return "$languagesText · ${maxQuality}p máx"
        }
}

@Serializable
data class ProfilesEnvelope(
    val ok: Boolean = false,
    val profiles: List<Profile> = emptyList()
)

// ---------------------------------------------------------------------------
// Planificación y ranking (núcleo C++)
// ---------------------------------------------------------------------------

@Serializable
data class PlanRequest(
    val type: String,
    val imdbId: String = "",
    val tmdbId: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val addons: List<PlanAddon>
)

@Serializable
data class PlanAddon(val id: String, val manifest: ManifestInfo? = null, val baseUrl: String = "")

@Serializable
data class PlannedAddon(
    val id: String = "",
    val name: String = "",
    val ids: List<String> = emptyList(),
    val urls: List<String> = emptyList()
)

@Serializable
data class PlanEnvelope(
    val ok: Boolean = false,
    val addons: List<PlannedAddon> = emptyList(),
    val message: String = ""
)

@Serializable
data class AddonPayload(
    val id: String,
    val name: String,
    val priority: Int,
    val payload: JsonObject
)

@Serializable
data class RankOptions(
    val allowTorrents: Boolean = false,
    val limit: Int = 12,
    val includeBackups: Boolean = true
)

@Serializable
data class RankRequest(
    val profile: Profile,
    val addons: List<AddonPayload>,
    val options: RankOptions = RankOptions()
)

@Serializable
data class SubtitleTrack(
    val url: String = "",
    val lang: String = "",
    val label: String = ""
)

@Serializable
data class StreamChoice(
    val addonId: String = "",
    val addonName: String = "",
    val url: String = "",
    val externalUrl: String = "",
    val quality: Int = 0,
    val qualityLabel: String = "Auto",
    val languageLabel: String = "",
    val title: String = "",
    val hdr: Boolean = false,
    val cam: Boolean = false,
    val torrent: Boolean = false,
    val hevc: Boolean = false,
    val sizeBytes: Long = 0,
    val score: Int = 0,
    val role: String = "backup",
    val languages: List<String> = emptyList(),
    val subtitles: List<SubtitleTrack> = emptyList()
) {
    /** Etiqueta visible: solo calidad e idioma, nunca la fuente. */
    val label: String
        get() = listOf(qualityLabel, languageLabel, if (hdr) "HDR" else "")
            .filter { it.isNotBlank() }
            .joinToString(" · ")
}

@Serializable
data class RankEnvelope(
    val ok: Boolean = false,
    val received: Int = 0,
    val playable: Int = 0,
    val rejected: Int = 0,
    val message: String = "",
    val candidates: List<StreamChoice> = emptyList()
)

/** Respuesta de un addon ya traducida a lo que el núcleo entiende. */
@Serializable
data class StreamsPayload(val streams: List<JsonObject> = emptyList())

// ---------------------------------------------------------------------------
// Catálogos de addons (Nuvio · Cinemeta)
// ---------------------------------------------------------------------------

@Serializable
data class CatalogPlanSource(
    val baseUrl: String,
    val manifest: JsonObject
)

@Serializable
data class CatalogPlanRequest(
    val types: List<String> = listOf("movie", "series"),
    val query: String = "",
    val limit: Int = 12,
    val perSource: Int = 12,
    val sources: List<CatalogPlanSource> = emptyList()
)

@Serializable
data class CatalogPlanRow(
    val addonId: String = "",
    val addonName: String = "",
    val baseUrl: String = "",
    val type: String = "",
    val catalogId: String = "",
    val name: String = "",
    val url: String = "",
    val search: Boolean = false
)

@Serializable
data class CatalogPlanEnvelope(
    val ok: Boolean = false,
    val count: Int = 0,
    val message: String = "",
    val rows: List<CatalogPlanRow> = emptyList()
)

// ---------------------------------------------------------------------------
// Saltos de intro / recap / créditos
// ---------------------------------------------------------------------------

@Serializable
data class SkipSource(
    val source: String,
    val payload: JsonObject
)

@Serializable
data class SkipRequest(
    val durationMs: Long,
    val priority: List<String> = emptyList(),
    val sources: List<SkipSource> = emptyList()
)

@Serializable
data class SkipSegment(
    val kind: String = "",
    val label: String = "",
    val startMs: Long = 0,
    val endMs: Long = 0,
    val source: String = "",
    val confidence: Double? = null
) {
    fun contains(positionMs: Long): Boolean = positionMs + 1200 >= startMs && positionMs < endMs - 900
}

@Serializable
data class SkipEnvelope(
    val ok: Boolean = false,
    val count: Int = 0,
    val segments: List<SkipSegment> = emptyList(),
    val message: String = ""
)

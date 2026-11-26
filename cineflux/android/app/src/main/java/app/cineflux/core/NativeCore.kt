package app.cineflux.core

import android.util.Log
import kotlinx.serialization.decodeFromString

/**
 * Puente con el núcleo C++ compartido (mismo código que usa iOS).
 *
 * Todo el trabajo pesado —validar manifests, resolver identificadores, parsear
 * respuestas de plugins, deduplicar y ordenar streams según el perfil— ocurre
 * en C++. Kotlin solo hace red y presentación.
 */
object NativeCore {

    private const val TAG = "CinefluxCore"
    private const val LIBRARY = "cineflux"

    val available: Boolean by lazy {
        runCatching { System.loadLibrary(LIBRARY) }
            .onFailure { Log.w(TAG, "núcleo nativo no disponible", it) }
            .isSuccess
    }

    external fun version(): String
    external fun defaultProfiles(): String
    external fun parseManifest(manifest: String, baseUrl: String): String
    external fun planRequests(request: String): String
    external fun rankStreams(request: String): String
    external fun skipSegments(request: String): String

    external fun planCatalog(request: String): String

    fun coreVersion(): String =
        if (!available) "—" else runCatching { version() }.getOrDefault("—")

    fun manifest(input: String, baseUrl: String = ""): ManifestEnvelope {
        if (!available) return ManifestEnvelope(ok = false, message = Messages.manifest)
        return runCatching {
            AppJson.decodeFromString<ManifestEnvelope>(parseManifest(input, baseUrl))
        }.getOrElse {
            Log.w(TAG, "manifest ilegible", it)
            ManifestEnvelope(ok = false, message = Messages.manifest)
        }
    }

    fun profiles(): List<Profile> {
        if (!available) return FallbackProfiles.all
        return runCatching {
            AppJson.decodeFromString<ProfilesEnvelope>(defaultProfiles()).profiles
        }.getOrDefault(FallbackProfiles.all)
    }

    fun plan(request: PlanRequest): PlanEnvelope {
        if (!available) return PlanEnvelope(ok = false, message = Messages.noOptions)
        return runCatching {
            AppJson.decodeFromString<PlanEnvelope>(
                planRequests(AppJson.encodeToString(PlanRequest.serializer(), request))
            )
        }.getOrElse {
            Log.w(TAG, "plan ilegible", it)
            PlanEnvelope(ok = false, message = Messages.noOptions)
        }
    }

    fun catalogPlan(request: CatalogPlanRequest): CatalogPlanEnvelope {
        if (!available) return CatalogPlanEnvelope(message = Messages.network)
        return runCatching {
            AppJson.decodeFromString<CatalogPlanEnvelope>(
                planCatalog(AppJson.encodeToString(CatalogPlanRequest.serializer(), request))
            )
        }.getOrElse {
            Log.w(TAG, "catálogos ilegibles", it)
            CatalogPlanEnvelope(message = Messages.network)
        }
    }

    fun skip(durationMs: Long, sources: List<SkipSource>): SkipEnvelope {
        if (!available || durationMs <= 0 || sources.isEmpty()) return SkipEnvelope()
        val request = SkipRequest(durationMs = durationMs, sources = sources)
        return runCatching {
            AppJson.decodeFromString<SkipEnvelope>(
                skipSegments(AppJson.encodeToString(SkipRequest.serializer(), request))
            )
        }.getOrElse {
            Log.w(TAG, "saltos ilegibles", it)
            SkipEnvelope()
        }
    }

    fun rank(
        profile: Profile,
        payloads: List<AddonPayload>,
        allowTorrents: Boolean
    ): RankEnvelope {
        if (!available) return RankEnvelope(ok = false, message = Messages.noOptions)
        val request = RankRequest(
            profile = profile,
            addons = payloads,
            options = RankOptions(allowTorrents = allowTorrents, limit = 12)
        )
        return runCatching {
            AppJson.decodeFromString<RankEnvelope>(
                rankStreams(AppJson.encodeToString(RankRequest.serializer(), request))
            )
        }.getOrElse {
            Log.w(TAG, "ranking ilegible", it)
            RankEnvelope(ok = false, message = Messages.noOptions)
        }
    }
}

/**
 * Copia de los perfiles por defecto. El núcleo C++ es la fuente de verdad; esto
 * solo evita una pantalla vacía si la librería nativa no está compilada.
 */
object FallbackProfiles {
    val all: List<Profile> = listOf(
        Profile(
            id = "latino-1080", name = "Latino 1080p", accent = "#22d3ee",
            description = "Audio latino; Full HD como tope.",
            languages = listOf("latino", "dual", "es"), targetQuality = 1080, maxQuality = 1080
        ),
        Profile(
            id = "latino-4k", name = "Latino 4K", accent = "#f43f5e",
            description = "Audio latino; máxima calidad disponible.",
            languages = listOf("latino", "dual", "es"), targetQuality = 2160, maxQuality = 2160,
            preferHdr = true
        ),
        Profile(
            id = "castellano", name = "Castellano", accent = "#f59e0b",
            description = "Español de España.",
            languages = listOf("castellano", "es", "dual"), targetQuality = 1080, maxQuality = 1080
        ),
        Profile(
            id = "subtitulado", name = "Subtitulado", accent = "#a78bfa",
            description = "Voz original y subtítulos.",
            languages = listOf("en", "original"), targetQuality = 2160, maxQuality = 2160,
            preferSubtitles = true, preferDual = false, preferHdr = true
        ),
        Profile(
            id = "movil", name = "Móvil / datos", accent = "#34d399",
            description = "Menos peso, arranque rápido.",
            languages = listOf("latino", "dual", "es", "castellano"),
            targetQuality = 720, maxQuality = 1080, dataSaver = true
        ),
        Profile(
            id = "automatico", name = "Automático", accent = "#94a3b8",
            description = "La mejor opción disponible, sin filtros de idioma.",
            targetQuality = 1080, maxQuality = 2160
        )
    )
}

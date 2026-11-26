package app.cineflux.data

import app.cineflux.core.AddonPayload
import app.cineflux.core.AppJson
import app.cineflux.core.ManifestEnvelope
import app.cineflux.core.Messages
import app.cineflux.core.NativeCore
import app.cineflux.core.PlanAddon
import app.cineflux.core.PlanRequest
import app.cineflux.core.PlannedAddon
import app.cineflux.core.Plugin
import app.cineflux.core.Profile
import app.cineflux.core.RankEnvelope
import app.cineflux.core.CatalogInfo
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class InstallResult(val ok: Boolean, val message: String = "", val plugin: Plugin? = null)

/**
 * Registro de plugins. Se instalan con un manifest (URL o JSON en crudo),
 * nunca con scrapers dentro de la app.
 */
class PluginRepository(
    private val settings: SettingsStore,
    private val http: Http
) {
    private val _plugins = MutableStateFlow(load().filter { it.manifest.capabilities.streams })
    val plugins: StateFlow<List<Plugin>> = _plugins.asStateFlow()

    private val counter = AtomicInteger(_plugins.value.size)

    val streamPlugins: List<Plugin>
        get() = _plugins.value.filter { it.enabled && it.streams && it.manifest.baseUrl.isNotBlank() }
            .sortedBy { it.priority }

    val catalogPlugins: List<Pair<Plugin, CatalogInfo>>
        get() = _plugins.value.filter { it.enabled && it.catalogs }
            .flatMap { plugin -> plugin.manifest.catalogs.map { plugin to it } }

    suspend fun install(input: String, baseUrlOverride: String = ""): InstallResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return InstallResult(false, Messages.needBaseUrl)

        val isUrl = URL_PATTERN.containsMatchIn(trimmed)
        if (!isUrl) return InstallResult(false, Messages.needBaseUrl)
        val envelope: ManifestEnvelope = when {
            isUrl -> {
                val body = runCatching { http.text(trimmed) }.getOrElse {
                    return InstallResult(false, Messages.network)
                }
                NativeCore.manifest(body, baseUrlOverride.trim().ifEmpty { trimmed })
            }
            else -> NativeCore.manifest(trimmed, baseUrlOverride.trim())
        }

        val manifest = envelope.manifest
        if (!envelope.ok || manifest == null) {
            return InstallResult(false, Messages.scrub(envelope.message).ifEmpty { Messages.manifest })
        }
        if (manifest.baseUrl.isBlank()) {
            return InstallResult(false, Messages.needBaseUrl)
        }
        if (manifest.resources.isEmpty()) {
            return InstallResult(false, Messages.manifest)
        }
        if (!manifest.capabilities.streams) {
            return InstallResult(
                false,
                "Ese plugin solo aporta catálogo. Añádelo como fuente en Ajustes → Catálogo."
            )
        }

        val key = manifest.baseUrl.trimEnd('/')
        val existing = _plugins.value.firstOrNull { it.key == key }
        val plugin = Plugin(
            key = key,
            manifest = manifest,
            enabled = existing?.enabled ?: true,
            priority = existing?.priority ?: counter.getAndIncrement(),
            installedAt = existing?.installedAt ?: System.currentTimeMillis(),
            origin = if (isUrl) "url" else "raw",
            rawManifest = trimmed
        )
        persist(_plugins.value.filterNot { it.key == key } + plugin)
        return InstallResult(
            ok = true,
            message = "${manifest.name} instalado.",
            plugin = plugin
        )
    }

    suspend fun refresh(plugin: Plugin): InstallResult {
        val url = plugin.manifest.manifestUrl.ifBlank {
            plugin.manifest.baseUrl.trimEnd('/') + "/manifest.json"
        }
        val body = runCatching { http.text(url) }.getOrElse {
            return InstallResult(false, Messages.network)
        }
        val envelope = NativeCore.manifest(body, plugin.manifest.baseUrl)
        val manifest = envelope.manifest
        if (!envelope.ok || manifest == null) {
            return InstallResult(false, Messages.scrub(envelope.message).ifEmpty { Messages.manifest })
        }
        val updated = plugin.copy(manifest = manifest, origin = "url")
        persist(_plugins.value.map { if (it.key == plugin.key) updated else it })
        return InstallResult(true, "${manifest.name} actualizado.", updated)
    }

    fun setEnabled(key: String, enabled: Boolean) {
        persist(_plugins.value.map { if (it.key == key) it.copy(enabled = enabled) else it })
    }

    fun remove(key: String) {
        persist(_plugins.value.filterNot { it.key == key })
    }

    fun move(key: String, delta: Int) {
        val list = _plugins.value.sortedBy { it.priority }.toMutableList()
        val index = list.indexOfFirst { it.key == key }
        if (index < 0) return
        val target = (index + delta).coerceIn(0, list.size - 1)
        if (target == index) return
        val moved = list.removeAt(index)
        list.add(target, moved)
        persist(list.mapIndexed { i, plugin -> plugin.copy(priority = i) })
    }

    fun replaceAll(list: List<Plugin>) {
        persist(
            list.filter { it.manifest.capabilities.streams }
                .sortedBy { it.priority }
                .mapIndexed { i, plugin -> plugin.copy(priority = i) }
        )
    }

    /** Consulta todos los plugins y devuelve la cola ya ordenada por el perfil. */
    suspend fun resolve(
        type: String,
        imdbId: String,
        tmdbId: String,
        season: Int,
        episode: Int,
        profile: Profile
    ): RankEnvelope {
        val active = streamPlugins
        if (active.isEmpty()) return RankEnvelope(ok = false, message = Messages.noOptions)

        val plan = NativeCore.plan(
            PlanRequest(
                type = type,
                imdbId = imdbId,
                tmdbId = tmdbId,
                season = season,
                episode = episode,
                addons = active.map { PlanAddon(it.key, it.manifest) }
            )
        )
        if (!plan.ok || plan.addons.isEmpty()) {
            return RankEnvelope(ok = false, message = Messages.noOptions)
        }

        val payloads = coroutineScope {
            plan.addons.map { planned ->
                async(Dispatchers.IO) {
                    val plugin = active.firstOrNull { it.key == planned.id } ?: return@async null
                    fetchPayload(planned, plugin)
                }
            }.awaitAll().filterNotNull()
        }
        if (payloads.isEmpty()) return RankEnvelope(ok = false, message = Messages.noOptions)

        return NativeCore.rank(profile, payloads, settings.value.allowTorrents)
    }

    private suspend fun fetchPayload(planned: PlannedAddon, plugin: Plugin): AddonPayload? {
        for (url in planned.urls) {
            val body = withTimeoutOrNull(12_000) {
                runCatching { http.text(url) }.getOrNull()
            } ?: continue
            val root = runCatching { AppJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: continue
            val streams = runCatching { root["streams"] as JsonArray }.getOrNull() ?: continue
            if (streams.isEmpty()) continue
            val payload = buildJsonObject {
                put("streams", JsonArray(streams.filterIsInstance<JsonObject>()))
            }
            return AddonPayload(
                id = planned.id,
                name = planned.name.ifBlank { plugin.manifest.name },
                priority = plugin.priority,
                payload = payload
            )
        }
        return null
    }

    /** Comprobación manual desde Ajustes; mensaje humano, sin códigos. */
    suspend fun test(plugin: Plugin): InstallResult {
        val url = plugin.manifest.manifestUrl.ifBlank {
            plugin.manifest.baseUrl.trimEnd('/') + "/manifest.json"
        }
        val body = runCatching { http.text(url) }.getOrElse {
            return InstallResult(false, Messages.network)
        }
        val envelope = NativeCore.manifest(body, plugin.manifest.baseUrl)
        if (!envelope.ok) return InstallResult(false, Messages.manifest)
        val manifest = envelope.manifest ?: return InstallResult(false, Messages.manifest)
        val parts = buildList {
            add(manifest.name)
            if (manifest.capabilities.streams) add("streams")
            if (manifest.capabilities.catalog) add("${manifest.catalogs.size} catálogos")
            if (manifest.capabilities.meta) add("meta")
            if (manifest.capabilities.subtitles) add("subtítulos")
        }
        return InstallResult(true, "Responde correctamente · " + parts.joinToString(" · "))
    }

    suspend fun fetchCatalog(
        plugin: Plugin,
        catalog: CatalogInfo,
        search: String = "",
        skip: Int = 0
    ): JsonObject? {
        if (plugin.manifest.baseUrl.isBlank()) return null
        val id = buildString {
            append(catalog.type); append('/'); append(catalog.id)
            val extras = buildList {
                if (search.isNotBlank()) add("search=" + java.net.URLEncoder.encode(search, "UTF-8"))
                if (skip > 0) add("skip=$skip")
            }
            if (extras.isNotEmpty()) { append('/'); append(extras.joinToString("&")) }
        }
        val url = plugin.manifest.baseUrl.trimEnd('/') + "/catalog/" + id + ".json"
        return http.tryJson(url)
    }

    private fun persist(list: List<Plugin>) {
        val ordered = list.sortedBy { it.priority }.mapIndexed { index, plugin ->
            plugin.copy(priority = index)
        }
        _plugins.value = ordered
        counter.set(ordered.size + 1)
        settings.putRaw(
            SettingsStore.KEY_PLUGINS,
            AppJson.encodeToString(ListSerializer(Plugin.serializer()), ordered)
        )
    }

    private fun load(): List<Plugin> {
        val raw = settings.raw(SettingsStore.KEY_PLUGINS) ?: return emptyList()
        return runCatching {
            AppJson.decodeFromString(ListSerializer(Plugin.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    companion object {
        private val URL_PATTERN = Regex("^https?://", RegexOption.IGNORE_CASE)
    }
}

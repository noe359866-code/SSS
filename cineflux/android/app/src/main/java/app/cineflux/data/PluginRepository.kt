package app.cineflux.data

import app.cineflux.core.AddonPayload
import app.cineflux.core.AppJson
import app.cineflux.core.JsPlugin
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
    private val jsRuntime = JsRuntime(http)

    private val _plugins = MutableStateFlow(load().filter { it.manifest.capabilities.streams })
    val plugins: StateFlow<List<Plugin>> = _plugins.asStateFlow()

    private val _jsPlugins = MutableStateFlow(loadJs())
    val jsPlugins: StateFlow<List<JsPlugin>> = _jsPlugins.asStateFlow()

    private val counter = AtomicInteger(_plugins.value.size)
    private val jsCounter = AtomicInteger(_jsPlugins.value.size)

    val streamPlugins: List<Plugin>
        get() = _plugins.value.filter { it.enabled && it.streams && it.manifest.baseUrl.isNotBlank() }
            .sortedBy { it.priority }

    val activeJsPlugins: List<JsPlugin>
        get() = _jsPlugins.value.filter { it.enabled && it.code.isNotBlank() }
            .sortedBy { it.priority }

    val catalogPlugins: List<Pair<Plugin, CatalogInfo>>
        get() = _plugins.value.filter { it.enabled && it.catalogs }
            .flatMap { plugin -> plugin.manifest.catalogs.map { plugin to it } }

    suspend fun install(input: String, baseUrlOverride: String = ""): InstallResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return InstallResult(false, Messages.needBaseUrl)

        val isUrl = URL_PATTERN.containsMatchIn(trimmed)
        if (!isUrl) return InstallResult(false, Messages.needBaseUrl)

        // 1. Enlace directo a un archivo .js
        if (JS_URL_PATTERN.containsMatchIn(trimmed)) {
            val code = runCatching { http.text(trimmed) }.getOrElse {
                return InstallResult(false, Messages.network)
            }
            if (!jsRuntime.validate(code)) {
                return InstallResult(false, "Ese archivo JS no expone la función getStreams.")
            }
            val fileName = trimmed.substringAfterLast('/').substringBefore('?').removeSuffix(".js")
            val name = fileName.ifBlank { "Plugin JS" }
            val key = "js:$trimmed"
            val existing = _jsPlugins.value.firstOrNull { it.key == key }
            val jsPlugin = JsPlugin(
                key = key,
                id = fileName.lowercase().ifBlank { "js-${System.currentTimeMillis()}" },
                name = name,
                scriptUrl = trimmed,
                code = code,
                enabled = existing?.enabled ?: true,
                priority = existing?.priority ?: jsCounter.getAndIncrement(),
                installedAt = existing?.installedAt ?: System.currentTimeMillis()
            )
            persistJs(_jsPlugins.value.filterNot { it.key == key } + jsPlugin)
            return InstallResult(ok = true, message = "$name (JS) instalado.")
        }

        val body = runCatching { http.text(trimmed) }.getOrElse {
            return InstallResult(false, Messages.network)
        }

        // 2. Repositorio de plugins JS estilo Nuvio (manifest.json con scrapers/providers)
        val repo = NativeCore.jsRepo(body, baseUrlOverride.trim().ifEmpty { trimmed })
        if (repo.ok && repo.plugins.isNotEmpty()) {
            var installedCount = 0
            val current = _jsPlugins.value.toMutableList()
            for (entry in repo.plugins) {
                val code = withTimeoutOrNull(12_000) {
                    runCatching { http.text(entry.scriptUrl) }.getOrNull()
                } ?: continue
                if (!jsRuntime.validate(code)) continue
                val key = "js:${entry.scriptUrl}"
                val existing = current.firstOrNull { it.key == key }
                val jsPlugin = JsPlugin(
                    key = key,
                    id = entry.id,
                    name = entry.name,
                    description = entry.description,
                    version = entry.version,
                    scriptUrl = entry.scriptUrl,
                    repoUrl = trimmed,
                    repoName = repo.name,
                    code = code,
                    types = entry.types,
                    enabled = existing?.enabled ?: entry.enabledByDefault,
                    priority = existing?.priority ?: jsCounter.getAndIncrement(),
                    installedAt = existing?.installedAt ?: System.currentTimeMillis()
                )
                current.removeAll { it.key == key }
                current.add(jsPlugin)
                installedCount++
            }
            if (installedCount == 0) {
                return InstallResult(false, "No se pudo descargar ningún plugin JS válido del repositorio.")
            }
            persistJs(current)
            return InstallResult(
                ok = true,
                message = "${repo.name}: $installedCount plugin(s) JS instalados."
            )
        }

        // 3. Manifest estándar de addon (Stremio)
        val envelope: ManifestEnvelope = NativeCore.manifest(body, baseUrlOverride.trim().ifEmpty { trimmed })

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

    /** Consulta todos los plugins (HTTP + JS locales) y devuelve la cola ordenada por el perfil. */
    suspend fun resolve(
        type: String,
        imdbId: String,
        tmdbId: String,
        season: Int,
        episode: Int,
        profile: Profile
    ): RankEnvelope {
        val active = streamPlugins
        val activeJs = activeJsPlugins.filter { it.supportsType(type) }
        if (active.isEmpty() && activeJs.isEmpty()) {
            return RankEnvelope(ok = false, message = Messages.noOptions)
        }

        val payloads = coroutineScope {
            val httpJobs = if (active.isNotEmpty()) {
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
                if (plan.ok) {
                    plan.addons.map { planned ->
                        async(Dispatchers.IO) {
                            val plugin = active.firstOrNull { it.key == planned.id } ?: return@async null
                            fetchPayload(planned, plugin)
                        }
                    }
                } else emptyList()
            } else emptyList()

            val jsJobs = activeJs.map { jsPlugin ->
                async(Dispatchers.IO) {
                    runJsPayload(jsPlugin, tmdbId.ifBlank { imdbId }, type, season, episode, active.size)
                }
            }

            (httpJobs + jsJobs).awaitAll().filterNotNull()
        }
        if (payloads.isEmpty()) return RankEnvelope(ok = false, message = Messages.noOptions)

        return NativeCore.rank(profile, payloads, settings.value.allowTorrents)
    }

    private suspend fun runJsPayload(
        plugin: JsPlugin,
        id: String,
        type: String,
        season: Int,
        episode: Int,
        priorityOffset: Int
    ): AddonPayload? {
        if (id.isBlank()) return null
        val timeoutMs = settings.value.pluginTimeoutSec.coerceIn(5, 45) * 1000L
        val raw = withTimeoutOrNull(timeoutMs) {
            jsRuntime.runGetStreams(plugin.code, id, type, season, episode)
        } ?: return null
        val normalized = NativeCore.normalizeJs(raw, plugin.name)
        val root = runCatching { AppJson.parseToJsonElement(normalized).jsonObject }.getOrNull() ?: return null
        val streams = runCatching { root["streams"] as JsonArray }.getOrNull() ?: return null
        if (streams.isEmpty()) return null
        return AddonPayload(
            id = plugin.key,
            name = plugin.name,
            priority = priorityOffset + plugin.priority,
            payload = root
        )
    }

    private suspend fun fetchPayload(planned: PlannedAddon, plugin: Plugin): AddonPayload? {
        val timeoutMs = settings.value.pluginTimeoutSec.coerceIn(5, 45) * 1000L
        for (url in planned.urls) {
            val body = withTimeoutOrNull(timeoutMs) {
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

    fun installLocalJs(name: String, code: String): InstallResult {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return InstallResult(false, "El archivo JS está vacío.")
        if (!jsRuntime.validate(trimmed)) {
            return InstallResult(false, "El archivo JS debe exportar la función getStreams.")
        }
        val cleanName = name.removeSuffix(".js").trim().ifBlank { "Plugin JS local" }
        val slug = cleanName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            .ifBlank { "local-${System.currentTimeMillis()}" }
        val key = "js:local:$slug"
        val existing = _jsPlugins.value.firstOrNull { it.key == key }
        val jsPlugin = JsPlugin(
            key = key,
            id = slug,
            name = cleanName,
            code = trimmed,
            enabled = existing?.enabled ?: true,
            priority = existing?.priority ?: jsCounter.getAndIncrement(),
            installedAt = existing?.installedAt ?: System.currentTimeMillis()
        )
        persistJs(_jsPlugins.value.filterNot { it.key == key } + jsPlugin)
        return InstallResult(ok = true, message = "$cleanName (JS) instalado.")
    }

    fun setJsEnabled(key: String, enabled: Boolean) {
        persistJs(_jsPlugins.value.map { if (it.key == key) it.copy(enabled = enabled) else it })
    }

    fun removeJs(key: String) {
        persistJs(_jsPlugins.value.filterNot { it.key == key })
    }

    fun moveJs(key: String, delta: Int) {
        val list = _jsPlugins.value.sortedBy { it.priority }.toMutableList()
        val index = list.indexOfFirst { it.key == key }
        if (index < 0) return
        val target = (index + delta).coerceIn(0, list.size - 1)
        if (target == index) return
        val moved = list.removeAt(index)
        list.add(target, moved)
        persistJs(list.mapIndexed { i, p -> p.copy(priority = i) })
    }

    suspend fun refreshJs(plugin: JsPlugin): InstallResult {
        if (plugin.scriptUrl.isBlank()) {
            return InstallResult(false, "Los archivos JS locales se actualizan importándolos de nuevo.")
        }
        val code = runCatching { http.text(plugin.scriptUrl) }.getOrElse {
            return InstallResult(false, Messages.network)
        }
        if (!jsRuntime.validate(code)) {
            return InstallResult(false, "La nueva versión del script no es válida.")
        }
        val updated = plugin.copy(code = code)
        persistJs(_jsPlugins.value.map { if (it.key == plugin.key) updated else it })
        return InstallResult(true, "${plugin.name} actualizado.")
    }

    fun testJs(plugin: JsPlugin): InstallResult {
        val ok = jsRuntime.validate(plugin.code)
        return if (ok) {
            InstallResult(true, "${plugin.name} · JS válido · ${plugin.types.joinToString(", ")}")
        } else {
            InstallResult(false, "El script no expone getStreams o contiene un error.")
        }
    }

    private fun persistJs(list: List<JsPlugin>) {
        val ordered = list.sortedBy { it.priority }.mapIndexed { index, plugin ->
            plugin.copy(priority = index)
        }
        _jsPlugins.value = ordered
        jsCounter.set(ordered.size + 1)
        settings.putRaw(
            SettingsStore.KEY_JS_PLUGINS,
            AppJson.encodeToString(ListSerializer(JsPlugin.serializer()), ordered)
        )
    }

    private fun loadJs(): List<JsPlugin> {
        val raw = settings.raw(SettingsStore.KEY_JS_PLUGINS) ?: return emptyList()
        return runCatching {
            AppJson.decodeFromString(ListSerializer(JsPlugin.serializer()), raw)
        }.getOrDefault(emptyList())
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
        private val JS_URL_PATTERN = Regex("\\.js(\\?.*)?$", RegexOption.IGNORE_CASE)
    }
}

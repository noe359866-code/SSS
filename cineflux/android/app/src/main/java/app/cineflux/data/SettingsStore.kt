package app.cineflux.data

import android.content.Context
import app.cineflux.core.AppJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

/** Catálogos oficiales por defecto (Nuvio + Cinemeta). */
val DEFAULT_CATALOG_SOURCES = listOf(
    "https://catalog.nuvio.tv/manifest.json",
    "https://v3-cinemeta.strem.io/manifest.json"
)

@Serializable
data class AppSettings(
    val omdbKey: String = "",
    val language: String = "es-ES",
    val region: String = "ES",
    val profileId: String = "latino-1080",
    val proxyTemplate: String = "",
    val userAgent: String = "Cineflux/1.0 (Android)",
    val allowTorrents: Boolean = false,
    val autoplayNext: Boolean = true,
    val onboardingDone: Boolean = false,
    val skipSegmentsEnabled: Boolean = true,
    val autoSkipIntro: Boolean = false,
    val autoSkipRecap: Boolean = true,
    val autoSkipCredits: Boolean = false,
    val animeSkipClientId: String = "",
    val catalogSources: List<String> = DEFAULT_CATALOG_SOURCES,
    val defaultPlaybackSpeed: Float = 1.0f,
    val seekStepSeconds: Int = 10,
    val rememberPosition: Boolean = true,
    val subtitlesEnabled: Boolean = true,
    val subtitleLanguage: String = "es",
    val subtitleSize: String = "medium",
    val pluginTimeoutSec: Int = 12,
    val wifiOnlyStreaming: Boolean = false,
    val compactCards: Boolean = false,
    val showRatingsOnCards: Boolean = true
) {
    val hasOmdb: Boolean get() = omdbKey.isNotBlank()
    val hasCatalogSources: Boolean get() = catalogSources.isNotEmpty()
    /** OMDb devuelve puntuaciones de Rotten Tomatoes sin proxies. */
    val rtScoresAvailable: Boolean get() = hasOmdb || proxyTemplate.isNotBlank()
}

/**
 * Ajustes y almacenamiento simple de JSON en SharedPreferences.
 * Todo es local: la app no tiene backend propio.
 */
class SettingsStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("cineflux.store", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val value: AppSettings get() = _state.value

    private fun read(): AppSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return AppSettings()
        return runCatching { AppJson.decodeFromString<AppSettings>(raw) }.getOrDefault(AppSettings())
    }

    fun update(block: (AppSettings) -> AppSettings) {
        val next = block(value)
        prefs.edit().putString(KEY_SETTINGS, AppJson.encodeToString(AppSettings.serializer(), next)).apply()
        _state.value = next
    }

    fun raw(key: String): String? = prefs.getString(key, null)

    fun putRaw(key: String, value: String?) {
        prefs.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }

    fun clearUserData() {
        prefs.edit().clear().apply()
        _state.value = AppSettings()
    }

    companion object {
        private const val KEY_SETTINGS = "settings.v1"
        const val KEY_PLUGINS = "plugins.v1"
        const val KEY_JS_PLUGINS = "js_plugins.v1"
        const val KEY_PROFILES = "profiles.v1"
        const val KEY_LIBRARY = "library.v1"
    }
}

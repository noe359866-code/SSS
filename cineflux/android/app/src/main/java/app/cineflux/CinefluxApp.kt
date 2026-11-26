package app.cineflux

import android.app.Application
import android.content.Context
import app.cineflux.core.AppJson
import app.cineflux.core.Plugin
import app.cineflux.core.Profile
import app.cineflux.data.AppSettings
import app.cineflux.data.Http
import app.cineflux.data.LibraryEntry
import app.cineflux.data.LibraryRepository
import app.cineflux.data.PluginRepository
import app.cineflux.data.ProfileRepository
import app.cineflux.data.SkipTimesRepository
import app.cineflux.data.SettingsStore
import app.cineflux.data.catalog.CatalogRepository
import app.cineflux.data.catalog.RtApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

@Serializable
data class Backup(
    val app: String = "cineflux",
    val version: Int = 1,
    val settings: AppSettings = AppSettings(),
    val plugins: List<Plugin> = emptyList(),
    val profiles: List<Profile> = emptyList(),
    val library: List<LibraryEntry> = emptyList()
)

/**
 * Contenedor de dependencias manual (sin frameworks). Una sola instancia por
 * proceso, creada desde [CinefluxApp].
 */
class AppGraph private constructor(context: Context) {

    val settings = SettingsStore(context)
    val http = Http(settings)
    val plugins = PluginRepository(settings, http)
    val profiles = ProfileRepository(settings)
    val library = LibraryRepository(settings)
    val rt = RtApi(settings, http)
    val catalog = CatalogRepository(settings, http, rt)
    val skipTimes = SkipTimesRepository(settings, http)

    fun exportBackup(): String = AppJson.encodeToString(
        Backup.serializer(),
        Backup(
            settings = settings.value,
            plugins = plugins.plugins.value,
            profiles = profiles.profiles.value,
            library = library.entries.value
        )
    )

    /** Devuelve un mensaje humano si la copia no se pudo importar. */
    fun importBackup(raw: String): String? {
        val backup = runCatching { AppJson.decodeFromString<Backup>(raw.trim()) }.getOrNull()
            ?: return "Esa copia no tiene el formato de Cineflux."
        if (backup.app != "cineflux") return "Esa copia no es de Cineflux."
        settings.update { backup.settings }
        plugins.replaceAll(backup.plugins)
        profiles.replaceAll(backup.profiles)
        library.replaceAll(backup.library)
        return null
    }

    fun clearEverything() {
        plugins.replaceAll(emptyList())
        profiles.resetToDefaults()
        library.clearAll()
        settings.clearUserData()
    }

    companion object {
        @Volatile
        private var instance: AppGraph? = null

        fun init(context: Context) {
            if (instance == null) {
                synchronized(AppGraph::class.java) {
                    if (instance == null) instance = AppGraph(context.applicationContext)
                }
            }
        }

        val shared: AppGraph
            get() = instance ?: error("AppGraph no inicializado")
    }
}

class CinefluxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}

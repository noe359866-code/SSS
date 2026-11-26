package app.cineflux.data

import app.cineflux.core.AppJson
import app.cineflux.core.NativeCore
import app.cineflux.core.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Perfiles de reproducción: la única elección del usuario.
 * El núcleo C++ calcula los valores por defecto; la app guarda los cambios.
 */
class ProfileRepository(private val settings: SettingsStore) {

    private val _profiles = MutableStateFlow(load())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _selectedId = MutableStateFlow(settings.value.profileId)
    val selectedId: StateFlow<String> = _selectedId.asStateFlow()

    val selected: Profile
        get() {
            val list = _profiles.value
            return list.firstOrNull { it.id == _selectedId.value }
                ?: list.firstOrNull()
                ?: NativeCore.profiles().first()
        }

    fun select(id: String) {
        if (_profiles.value.none { it.id == id }) return
        _selectedId.value = id
        settings.update { it.copy(profileId = id) }
    }

    /** Devuelve un mensaje de error listo para la interfaz, o null si se guardó. */
    fun save(profile: Profile): String? {
        validate(profile)?.let { return it }
        val current = _profiles.value
        val index = current.indexOfFirst { it.id == profile.id }
        val next = if (index >= 0) current.toMutableList().also { it[index] = profile } else current + profile
        persist(next)
        return null
    }

    fun delete(id: String): String? {
        val current = _profiles.value
        if (current.size <= 1) return "Necesitas al menos un perfil."
        val next = current.filterNot { it.id == id }
        persist(next)
        if (_selectedId.value == id) select(next.first().id)
        return null
    }

    fun replaceAll(list: List<Profile>) {
        if (list.isEmpty()) return
        persist(list)
        if (list.none { it.id == _selectedId.value }) select(list.first().id)
    }

    fun resetToDefaults() {
        persist(NativeCore.profiles())
        _profiles.value.firstOrNull()?.let { select(it.id) }
    }

    private fun validate(profile: Profile): String? {
        if (profile.name.isBlank()) return "Ponle un nombre al perfil."
        if (profile.name.length > 40) return "El nombre del perfil es demasiado largo."
        if (profile.strictLanguage && profile.languages.isEmpty()) {
            return "Elige al menos un idioma si el perfil es estricto."
        }
        val allowed = setOf(360, 480, 720, 1080, 1440, 2160)
        if (profile.targetQuality !in allowed) return "La calidad objetivo no es válida."
        if (profile.maxQuality !in allowed) return "El techo de calidad no es válido."
        return null
    }

    private fun persist(list: List<Profile>) {
        _profiles.value = list
        settings.putRaw(
            SettingsStore.KEY_PROFILES,
            AppJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(Profile.serializer()), list)
        )
    }

    private fun load(): List<Profile> {
        val raw = settings.raw(SettingsStore.KEY_PROFILES)
        if (raw != null) {
            runCatching {
                val list = AppJson.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(Profile.serializer()), raw
                )
                if (list.isNotEmpty()) return list
            }
        }
        return NativeCore.profiles().ifEmpty { app.cineflux.core.FallbackProfiles.all }
    }
}

package app.cineflux.data

import app.cineflux.core.AppJson
import app.cineflux.data.catalog.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class LibraryEntry(
    val item: MediaItem,
    val addedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val season: Int = 0,
    val episode: Int = 0,
    val saved: Boolean = true
) {
    val hasProgress: Boolean get() = positionMs > 30_000 && durationMs > 0 && positionMs < durationMs * 0.96
    val progress: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/** Mi lista y «continuar viendo», todo local. */
class LibraryRepository(private val settings: SettingsStore) {

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<LibraryEntry>> = _entries.asStateFlow()

    private val _saved = MutableStateFlow(_entries.value.filter { it.saved })
    val savedItems: StateFlow<List<LibraryEntry>> = _saved.asStateFlow()

    private val _continue = MutableStateFlow(_entries.value.filter { it.hasProgress })
    val continueWatching: StateFlow<List<LibraryEntry>> = _continue.asStateFlow()

    fun isSaved(key: String): Boolean = _entries.value.any { it.item.key == key && it.saved }

    fun toggle(item: MediaItem) {
        val current = _entries.value
        val existing = current.firstOrNull { it.item.key == item.key }
        val next = when {
            existing == null -> current + LibraryEntry(item = item, saved = true)
            existing.saved -> current.map {
                if (it.item.key != item.key) it
                else if (it.hasProgress) it.copy(saved = false) else null
            }.filterNotNull()
            else -> current.map {
                if (it.item.key == item.key) it.copy(saved = true) else it
            }
        }
        persist(next)
    }

    fun recordProgress(
        item: MediaItem,
        positionMs: Long,
        durationMs: Long,
        season: Int = 0,
        episode: Int = 0
    ) {
        if (positionMs <= 0) return
        val current = _entries.value
        val existing = current.firstOrNull { it.item.key == item.key }
        val base = existing ?: LibraryEntry(
            item = item,
            addedAt = System.currentTimeMillis(),
            saved = false
        )
        val updated = base.copy(
            item = item,
            positionMs = positionMs,
            durationMs = durationMs,
            season = season,
            episode = episode,
            updatedAt = System.currentTimeMillis()
        )
        persist(current.filterNot { it.item.key == item.key } + updated)
    }

    fun remove(key: String) {
        persist(_entries.value.filterNot { it.item.key == key })
    }

    fun clearProgress() {
        persist(_entries.value.map { it.copy(positionMs = 0, durationMs = 0) })
    }

    fun replaceAll(list: List<LibraryEntry>) {
        persist(list)
    }

    fun clearAll() {
        persist(emptyList())
    }

    private fun persist(list: List<LibraryEntry>) {
        val sorted = list.sortedByDescending { it.updatedAt }
        _entries.value = sorted
        _saved.value = sorted.filter { it.saved }
        _continue.value = sorted.filter { it.hasProgress }.sortedByDescending { it.updatedAt }
        settings.putRaw(
            SettingsStore.KEY_LIBRARY,
            AppJson.encodeToString(ListSerializer(LibraryEntry.serializer()), sorted)
        )
    }

    private fun load(): List<LibraryEntry> {
        val raw = settings.raw(SettingsStore.KEY_LIBRARY) ?: return emptyList()
        return runCatching {
            AppJson.decodeFromString(ListSerializer(LibraryEntry.serializer()), raw)
        }.getOrDefault(emptyList()).sortedByDescending { it.updatedAt }
    }
}

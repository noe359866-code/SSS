package app.cineflux.core

import kotlinx.serialization.Serializable

@Serializable
data class JsRepoPluginEntry(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val version: String = "1.0.0",
    val scriptUrl: String = "",
    val types: List<String> = listOf("movie", "series"),
    val enabledByDefault: Boolean = true
)

@Serializable
data class JsRepoEnvelope(
    val ok: Boolean = false,
    val name: String = "",
    val version: String = "",
    val baseUrl: String = "",
    val plugins: List<JsRepoPluginEntry> = emptyList(),
    val message: String = ""
)

@Serializable
data class JsPlugin(
    val key: String,
    val id: String,
    val name: String,
    val description: String = "",
    val version: String = "1.0.0",
    val scriptUrl: String = "",
    val repoUrl: String = "",
    val repoName: String = "",
    val code: String = "",
    val types: List<String> = listOf("movie", "series"),
    val enabled: Boolean = true,
    val priority: Int = 0,
    val installedAt: Long = 0L
) {
    val isFromRepo: Boolean get() = repoUrl.isNotBlank()
    val isLocalFile: Boolean get() = scriptUrl.isBlank()

    fun supportsType(type: String): Boolean {
        val norm = if (type.equals("tv", ignoreCase = true)) "series" else type.lowercase()
        return types.isEmpty() || types.any { it.equals(norm, ignoreCase = true) }
    }
}

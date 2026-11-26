package app.cineflux.data

import android.util.Log
import app.cineflux.core.FriendlyException
import app.cineflux.core.Messages
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Cliente HTTP de la app. En móvil no hay CORS, así que se llama directo; el
 * proxy opcional solo sirve para redes que bloquean ciertos hosts.
 */
class Http(private val settings: SettingsStore) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun proxied(url: String): String? {
        val template = settings.value.proxyTemplate.trim()
        if (template.isEmpty()) return null
        if (!template.contains("{url}")) return null
        return template.replace("{url}", URLEncoder.encode(url, "UTF-8"))
    }

    suspend fun text(url: String, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val direct = runCatching { execute(url, headers) }
            direct.getOrNull()?.let { return@withContext it }
            val proxy = proxied(url)
            if (proxy != null) {
                val viaProxy = runCatching { execute(proxy, headers) }
                viaProxy.getOrNull()?.let { return@withContext it }
            }
            Log.w(TAG, "petición no completada")
            throw FriendlyException(Messages.network, direct.exceptionOrNull())
        }

    private fun execute(url: String, headers: Map<String, String>): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", settings.value.userAgent)
            .header("Accept", "application/json, text/plain, */*")
        headers.forEach { (key, value) -> builder.header(key, value) }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("respuesta no válida")
            return response.body?.string() ?: throw IOException("respuesta vacía")
        }
    }

    suspend fun json(url: String, headers: Map<String, String> = emptyMap()): JsonObject =
        withContext(Dispatchers.IO) {
            val body = text(url, headers)
            runCatching { Json.parseToJsonElement(body) as JsonObject }
                .getOrElse { throw FriendlyException(Messages.network, it) }
        }

    suspend fun postJson(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap()
    ): JsonObject = withContext(Dispatchers.IO) {
        val requestBody = body.toRequestBody(JSON)
        val direct = runCatching { executePost(url, requestBody, headers) }
        val text = direct.getOrNull() ?: run {
            val proxy = proxied(url)
            if (proxy != null) {
                runCatching { executePost(proxy, requestBody, headers) }.getOrNull()
            } else {
                null
            }
        } ?: throw FriendlyException(Messages.network, direct.exceptionOrNull())
        runCatching { Json.parseToJsonElement(text) as JsonObject }
            .getOrElse { throw FriendlyException(Messages.network, it) }
    }

    private fun executePost(url: String, body: okhttp3.RequestBody, headers: Map<String, String>): String {
        val builder = Request.Builder()
            .url(url)
            .post(body)
            .header("User-Agent", settings.value.userAgent)
            .header("Accept", "application/json")
        headers.forEach { (key, value) -> builder.header(key, value) }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("respuesta no válida")
            return response.body?.string() ?: throw IOException("respuesta vacía")
        }
    }

    /** Prueba silenciosa: nunca muestra el fallo al usuario. */
    suspend fun tryJson(url: String): JsonObject? =
        runCatching { json(url) }.getOrNull()

    companion object {
        private const val TAG = "CinefluxHttp"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

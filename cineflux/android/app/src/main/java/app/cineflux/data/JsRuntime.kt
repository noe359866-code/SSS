package app.cineflux.data

import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.util.concurrent.TimeUnit

/**
 * Motor JavaScript embebido (Mozilla Rhino) para ejecutar plugins JS locales
 * estilo Nuvio (`getStreams(tmdbId, mediaType, season, episode)`).
 *
 * Expone un entorno mínimo y seguro: `fetch` (respaldado por OkHttp), `atob`,
 * `btoa`, `console`, `module`/`exports` y `Promise`.
 */
class JsRuntime(private val http: Http) {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * Comprueba que el código compila y expone `getStreams`.
     */
    fun validate(code: String): Boolean {
        if (code.isBlank()) return false
        val cx = Context.enter()
        return try {
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            val scope = createScope(cx)
            cx.evaluateString(scope, code, "plugin.js", 1, null)
            resolveGetStreams(scope) != null
        } catch (t: Throwable) {
            Log.w(TAG, "validación JS fallida", t)
            false
        } finally {
            Context.exit()
        }
    }

    /**
     * Ejecuta `getStreams(tmdbId, mediaType, season, episode)` y devuelve la
     * salida serializada como JSON (array o `{streams:[...]}`), o `null` si falla.
     */
    fun runGetStreams(
        code: String,
        tmdbId: String,
        mediaType: String,
        season: Int,
        episode: Int
    ): String? {
        if (code.isBlank()) return null
        val cx = Context.enter()
        return try {
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            val scope = createScope(cx)
            cx.evaluateString(scope, code, "plugin.js", 1, null)

            val fn = resolveGetStreams(scope) ?: return null
            val nuvioType = if (mediaType.equals("series", ignoreCase = true) ||
                mediaType.equals("tv", ignoreCase = true)
            ) "tv" else "movie"

            val args: Array<Any?> = if (nuvioType == "tv") {
                arrayOf(tmdbId, nuvioType, season, episode)
            } else {
                arrayOf(tmdbId, nuvioType, null, null)
            }

            val rawResult = fn.call(cx, scope, scope, args)
            val resolved = unwrapThenable(cx, scope, rawResult) ?: return null
            val json = NativeJSON.stringify(cx, scope, resolved, null, null)
            json?.toString()?.takeIf { it != "undefined" && it != "null" }
        } catch (t: Throwable) {
            Log.w(TAG, "ejecución JS fallida", t)
            null
        } finally {
            Context.exit()
        }
    }

    private fun createScope(cx: Context): Scriptable {
        val scope = cx.initStandardObjects()
        ScriptableObject.putProperty(scope, "__cfxBridge", Context.javaToJS(Bridge(), scope))
        cx.evaluateString(scope, SHIM_JS, "cineflux-shim.js", 1, null)
        return scope
    }

    private fun resolveGetStreams(scope: Scriptable): Function? {
        val moduleObj = ScriptableObject.getProperty(scope, "module") as? Scriptable
        val moduleExports = moduleObj?.let { ScriptableObject.getProperty(it, "exports") }
        if (moduleExports is Function) return moduleExports
        if (moduleExports is Scriptable) {
            val fn = ScriptableObject.getProperty(moduleExports, "getStreams")
            if (fn is Function) return fn
        }
        val exportsObj = ScriptableObject.getProperty(scope, "exports") as? Scriptable
        if (exportsObj != null) {
            val fn = ScriptableObject.getProperty(exportsObj, "getStreams")
            if (fn is Function) return fn
        }
        val globalFn = ScriptableObject.getProperty(scope, "getStreams")
        return globalFn as? Function
    }

    private fun unwrapThenable(cx: Context, scope: Scriptable, value: Any?): Any? {
        if (value !is Scriptable) return value
        val thenFn = ScriptableObject.getProperty(value, "then")
        if (thenFn !is Function) return value

        var resolvedValue: Any? = null
        var settled = false
        val onResolve = object : org.mozilla.javascript.BaseFunction() {
            override fun call(
                cx2: Context?,
                scope2: Scriptable?,
                thisObj: Scriptable?,
                args: Array<out Any?>?
            ): Any? {
                resolvedValue = args?.firstOrNull()
                settled = true
                return Context.getUndefinedValue()
            }
        }
        val onReject = object : org.mozilla.javascript.BaseFunction() {
            override fun call(
                cx2: Context?,
                scope2: Scriptable?,
                thisObj: Scriptable?,
                args: Array<out Any?>?
            ): Any? {
                resolvedValue = null
                settled = true
                return Context.getUndefinedValue()
            }
        }
        thenFn.call(cx, scope, value, arrayOf(onResolve, onReject))
        return if (settled) resolvedValue else null
    }

    inner class Bridge {
        @Suppress("unused")
        fun fetchSync(url: String, method: String, headersJson: String, body: String?): String {
            return runCatching {
                val reqBuilder = Request.Builder().url(url)
                var contentType = "application/json"
                if (headersJson.isNotBlank() && headersJson != "{}") {
                    val map = org.json.JSONObject(headersJson)
                    val keys = map.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = map.optString(k, "")
                        if (v.isNotEmpty()) {
                            reqBuilder.header(k, v)
                            if (k.equals("Content-Type", ignoreCase = true)) contentType = v
                        }
                    }
                }
                val upper = method.uppercase().ifBlank { "GET" }
                if (upper == "GET" || upper == "HEAD") {
                    reqBuilder.method(upper, null)
                } else {
                    reqBuilder.method(upper, (body ?: "").toRequestBody(contentType.toMediaTypeOrNull()))
                }
                client.newCall(reqBuilder.build()).execute().use { res ->
                    val text = res.body?.string().orEmpty()
                    val hdrs = org.json.JSONObject()
                    for (name in res.headers.names()) {
                        hdrs.put(name.lowercase(), res.headers[name].orEmpty())
                    }
                    org.json.JSONObject().apply {
                        put("ok", res.isSuccessful)
                        put("status", res.code)
                        put("statusText", res.message)
                        put("url", res.request.url.toString())
                        put("headers", hdrs)
                        put("body", text)
                    }.toString()
                }
            }.getOrElse {
                org.json.JSONObject().apply {
                    put("ok", false)
                    put("status", 0)
                    put("statusText", "Network error")
                    put("url", url)
                    put("headers", org.json.JSONObject())
                    put("body", "")
                }.toString()
            }
        }

        @Suppress("unused")
        fun atob(input: String): String =
            runCatching { String(Base64.decode(input, Base64.DEFAULT), Charsets.UTF_8) }
                .getOrDefault("")

        @Suppress("unused")
        fun btoa(input: String): String =
            Base64.encodeToString(input.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        @Suppress("unused")
        fun log(level: String, msg: String) {
            Log.d(TAG, "[$level] $msg")
        }
    }

    companion object {
        private const val TAG = "CinefluxJS"

        private val SHIM_JS = """
            var exports = {};
            var module = { exports: exports };
            var console = {
              log: function() { __cfxBridge.log("log", Array.prototype.slice.call(arguments).join(" ")); },
              warn: function() { __cfxBridge.log("warn", Array.prototype.slice.call(arguments).join(" ")); },
              error: function() { __cfxBridge.log("error", Array.prototype.slice.call(arguments).join(" ")); }
            };
            function atob(s) { return String(__cfxBridge.atob(String(s || ""))); }
            function btoa(s) { return String(__cfxBridge.btoa(String(s || ""))); }
            function setTimeout(fn) { if (typeof fn === "function") fn(); return 0; }
            function clearTimeout() {}
            function fetch(url, opts) {
              opts = opts || {};
              var method = String(opts.method || "GET");
              var headers = opts.headers ? JSON.stringify(opts.headers) : "{}";
              var body = opts.body != null ? String(opts.body) : null;
              var raw = String(__cfxBridge.fetchSync(String(url), method, headers, body));
              var data = JSON.parse(raw);
              var response = {
                ok: !!data.ok,
                status: data.status || 0,
                statusText: data.statusText || "",
                url: data.url || String(url),
                headers: {
                  get: function(k) {
                    if (!k) return null;
                    return data.headers[String(k).toLowerCase()] || null;
                  }
                },
                text: function() { return Promise.resolve(data.body || ""); },
                json: function() { return Promise.resolve(JSON.parse(data.body || "null")); }
              };
              return Promise.resolve(response);
            }
        """.trimIndent()
    }
}

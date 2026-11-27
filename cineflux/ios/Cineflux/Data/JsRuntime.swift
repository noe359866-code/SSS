import Foundation
import JavaScriptCore

struct JsRepoPluginEntry: Codable {
    var id: String = ""
    var name: String = ""
    var description: String = ""
    var version: String = "1.0.0"
    var scriptUrl: String = ""
    var types: [String] = ["movie", "series"]
    var enabledByDefault: Bool = true
}

struct JsRepoEnvelope: Codable {
    var ok: Bool = false
    var name: String = ""
    var version: String = ""
    var baseUrl: String = ""
    var plugins: [JsRepoPluginEntry] = []
    var message: String = ""
}

struct JsPlugin: Codable, Identifiable, Equatable {
    var id: String { key }
    var key: String
    var pluginId: String
    var name: String
    var description: String = ""
    var version: String = "1.0.0"
    var scriptUrl: String = ""
    var repoUrl: String = ""
    var repoName: String = ""
    var code: String = ""
    var types: [String] = ["movie", "series"]
    var enabled: Bool = true
    var priority: Int = 0
    var installedAt: Double = Date().timeIntervalSince1970

    var isLocalFile: Bool { scriptUrl.isEmpty }

    func supportsType(_ type: String) -> Bool {
        let norm = type.lowercased() == "tv" ? "series" : type.lowercased()
        return types.isEmpty || types.contains { $0.lowercased() == norm }
    }
}

/// Motor JavaScript nativo de iOS (JavaScriptCore) para ejecutar plugins JS
/// locales estilo Nuvio (`getStreams(tmdbId, mediaType, season, episode)`).
final class JsRuntime {
    static let shared = JsRuntime()

    private let session: URLSession = {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 12
        cfg.timeoutIntervalForResource = 14
        return URLSession(configuration: cfg)
    }()

    /// Comprueba que el script compila y expone `getStreams`.
    func validate(_ code: String) -> Bool {
        guard !code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let ctx = makeContext() else { return false }
        var hadError = false
        ctx.exceptionHandler = { _, _ in hadError = true }
        ctx.evaluateScript(code)
        if hadError { return false }
        return resolveGetStreams(in: ctx) != nil
    }

    /// Ejecuta `getStreams(tmdbId, mediaType, season, episode)` y devuelve el
    /// resultado serializado en JSON (array o `{streams:[...]}`).
    func runGetStreams(
        code: String,
        tmdbId: String,
        mediaType: String,
        season: Int,
        episode: Int
    ) async -> String? {
        await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                guard let ctx = self.makeContext() else {
                    continuation.resume(returning: nil)
                    return
                }
                var hadError = false
                ctx.exceptionHandler = { _, _ in hadError = true }
                ctx.evaluateScript(code)
                guard !hadError, let fn = self.resolveGetStreams(in: ctx) else {
                    continuation.resume(returning: nil)
                    return
                }

                let nuvioType = (mediaType.lowercased() == "series" || mediaType.lowercased() == "tv")
                    ? "tv" : "movie"
                let args: [Any] = nuvioType == "tv"
                    ? [tmdbId, nuvioType, season, episode]
                    : [tmdbId, nuvioType]

                guard let rawResult = fn.call(withArguments: args), !rawResult.isUndefined, !rawResult.isNull else {
                    continuation.resume(returning: nil)
                    return
                }

                // Si devuelve una Promise / thenable, esperamos a que resuelva.
                if let thenProp = rawResult.forProperty("then"), thenProp.isObject {
                    var resumed = false
                    let lock = NSLock()
                    let finish: (String?) -> Void = { value in
                        lock.lock()
                        defer { lock.unlock() }
                        guard !resumed else { return }
                        resumed = true
                        continuation.resume(returning: value)
                    }

                    let onResolve: @convention(block) (JSValue) -> Void = { val in
                        finish(self.stringify(val, in: ctx))
                    }
                    let onReject: @convention(block) (JSValue) -> Void = { _ in
                        finish(nil)
                    }
                    rawResult.invokeMethod("then", withArguments: [
                        unsafeBitCast(onResolve, to: AnyObject.self),
                        unsafeBitCast(onReject, to: AnyObject.self)
                    ])
                    DispatchQueue.global().asyncAfter(deadline: .now() + 14) {
                        finish(nil)
                    }
                } else {
                    continuation.resume(returning: self.stringify(rawResult, in: ctx))
                }
            }
        }
    }

    private func makeContext() -> JSContext? {
        guard let ctx = JSContext() else { return nil }

        let fetchSync: @convention(block) (String, String, String, String?) -> String = { [weak self] urlStr, method, headersJson, body in
            guard let self, let url = URL(string: urlStr) else {
                return #"{"ok":false,"status":0,"statusText":"Invalid URL","url":"","headers":{},"body":""}"#
            }
            var req = URLRequest(url: url)
            req.httpMethod = method.isEmpty ? "GET" : method.uppercased()
            if let data = headersJson.data(using: .utf8),
               let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                for (k, v) in dict {
                    req.setValue("\(v)", forHTTPHeaderField: k)
                }
            }
            if let body, req.httpMethod != "GET", req.httpMethod != "HEAD" {
                req.httpBody = body.data(using: .utf8)
            }

            let sem = DispatchSemaphore(value: 0)
            var payload: [String: Any] = [
                "ok": false, "status": 0, "statusText": "Network error",
                "url": urlStr, "headers": [:], "body": ""
            ]
            self.session.dataTask(with: req) { data, response, _ in
                if let http = response as? HTTPURLResponse {
                    var hdrs: [String: String] = [:]
                    for (k, v) in http.allHeaderFields {
                        hdrs["\(k)".lowercased()] = "\(v)"
                    }
                    payload = [
                        "ok": (200...299).contains(http.statusCode),
                        "status": http.statusCode,
                        "statusText": HTTPURLResponse.localizedString(forStatusCode: http.statusCode),
                        "url": http.url?.absoluteString ?? urlStr,
                        "headers": hdrs,
                        "body": data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
                    ]
                }
                sem.signal()
            }.resume()
            _ = sem.wait(timeout: .now() + 12)

            guard let outData = try? JSONSerialization.data(withJSONObject: payload),
                  let outStr = String(data: outData, encoding: .utf8) else {
                return #"{"ok":false,"status":0,"statusText":"Error","url":"","headers":{},"body":""}"#
            }
            return outStr
        }

        let atobFn: @convention(block) (String) -> String = { input in
            guard let data = Data(base64Encoded: input),
                  let str = String(data: data, encoding: .utf8) else { return "" }
            return str
        }
        let btoaFn: @convention(block) (String) -> String = { input in
            Data(input.utf8).base64EncodedString()
        }

        ctx.setObject(fetchSync, forKeyedSubscript: "__cfxFetchSync" as NSString)
        ctx.setObject(atobFn, forKeyedSubscript: "atob" as NSString)
        ctx.setObject(btoaFn, forKeyedSubscript: "btoa" as NSString)
        ctx.evaluateScript(Self.shimJS)
        return ctx
    }

    private func resolveGetStreams(in ctx: JSContext) -> JSValue? {
        if let mod = ctx.objectForKeyedSubscript("module"),
           let exp = mod.forProperty("exports") {
            if exp.isObject, let fn = exp.forProperty("getStreams"), !fn.isUndefined, !fn.isNull {
                return fn
            }
            if exp.toString() != "[object Object]", !exp.isUndefined, !exp.isNull,
               ctx.evaluateScript("typeof module.exports === 'function'")?.toBool() == true {
                return exp
            }
        }
        if let exp = ctx.objectForKeyedSubscript("exports"),
           let fn = exp.forProperty("getStreams"), !fn.isUndefined, !fn.isNull {
            return fn
        }
        if let fn = ctx.objectForKeyedSubscript("getStreams"), !fn.isUndefined, !fn.isNull,
           ctx.evaluateScript("typeof getStreams === 'function'")?.toBool() == true {
            return fn
        }
        return nil
    }

    private func stringify(_ value: JSValue, in ctx: JSContext) -> String? {
        guard !value.isUndefined, !value.isNull else { return nil }
        guard let jsonObj = ctx.objectForKeyedSubscript("JSON"),
              let out = jsonObj.invokeMethod("stringify", withArguments: [value]),
              !out.isUndefined, !out.isNull else { return nil }
        return out.toString()
    }

    private static let shimJS = """
    var exports = {};
    var module = { exports: exports };
    var console = { log: function() {}, warn: function() {}, error: function() {} };
    function setTimeout(fn) { if (typeof fn === "function") fn(); return 0; }
    function clearTimeout() {}
    function fetch(url, opts) {
      opts = opts || {};
      var method = String(opts.method || "GET");
      var headers = opts.headers ? JSON.stringify(opts.headers) : "{}";
      var body = opts.body != null ? String(opts.body) : null;
      var raw = String(__cfxFetchSync(String(url), method, headers, body));
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
    """
}

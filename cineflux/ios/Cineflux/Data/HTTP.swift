import Foundation

/// Cliente HTTP. En iOS no hay CORS; el proxy solo se usa si la petición
/// directa falla. Los errores se traducen siempre a mensajes humanos.
final class HTTPClient: @unchecked Sendable {
    private let session: URLSession

    init() {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 25
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        session = URLSession(configuration: configuration)
    }

    func text(_ urlString: String, headers: [String: String] = [:], settings: AppSettings) async throws -> String {
        if let body = try? await perform(urlString, headers: headers, settings: settings) {
            return body
        }
        if let proxied = proxiedURL(urlString, template: settings.proxyTemplate),
           let body = try? await perform(proxied, headers: headers, settings: settings) {
            return body
        }
        throw FriendlyError(Messages.network)
    }

    func json(_ urlString: String, headers: [String: String] = [:], settings: AppSettings) async throws -> [String: Any] {
        let body = try await text(urlString, headers: headers, settings: settings)
        guard let data = body.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw FriendlyError(Messages.network)
        }
        return object
    }

    func postJSON(
        _ urlString: String,
        body: String,
        headers: [String: String] = [:],
        settings: AppSettings
    ) async -> [String: Any]? {
        guard let url = URL(string: urlString) else { return nil }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = body.data(using: .utf8)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(settings.userAgent, forHTTPHeaderField: "User-Agent")
        headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
        return await withCheckedContinuation { continuation in
            let task = session.dataTask(with: request) { data, response, _ in
                guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
                      let data,
                      let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                    continuation.resume(returning: nil)
                    return
                }
                continuation.resume(returning: object)
            }
            task.resume()
        }
    }

    func tryJSON(_ urlString: String, headers: [String: String] = [:], settings: AppSettings) async -> [String: Any]? {
        try? await json(urlString, headers: headers, settings: settings)
    }

    private func perform(_ urlString: String, headers: [String: String], settings: AppSettings) async throws -> String {
        guard let url = URL(string: urlString) else { throw FriendlyError(Messages.network) }
        var request = URLRequest(url: url)
        request.setValue(settings.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("application/json, text/plain, */*", forHTTPHeaderField: "Accept")
        for (key, value) in headers {
            request.setValue(value, forHTTPHeaderField: key)
        }
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw FriendlyError(Messages.network)
        }
        guard let body = String(data: data, encoding: .utf8) else {
            throw FriendlyError(Messages.network)
        }
        return body
    }

    private func proxiedURL(_ urlString: String, template: String) -> String? {
        let trimmed = template.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.contains("{url}") else { return nil }
        guard let encoded = urlString.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) else {
            return nil
        }
        return trimmed.replacingOccurrences(of: "{url}", with: encoded)
    }
}

import Foundation

/// Saltos de intro, recap y créditos.
///
/// Descarga en paralelo TheIntroDB, IntroDB, AniSkip y Anime Skip y deja que el
/// núcleo C++ entienda cada formato, recorte los tramos imposibles y elija una
/// fuente por tipo. Cualquier fallo se ignora: la reproducción nunca se bloquea.
@MainActor
final class SkipTimesService {
    private let settings: SettingsStore
    private let http: HTTPClient
    private var memory: [String: [SkipSegment]] = [:]
    private var malIds: [String: Int] = [:]
    private var anilistIds: [String: Int] = [:]

    init(settings: SettingsStore, http: HTTPClient) {
        self.settings = settings
        self.http = http
    }

    func segments(
        item: MediaItem,
        season: Int,
        episode: Int,
        durationMs: Double
    ) async -> [SkipSegment] {
        let config = settings.value
        guard config.skipSegmentsEnabled, durationMs >= 60_000,
              !item.imdbId.isEmpty || !item.tmdbId.isEmpty else { return [] }

        let key = cacheKey(item, season, episode)
        if let cached = memory[key] { return cached }
        if let stored = readCache(key) {
            memory[key] = stored
            return stored
        }

        async let theIntroDbTask = fetchTheIntroDb(item, season: season, episode: episode, durationMs: durationMs)
        async let introDbTask = fetchIntroDb(item, season: season, episode: episode)
        async let aniSkipTask = fetchAniSkip(item, episode: episode, durationMs: durationMs)
        async let animeSkipTask = fetchAnimeSkip(item, season: season, episode: episode)
        let theIntroDb = await theIntroDbTask
        let introDb = await introDbTask
        let aniSkip = await aniSkipTask
        let animeSkip = await animeSkipTask
        let sources = [theIntroDb, introDb, aniSkip, animeSkip].compactMap { $0 }
        guard !sources.isEmpty else { return [] }

        let envelope = NativeCore.skip(durationMs: durationMs, sources: sources)
        let found = envelope?.segments ?? []
        memory[key] = found
        if !found.isEmpty { writeCache(key, found) }
        return found
    }

    // MARK: - TheIntroDB (v4)

    private func fetchTheIntroDb(
        item: MediaItem,
        season: Int,
        episode: Int,
        durationMs: Double
    ) async -> SkipSource? {
        let identity: String
        if !item.imdbId.isEmpty {
            identity = "imdb_id=\(encode(item.imdbId))"
        } else if !item.tmdbId.isEmpty {
            identity = "tmdb_id=\(encode(item.tmdbId))"
        } else {
            return nil
        }
        var url = "https://api.theintrodb.org/v4/media?\(identity)&duration_ms=\(Int(durationMs))"
        if item.isSeries {
            url += "&season=\(season)&episode=\(episode)"
        }
        guard let payload = await http.tryJSON(url, settings: settings.value) else { return nil }
        return SkipSource(source: "theintrodb", payload: JSONValue.from(payload))
    }

    // MARK: - IntroDB.app

    private func fetchIntroDb(item: MediaItem, season: Int, episode: Int) async -> SkipSource? {
        guard !item.imdbId.isEmpty else { return nil }
        let query = item.isSeries
            ? "imdb_id=\(encode(item.imdbId))&season=\(season)&episode=\(episode)"
            : "imdb_id=\(encode(item.imdbId))&is_movie=true"
        guard let payload = await http.tryJSON(
            "https://api.introdb.app/segments?\(query)",
            settings: settings.value
        ) else { return nil }
        return SkipSource(source: "introdb", payload: JSONValue.from(payload))
    }

    // MARK: - AniSkip (MAL)

    private func fetchAniSkip(
        item: MediaItem,
        episode: Int,
        durationMs: Double
    ) async -> SkipSource? {
        guard item.isAnime, episode > 0, let malId = await resolveMalId(item) else { return nil }
        let types = "types%5B%5D=op&types%5B%5D=ed&types%5B%5D=mixed-op" +
            "&types%5B%5D=mixed-ed&types%5B%5D=recap"
        let seconds = Int(durationMs / 1000)
        let url = "https://api.aniskip.com/v2/skip-times/\(malId)/\(episode)?\(types)&episodeLength=\(seconds)"
        guard let payload = await http.tryJSON(url, settings: settings.value) else { return nil }
        if let found = payload["found"] as? Bool, !found { return nil }
        return SkipSource(source: "aniskip", payload: JSONValue.from(payload))
    }

    private func resolveMalId(_ item: MediaItem) async -> Int? {
        let key = normalize(item.title)
        if let cached = malIds[key] { return cached }
        let search = item.title.isEmpty ? item.originalTitle : item.title
        guard !search.isEmpty else { return nil }
        let url = "https://api.jikan.moe/v4/anime?q=\(encode(search))&limit=5&type=tv"
        guard let payload = await http.tryJSON(url, settings: settings.value) else { return nil }

        var best: Int?
        var bestScore = Int.max
        for candidate in payload.arr("data") {
            let id = candidate.int("mal_id")
            guard id > 0 else { continue }
            var titles = [candidate.str("title")]
            titles.append(contentsOf: candidate.arr("titles").map { $0.str("title") })
            let normalized = titles.map { normalize($0) }
            let exact = normalized.contains(key)
            let related = normalized.contains { !$0.isEmpty && ($0.contains(key) || key.contains($0)) }
            let year = candidate.int("year")
            let yearClose = item.year <= 0 || year <= 0 || abs(year - item.year) <= 1
            // Sin una coincidencia razonable no se consulta AniSkip: evita
            // saltos de otra serie con el mismo nombre traducido.
            guard exact || (related && yearClose) else { continue }
            let score = exact ? 0 : 20 + abs(year - item.year)
            if score < bestScore {
                bestScore = score
                best = id
            }
        }
        if let best { malIds[key] = best }
        return best
    }

    // MARK: - Anime Skip (GraphQL opcional)

    private func fetchAnimeSkip(
        item: MediaItem,
        season: Int,
        episode: Int
    ) async -> SkipSource? {
        let clientId = settings.value.animeSkipClientId.trimmingCharacters(in: .whitespaces)
        guard !clientId.isEmpty, item.isAnime, episode > 0,
              let anilistId = await resolveAniListId(item) else { return nil }
        let headers = ["X-Client-ID": clientId]

        let showQuery = """
        query FindShow($externalId: Int!, $service: ExternalService!) {
          findShowsByExternalId(externalId: $externalId, service: $service) { id name }
        }
        """
        guard let showPayload = await http.postJSON(
            "https://api.anime-skip.com/graphql",
            body: graphqlBody(showQuery, variables: ["externalId": anilistId, "service": "ANILIST"]),
            headers: headers,
            settings: settings.value
        ) else { return nil }
        guard let show = showPayload.dict("data")?.arr("findShowsByExternalId").first else { return nil }
        let showIdentifier = showIdValue(show)
        guard !showIdentifier.isEmpty else { return nil }

        let episodesQuery = """
        query Episodes($showId: ID!) {
          findEpisodesByShowId(showId: $showId) {
            number absoluteNumber season
            timestamps { at type { name } }
          }
        }
        """
        guard let episodesPayload = await http.postJSON(
            "https://api.anime-skip.com/graphql",
            body: graphqlBody(episodesQuery, variables: ["showId": showIdentifier]),
            headers: headers,
            settings: settings.value
        ) else { return nil }
        let episodes = episodesPayload.dict("data")?.arr("findEpisodesByShowId") ?? []
        guard let chosen = episodes.first(where: { node in
            let absolute = node.int("absoluteNumber")
            let number = node.int("number")
            let nodeSeason = node.int("season")
            return absolute == episode ||
                (number == episode && (season <= 0 || nodeSeason == 0 || nodeSeason == season))
        }) else { return nil }

        let timestamps = chosen.arr("timestamps")
        guard !timestamps.isEmpty else { return nil }
        let json = JSONValue.array(timestamps.map { JSONValue.from($0) })
        return SkipSource(
            source: "animeskip",
            payload: .object(["timestamps": json])
        )
    }

    private func showIdValue(_ show: [String: Any]) -> String {
        guard let value = show["id"] else { return "" }
        if let text = value as? String { return text }
        if let number = value as? NSNumber { return number.stringValue }
        return ""
    }

    private func resolveAniListId(_ item: MediaItem) async -> Int? {
        let key = normalize(item.title)
        if let cached = anilistIds[key] { return cached }
        let search = item.title.isEmpty ? item.originalTitle : item.title
        guard !search.isEmpty else { return nil }
        let query = "query Search($search: String) { Media(search: $search, type: ANIME) { id } }"
        guard let payload = await http.postJSON(
            "https://graphql.anilist.co",
            body: graphqlBody(query, variables: ["search": search]),
            headers: [:],
            settings: settings.value
        ) else { return nil }
        let id = payload.dict("data")?.dict("Media")?.int("id") ?? 0
        guard id > 0 else { return nil }
        anilistIds[key] = id
        return id
    }

    // MARK: - Utilidades

    private func cacheKey(_ item: MediaItem, _ season: Int, _ episode: Int) -> String {
        "\(item.key)|\(season)|\(episode)"
    }

    private func storageKey(_ key: String) -> String {
        let clean = key.map { $0.isLetter || $0.isNumber ? String($0) : "_" }.joined()
        return "cineflux.skip.v1.\(clean)"
    }

    private func normalize(_ value: String) -> String {
        value.lowercased()
            .replacingOccurrences(of: "[^a-z0-9áéíóúñü ]", with: " ", options: .regularExpression)
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
    }

    private func encode(_ value: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }

    private func graphqlBody(_ query: String, variables: [String: Any]) -> String {
        let payload: [String: Any] = ["query": query, "variables": variables]
        guard let data = try? JSONSerialization.data(withJSONObject: payload),
              let text = String(data: data, encoding: .utf8) else { return "{}" }
        return text
    }

    private func readCache(_ key: String) -> [SkipSegment]? {
        guard let data = settings.raw(storageKey(key)),
              let cache = try? JSONDecoder().decode(SkipCache.self, from: data) else { return nil }
        let age = Date().timeIntervalSince1970 - cache.savedAt
        guard age < 7 * 24 * 60 * 60 else { return nil }
        return cache.segments
    }

    private func writeCache(_ key: String, _ segments: [SkipSegment]) {
        let cache = SkipCache(savedAt: Date().timeIntervalSince1970, segments: segments)
        guard let data = try? JSONEncoder().encode(cache) else { return }
        settings.putRaw(storageKey(key), data)
    }
}

private struct SkipCache: Codable {
    var savedAt: Double = 0
    var segments: [SkipSegment] = []
}

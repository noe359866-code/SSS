import Foundation

// MARK: - Rotten Tomatoes

struct RtScores {
    var tomato: Int?
    var audience: Int?
    var hasAny: Bool { tomato != nil || audience != nil }
}

actor RtClient {
    private let settings: SettingsStore
    private let http: HTTPClient
    private var cache: [String: RtScores] = [:]

    init(settings: SettingsStore, http: HTTPClient) {
        self.settings = settings
        self.http = http
    }

    func scores(item: MediaItem) async -> RtScores {
        let key = "\(item.key)|\(item.title.lowercased())|\(item.year)"
        if let cached = cache[key] { return cached }
        var result = RtScores()
        if !item.imdbId.isEmpty {
            result = await omdb(imdbId: item.imdbId)
        }
        if !result.hasAny {
            result = await scores(title: item.title, year: item.year, type: item.type)
        }
        if result.hasAny { cache[key] = result }
        return result
    }

    func scores(title: String, year: Int, type: String) async -> RtScores {
        guard !title.isEmpty else { return RtScores() }
        let key = "\(title.lowercased())|\(year)|\(type)"
        if let cached = cache[key] { return cached }
        var result = await omdb(title: title, year: year, type: type)
        let search = await rtSearch(title: title, year: year, type: type)
        result.tomato = result.tomato ?? search.tomato
        result.audience = result.audience ?? search.audience
        if result.hasAny { cache[key] = result }
        return result
    }

    private func omdb(imdbId: String) async -> RtScores {
        let key = settings.value.omdbKey.trimmingCharacters(in: .whitespaces)
        guard !key.isEmpty, !imdbId.isEmpty else { return RtScores() }
        var components = URLComponents(string: "https://www.omdbapi.com/")!
        components.queryItems = [
            URLQueryItem(name: "apikey", value: key),
            URLQueryItem(name: "i", value: imdbId)
        ]
        guard let url = components.url?.absoluteString,
              let payload = await http.tryJSON(url, settings: settings.value),
              payload.str("Response").lowercased() != "false" else {
            return RtScores()
        }
        var tomato: Int?
        for rating in payload.arr("Ratings") where rating.str("Source").lowercased().contains("rotten") {
            tomato = Int(rating.str("Value").filter(\.isNumber))
        }
        return RtScores(tomato: tomato, audience: nil)
    }

    private func omdb(title: String, year: Int, type: String) async -> RtScores {
        let key = settings.value.omdbKey.trimmingCharacters(in: .whitespaces)
        guard !key.isEmpty else { return RtScores() }
        var components = URLComponents(string: "https://www.omdbapi.com/")!
        var query = [URLQueryItem(name: "apikey", value: key), URLQueryItem(name: "t", value: title)]
        if year > 0 { query.append(URLQueryItem(name: "y", value: String(year))) }
        query.append(URLQueryItem(name: "type", value: type == "series" ? "series" : "movie"))
        components.queryItems = query
        guard let url = components.url?.absoluteString,
              let payload = await http.tryJSON(url, settings: settings.value),
              payload.str("Response").lowercased() != "false" else {
            return RtScores()
        }
        var tomato: Int?
        for rating in payload.arr("Ratings") where rating.str("Source").lowercased().contains("rotten") {
            tomato = Int(rating.str("Value").filter(\.isNumber))
        }
        return RtScores(tomato: tomato, audience: nil)
    }

    private func rtSearch(title: String, year: Int, type: String) async -> RtScores {
        var components = URLComponents(string: "https://www.rottentomatoes.com/api/private/v2.0/search")!
        components.queryItems = [
            URLQueryItem(name: "q", value: title),
            URLQueryItem(name: "limit", value: "12")
        ]
        guard let url = components.url?.absoluteString,
              let payload = await http.tryJSON(url, settings: settings.value) else {
            return RtScores()
        }
        let candidates = payload.arr("movies") + payload.arr("tvSeries")
        let target = normalize(title)
        let ranked = candidates
            .filter { !normalize($0.str("name")).isEmpty }
            .sorted { lhs, rhs in
                score(lhs, target: target, year: year, type: type) > score(rhs, target: target, year: year, type: type)
            }
        guard let best = ranked.first else { return RtScores() }
        let tomato = best.int("meterScore") > 0 ? best.int("meterScore") : best.int("criticsScore")
        let audience = best.int("audienceScore") > 0 ? best.int("audienceScore") : nil
        return RtScores(tomato: tomato > 0 ? tomato : nil, audience: audience)
    }

    private func score(_ candidate: [String: Any], target: String, year: Int, type: String) -> Int {
        var value = 0
        let name = normalize(candidate.str("name"))
        if name == target { value += 100 }
        else if name.hasPrefix(target) || target.hasPrefix(name) { value += 40 }
        let candidateYear = candidate.int("year")
        if year > 0 && candidateYear > 0 {
            value += 5 - abs(candidateYear - year) * 2
        }
        if type == "series" && candidate.str("type").lowercased().contains("tv") { value += 5 }
        return value
    }

    private func normalize(_ value: String) -> String {
        value.lowercased()
            .replacingOccurrences(of: "[^a-z0-9áéíóúñü ]", with: " ", options: .regularExpression)
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
    }
}

// MARK: - Repositorio de catálogo (Nuvio · Cinemeta)

@MainActor
final class CatalogRepository {
    private struct Source {
        let manifestURL: String
        let baseURL: String
        let manifest: JSONValue
        let id: String
        let name: String
    }

    private let settings: SettingsStore
    private let http: HTTPClient
    private let rt: RtClient

    private var sources: [Source] = []
    private var sourcesAt = Date.distantPast
    private var cachedHome: CatalogSnapshot?
    private var cachedAt = Date.distantPast
    private var cachedSignature = ""
    private var details: [String: DetailData] = [:]

    init(settings: SettingsStore, http: HTTPClient) {
        self.settings = settings
        self.http = http
        self.rt = RtClient(settings: settings, http: http)
    }

    func home(force: Bool = false) async -> CatalogSnapshot {
        let loaded = await loadSources(force: force)
        guard !loaded.isEmpty else { return CatalogSnapshot(message: Messages.catalog) }
        let signature = settings.value.catalogSources.joined(separator: "|")
        if !force, signature == cachedSignature, let cachedHome, Date().timeIntervalSince(cachedAt) < 600 {
            return cachedHome
        }
        let rows = plan(query: "", types: ["movie", "series"], limit: 10, perSource: 5)
        guard !rows.isEmpty else { return CatalogSnapshot(message: Messages.catalog) }

        let built = await withTaskGroup(of: (Int, HomeRow?).self) { group in
            for (index, row) in rows.enumerated() {
                group.addTask { (index, await self.fetchRow(row)) }
            }
            var collected: [(Int, HomeRow?)] = []
            for await pair in group { collected.append(pair) }
            return collected.sorted { $0.0 < $1.0 }.compactMap { $0.1 }
        }
        guard !built.isEmpty else { return CatalogSnapshot(message: Messages.network) }

        var scored = built
        if let first = scored.first {
            var row = first
            row.items = await enrich(row.items, limit: 6)
            scored[0] = row
        }
        let snapshot = CatalogSnapshot(rows: scored)
        cachedHome = snapshot
        cachedAt = Date()
        cachedSignature = signature
        return snapshot
    }

    func browse(_ type: String) async -> [HomeRow] {
        let loaded = await loadSources()
        guard !loaded.isEmpty else { return [] }
        let rows = plan(query: "", types: [type], limit: 8, perSource: 6)
        let built = await withTaskGroup(of: (Int, HomeRow?).self) { group in
            for (index, row) in rows.enumerated() {
                group.addTask { (index, await self.fetchRow(row, fallbackType: type)) }
            }
            var collected: [(Int, HomeRow?)] = []
            for await pair in group { collected.append(pair) }
            return collected.sorted { $0.0 < $1.0 }.compactMap { $0.1 }
        }
        return built
    }

    func search(_ query: String) async -> [MediaItem] {
        guard !query.isEmpty else { return [] }
        let loaded = await loadSources()
        guard !loaded.isEmpty else { return [] }
        let rows = plan(query: query, types: ["movie", "series"], limit: 6, perSource: 3)
        guard !rows.isEmpty else { return [] }
        let items = await withTaskGroup(of: [MediaItem].self) { group in
            for row in rows {
                group.addTask { await self.fetchItems(row, limit: 20, fallbackType: nil) }
            }
            var collected: [MediaItem] = []
            for await batch in group { collected.append(contentsOf: batch) }
            return collected
        }
        var seen = Set<String>()
        let unique = items.filter { seen.insert($0.key).inserted }
        return unique
            .sorted { max($0.tmdbRating, $0.imdbRating) > max($1.tmdbRating, $1.imdbRating) }
            .prefix(40)
            .map { $0 }
    }

    func detail(key: String, type: String) async -> DetailData? {
        if let cached = details[key] { return cached }
        let loaded = await loadSources()
        guard !loaded.isEmpty else { return nil }
        let ordered = loaded.sorted { lhs, rhs in
            let l = lhs.id.lowercased().contains("nuvio") ? 0 : 1
            let r = rhs.id.lowercased().contains("nuvio") ? 0 : 1
            return l < r
        }
        for source in ordered {
            guard let payload = await http.tryJSON(
                "\(source.baseURL)/meta/\(type)/\(key).json",
                settings: settings.value
            ), let meta = payload["meta"] as? [String: Any],
               let item = metaToItem(meta, fallbackType: type) else { continue }
            let seasons = seasonsFrom(meta, item: item)
            let scores = await rt.scores(item: item)
            var full = item
            full.tomato = scores.tomato
            full.audience = scores.audience
            full.seasons = seasons.count
            let detail = DetailData(item: full, seasons: seasons, similar: [])
            details[key] = detail
            details[full.key] = detail
            return detail
        }
        return nil
    }

    func seasonEpisodes(_ item: MediaItem, season: Int) async -> [EpisodeInfo] {
        if let cached = details[item.key],
           let found = cached.seasons.first(where: { $0.number == season }) {
            return found.episodes
        }
        guard let detail = await detail(key: item.key, type: item.type),
              let found = detail.seasons.first(where: { $0.number == season }) else {
            return []
        }
        return found.episodes
    }

    func identifiers(_ item: MediaItem) async -> MediaItem {
        var copy = item
        if item.key.hasPrefix("tt") { copy.imdbId = item.key }
        if item.key.hasPrefix("tmdb:") {
            copy.tmdbId = String(item.key.dropFirst("tmdb:".count))
        }
        if copy.imdbId.isEmpty, !copy.tmdbId.isEmpty,
           let resolved = await detail(key: item.key, type: item.type) {
            // Nuvio resuelve tmdb:… a tt…; conviene tener IMDb antes de pedir streams.
            copy.imdbId = resolved.item.imdbId
            if copy.tmdbId.isEmpty { copy.tmdbId = resolved.item.tmdbId }
        }
        return copy
    }

    // MARK: Manifests y planificación

    private func loadSources(force: Bool = false) async -> [Source] {
        if !force, !sources.isEmpty, Date().timeIntervalSince(sourcesAt) < 1_800 {
            return sources
        }
        let urls = settings.value.catalogSources
        guard !urls.isEmpty else {
            sources = []
            return []
        }
        var loaded: [Source] = []
        for url in urls {
            guard let text = try? await http.text(url, settings: settings.value),
                  let data = text.data(using: .utf8),
                  let object = try? JSONSerialization.jsonObject(with: data),
                  let dict = object as? [String: Any],
                  let id = dict["id"] as? String, !id.isEmpty else { continue }
            loaded.append(
                Source(
                    manifestURL: url,
                    baseURL: baseURL(of: url),
                    manifest: JSONValue.from(dict),
                    id: id,
                    name: (dict["name"] as? String) ?? "Catálogo"
                )
            )
        }
        if !loaded.isEmpty {
            sources = loaded
            sourcesAt = Date()
        }
        return loaded.isEmpty ? sources : loaded
    }

    private func plan(query: String, types: [String], limit: Int, perSource: Int) -> [CatalogPlanRow] {
        guard !sources.isEmpty else { return [] }
        let request = CatalogPlanRequest(
            types: types,
            query: query,
            limit: limit,
            perSource: perSource,
            sources: sources.map { CatalogPlanSource(baseUrl: $0.baseURL, manifest: $0.manifest) }
        )
        return NativeCore.catalogPlan(request)?.rows ?? []
    }

    private func fetchRow(_ row: CatalogPlanRow, fallbackType: String? = nil) async -> HomeRow? {
        let items = await fetchItems(row, limit: 20, fallbackType: fallbackType)
        guard !items.isEmpty else { return nil }
        return HomeRow(
            title: prettyRowName(row),
            subtitle: prettyAddon(row.addonId, row.addonName),
            badge: "",
            items: items
        )
    }

    private func fetchItems(_ row: CatalogPlanRow, limit: Int, fallbackType: String?) async -> [MediaItem] {
        guard let payload = await http.tryJSON(row.url, settings: settings.value) else { return [] }
        return payload.arr("metas")
            .compactMap { metaToItem($0, fallbackType: fallbackType ?? row.type) }
            .prefix(limit)
            .map { $0 }
    }

    // MARK: Mapeo

    private func metaToItem(_ meta: [String: Any], fallbackType: String) -> MediaItem? {
        let id = meta.str("id").isEmpty ? meta.str("imdb_id") : meta.str("id")
        guard !id.isEmpty else { return nil }
        let type = meta.str("type").isEmpty ? fallbackType : meta.str("type")
        guard type == "movie" || type == "series" else { return nil }
        let title = meta.str("name").isEmpty ? meta.str("title") : meta.str("name")
        guard !title.isEmpty else { return nil }

        let imdb = id.hasPrefix("tt") ? id : meta.str("imdb_id")
        var tmdb = ""
        if id.hasPrefix("tmdb:") {
            tmdb = String(id.dropFirst("tmdb:".count))
        } else if meta.int("moviedb_id") > 0 {
            tmdb = String(meta.int("moviedb_id"))
        }
        let release = meta.str("releaseInfo").isEmpty ? meta.str("year") : meta.str("releaseInfo")
        var genres = meta.strList("genres")
        genres.append(contentsOf: meta.strList("genre"))
        let ratings = meta.dict("app_extras")?.dict("ratings")
        let tmdbRating = Double(ratings?.dict("tmdb")?.str("rating") ?? "") ?? 0
        let imdbRating = Double(meta.str("imdbRating"))
            ?? Double(ratings?.dict("imdb")?.str("rating") ?? "")
            ?? 0
        let trailer = meta.arr("trailers")
            .first { $0.str("type").lowercased() == "trailer" }?
            .str("source") ?? ""

        return MediaItem(
            key: id,
            type: type,
            tmdbId: tmdb,
            imdbId: imdb,
            title: title,
            originalTitle: title,
            year: Int(release.prefix(4)) ?? 0,
            overview: meta.str("description"),
            poster: upscale(meta.str("poster")),
            backdrop: upscale(meta.str("background").isEmpty ? meta.str("poster") : meta.str("background")),
            tmdbRating: tmdbRating,
            genres: Array(Set(genres)),
            runtime: parseRuntime(meta.str("runtime")),
            cast: Array(meta.strList("cast").prefix(10)),
            trailerKey: trailer,
            originalLanguage: meta.str("language"),
            logo: meta.str("logo"),
            imdbRating: imdbRating
        )
    }

    private func seasonsFrom(_ meta: [String: Any], item: MediaItem) -> [SeasonInfo] {
        guard item.type == "series" else { return [] }
        var grouped: [Int: [EpisodeInfo]] = [:]
        for video in meta.arr("videos") {
            let season = video.int("season")
            let number = video.int("episode") > 0 ? video.int("episode") : video.int("number")
            guard season > 0, number > 0 else { continue }
            let episode = EpisodeInfo(
                number: number,
                title: video.str("title").isEmpty ? video.str("name") : video.str("title"),
                overview: video.str("overview").isEmpty ? video.str("description") : video.str("overview"),
                still: video.str("thumbnail"),
                runtime: parseRuntime(video.str("runtime")),
                airDate: video.str("released").isEmpty ? video.str("firstAired") : video.str("released")
            )
            grouped[season, default: []].append(episode)
        }
        return grouped.keys.sorted().map { season in
            SeasonInfo(
                number: season,
                name: "Temporada \(season)",
                episodes: grouped[season, default: []].sorted { $0.number < $1.number }
            )
        }
    }

    private func enrich(_ items: [MediaItem], limit: Int) async -> [MediaItem] {
        guard settings.value.rtScoresAvailable, !items.isEmpty else { return items }
        var result = items
        await withTaskGroup(of: (Int, RtScores).self) { group in
            for (index, item) in items.prefix(limit).enumerated() {
                group.addTask { (index, await self.rt.scores(item: item)) }
            }
            for await (index, scores) in group {
                result[index].tomato = scores.tomato
                result[index].audience = scores.audience
            }
        }
        return result
    }

    private func baseURL(of manifestURL: String) -> String {
        let clean = manifestURL.components(separatedBy: "?").first ?? manifestURL
        let trimmed = clean.hasSuffix("/") ? String(clean.dropLast()) : clean
        if trimmed.hasSuffix("/manifest.json") {
            return String(trimmed.dropLast("/manifest.json".count))
        }
        return trimmed
    }

    private func upscale(_ url: String) -> String {
        url.replacingOccurrences(of: "/poster/small/", with: "/poster/medium/")
            .replacingOccurrences(of: "/background/small/", with: "/background/medium/")
    }

    private func prettyAddon(_ id: String, _ name: String) -> String {
        if id.lowercased().contains("nuvio") { return "Nuvio" }
        if id.lowercased().contains("cinemeta") { return "Cinemeta" }
        return name.isEmpty ? "Catálogo" : name
    }

    private func parseRuntime(_ value: String) -> Int {
        let text = value.lowercased()
        if let hoursAt = text.firstIndex(of: "h") {
            let hours = firstInt(text[..<hoursAt]) ?? 0
            let minutes = firstInt(text[text.index(after: hoursAt)...]) ?? 0
            return hours * 60 + minutes
        }
        return firstInt(text[...]) ?? 0
    }

    private func firstInt(_ text: Substring) -> Int? {
        let digits = text.drop(while: { !$0.isNumber }).prefix(while: { $0.isNumber })
        return Int(digits)
    }

    private func prettyRowName(_ row: CatalogPlanRow) -> String {
        let names: [String: String]
        if row.addonId.lowercased().contains("cinemeta") {
            names = [
                "top": "Popular en IMDb",
                "imdbRating": "Mejor valoradas"
            ]
        } else {
            names = [
                "trending-movies": "Tendencias en películas",
                "trending-series": "Series en tendencia",
                "popular-movies": "Películas populares",
                "popular-series": "Series populares",
                "critically-acclaimed-movies": "Aclamadas por la crítica",
                "critically-acclaimed-series": "Series aclamadas",
                "airing-today-series": "Hoy en emisión",
                "new-and-upcoming-movies": "Estrenos y próximos estrenos",
                "new-and-returning-series": "Series nuevas y de regreso",
                "netflix-movies": "Netflix · películas",
                "netflix-series": "Netflix · series",
                "disney-plus-movies": "Disney+ · películas",
                "disney-plus-series": "Disney+ · series",
                "prime-video-movies": "Prime Video · películas",
                "prime-video-series": "Prime Video · series",
                "international-cinema": "Cine internacional",
                "international-series": "Series internacionales",
                "independent-and-festival-picks": "Independientes y festivales",
                "limited-series": "Miniseries",
                "documentaries": "Documentales",
                "documentary-series": "Series documentales",
                "family-movies": "Para toda la familia",
                "family-series": "Series para toda la familia"
            ]
        }
        return names[row.catalogId] ?? (row.name.isEmpty ? row.catalogId : row.name)
    }
}

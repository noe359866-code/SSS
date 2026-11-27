import CoreFoundation
import Foundation

// MARK: - Mensajes para la interfaz

enum Messages {
    static let noOptions = "No hay ninguna opción disponible ahora mismo para este perfil."
    static let playbackExhausted = "No encontramos una opción compatible. Prueba con otro perfil."
    static let network = "No hemos podido conectar con el servicio. Inténtalo de nuevo en un momento."
    static let manifest = "Ese enlace no es válido o está incompleto."
    static let generic = "Algo no ha salido como esperábamos. Inténtalo de nuevo."
    static let notFound = "No encontramos ese título."
    static let needBaseUrl = "Añade el enlace del plugin."
    static let catalog = "No hemos podido cargar el catálogo. Revisa tus fuentes en Ajustes."

    /// Elimina cualquier código técnico de un texto mostrable.
    static func scrub(_ text: String) -> String {
        var clean = text
        let banned = [
            "HTTP", "http://", "https://", "CORS", "ECONN", "ERR_", "status",
            "401", "403", "404", "408", "429", "500", "502", "503", "504",
            "undefined", "null"
        ]
        for token in banned {
            clean = clean.replacingOccurrences(of: token, with: "", options: .caseInsensitive)
        }
        return clean
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

struct FriendlyError: LocalizedError {
    let message: String
    init(_ message: String = Messages.generic) { self.message = message }
    var errorDescription: String? { message }
}

// MARK: - Manifest

struct ResourceInfo: Codable, Hashable {
    var name: String = ""
    var types: [String] = []
    var idPrefixes: [String] = []
}

struct CatalogInfo: Codable, Hashable, Identifiable {
    var type: String = ""
    var id: String = ""
    var name: String = ""
    var extra: [String] = []
    var stableID: String { "\(type):\(id)" }
}

struct Capabilities: Codable, Hashable {
    var streams: Bool = false
    var catalog: Bool = false
    var meta: Bool = false
    var subtitles: Bool = false
}

struct ManifestInfo: Codable, Hashable {
    var id: String = ""
    var name: String = ""
    var version: String = ""
    var description: String = ""
    var logo: String = ""
    var background: String = ""
    var baseUrl: String = ""
    var manifestUrl: String = ""
    var types: [String] = []
    var resources: [ResourceInfo] = []
    var catalogs: [CatalogInfo] = []
    var idPrefixes: [String] = []
    var capabilities: Capabilities = Capabilities()

    var resourceNames: [String] { resources.map(\.name) }
}

struct ManifestEnvelope: Decodable {
    var ok: Bool = false
    var manifest: ManifestInfo?
    var warnings: [String] = []
    var message: String?
}

struct Plugin: Codable, Hashable, Identifiable {
    var key: String
    var manifest: ManifestInfo
    var enabled: Bool = true
    var priority: Int = 0
    var installedAt: Double = Date().timeIntervalSince1970
    var origin: String = "url"
    var rawManifest: String = ""

    var id: String { key }
    var canStream: Bool { manifest.capabilities.streams }
    var canCatalog: Bool { manifest.capabilities.catalog && !manifest.catalogs.isEmpty }
}

// MARK: - Perfiles

struct Profile: Codable, Hashable, Identifiable {
    var id: String
    var name: String
    var description: String = ""
    var accent: String = "#22d3ee"
    var languages: [String] = []
    var targetQuality: Int = 1080
    var maxQuality: Int = 2160
    var allowCam: Bool = false
    var allowTorrent: Bool = false
    var preferHdr: Bool = false
    var preferSubtitles: Bool = false
    var preferDual: Bool = true
    var strictLanguage: Bool = false
    var dataSaver: Bool = false

    var summary: String {
        let languagesText = languages.isEmpty ? "cualquier idioma" : languages.joined(separator: " · ")
        return "\(languagesText) · \(maxQuality)p máx"
    }
}

struct ProfilesEnvelope: Decodable {
    var ok: Bool = false
    var profiles: [Profile] = []
}

enum FallbackProfiles {
    static let all: [Profile] = [
        Profile(id: "latino-1080", name: "Latino 1080p", description: "Audio latino; Full HD como tope.",
                accent: "#22d3ee", languages: ["latino", "dual", "es"],
                targetQuality: 1080, maxQuality: 1080),
        Profile(id: "latino-4k", name: "Latino 4K", description: "Audio latino; máxima calidad disponible.",
                accent: "#f43f5e", languages: ["latino", "dual", "es"],
                targetQuality: 2160, maxQuality: 2160, preferHdr: true),
        Profile(id: "castellano", name: "Castellano", description: "Español de España.",
                accent: "#f59e0b", languages: ["castellano", "es", "dual"],
                targetQuality: 1080, maxQuality: 1080),
        Profile(id: "subtitulado", name: "Subtitulado", description: "Voz original y subtítulos.",
                accent: "#a78bfa", languages: ["en", "original"],
                targetQuality: 2160, maxQuality: 2160,
                preferHdr: true, preferSubtitles: true, preferDual: false),
        Profile(id: "movil", name: "Móvil / datos", description: "Menos peso, arranque rápido.",
                accent: "#34d399", languages: ["latino", "dual", "es", "castellano"],
                targetQuality: 720, maxQuality: 1080, dataSaver: true),
        Profile(id: "automatico", name: "Automático", description: "La mejor opción disponible, sin filtros de idioma.",
                accent: "#94a3b8", targetQuality: 1080, maxQuality: 2160)
    ]
}

// MARK: - Plan y ranking (núcleo C++)

struct PlanRequest: Encodable {
    var type: String
    var imdbId: String = ""
    var tmdbId: String = ""
    var season: Int = 0
    var episode: Int = 0
    var addons: [PlanAddon]

    struct PlanAddon: Encodable {
        var id: String
        var manifest: ManifestInfo?
        var baseUrl: String = ""
    }
}

struct PlanEnvelope: Decodable {
    var ok: Bool = false
    var addons: [PlannedAddon] = []
    var message: String?
}

struct PlannedAddon: Decodable, Hashable {
    var id: String = ""
    var name: String = ""
    var ids: [String] = []
    var urls: [String] = []
}

struct SubtitleTrack: Decodable, Hashable {
    var url: String = ""
    var lang: String = ""
    var label: String = ""
}

struct StreamChoice: Decodable, Hashable, Identifiable {
    var addonId: String = ""
    var addonName: String = ""
    var url: String = ""
    var externalUrl: String = ""
    var quality: Int = 0
    var qualityLabel: String = "Auto"
    var languageLabel: String = ""
    var title: String = ""
    var hdr: Bool = false
    var cam: Bool = false
    var torrent: Bool = false
    var hevc: Bool = false
    var sizeBytes: Int64 = 0
    var score: Int = 0
    var role: String = "backup"
    var languages: [String] = []
    var subtitles: [SubtitleTrack] = []

    var id: String { "\(url)|\(quality)|\(languageLabel)" }
    var label: String {
        [qualityLabel, languageLabel, hdr ? "HDR" : ""]
            .filter { !$0.isEmpty }
            .joined(separator: " · ")
    }
}

struct RankEnvelope: Decodable {
    var ok: Bool = false
    var received: Int = 0
    var playable: Int = 0
    var rejected: Int = 0
    var message: String = ""
    var candidates: [StreamChoice] = []
}

enum JSONValue: Codable, Hashable {
    case object([String: JSONValue])
    case array([JSONValue])
    case string(String)
    case number(Double)
    case bool(Bool)
    case null

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Double.self) {
            self = .number(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode([String: JSONValue].self) {
            self = .object(value)
        } else if let value = try? container.decode([JSONValue].self) {
            self = .array(value)
        } else {
            self = .null
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .object(let value): try container.encode(value)
        case .array(let value): try container.encode(value)
        case .string(let value): try container.encode(value)
        case .number(let value): try container.encode(value)
        case .bool(let value): try container.encode(value)
        case .null: try container.encodeNil()
        }
    }

    var objectValue: [String: JSONValue]? {
        if case .object(let value) = self { return value }
        return nil
    }

    var arrayValue: [JSONValue]? {
        if case .array(let value) = self { return value }
        return nil
    }

    /// Construye un JSONValue desde JSONSerialization.
    static func from(_ any: Any) -> JSONValue {
        if any is NSNull { return .null }
        if let dict = any as? [String: Any] {
            return .object(dict.mapValues { from($0) })
        }
        if let array = any as? [Any] {
            return .array(array.map { from($0) })
        }
        if let value = any as? String { return .string(value) }
        if let number = any as? NSNumber {
            if CFGetTypeID(number) == CFBooleanGetTypeID() {
                return .bool(number.boolValue)
            }
            return .number(number.doubleValue)
        }
        return .null
    }
}

struct AddonPayload: Encodable {
    var id: String
    var name: String
    var priority: Int
    var payload: JSONValue
}

struct RankOptions: Encodable {
    var allowTorrents: Bool = false
    var limit: Int = 12
    var includeBackups: Bool = true
}

struct RankRequest: Encodable {
    var profile: Profile
    var addons: [AddonPayload]
    var options: RankOptions
}

// MARK: - Catálogo

struct MediaItem: Codable, Hashable, Identifiable {
    var key: String
    var type: String
    var tmdbId: String = ""
    var imdbId: String = ""
    var title: String = ""
    var originalTitle: String = ""
    var year: Int = 0
    var overview: String = ""
    var poster: String = ""
    var backdrop: String = ""
    var tmdbRating: Double = 0
    var tmdbVotes: Int = 0
    var tomato: Int?
    var audience: Int?
    var genres: [String] = []
    var runtime: Int = 0
    var seasons: Int = 0
    var cast: [String] = []
    var trailerKey: String = ""
    var originalLanguage: String = ""
    var logo: String = ""
    var imdbRating: Double = 0

    var scoreLabel: String {
        let value = imdbRating > 0 ? imdbRating : tmdbRating
        return value > 0 ? String(format: "%.1f", value) : ""
    }

    var id: String { key }
    var isSeries: Bool { type == "series" }
    var isAnime: Bool {
        genres.contains { $0.range(of: "anim", options: .caseInsensitive) != nil } ||
            originalLanguage.lowercased() == "ja"
    }
}

struct EpisodeInfo: Codable, Hashable, Identifiable {
    var number: Int
    var title: String = ""
    var overview: String = ""
    var still: String = ""
    var runtime: Int = 0
    var airDate: String = ""
    var id: Int { number }
}

struct SeasonInfo: Codable, Hashable, Identifiable {
    var number: Int
    var name: String = ""
    var episodes: [EpisodeInfo] = []
    var id: Int { number }
}

struct DetailData: Codable, Hashable {
    var item: MediaItem
    var seasons: [SeasonInfo] = []
    var similar: [MediaItem] = []
}

struct HomeRow: Codable, Hashable, Identifiable {
    var title: String
    var subtitle: String = ""
    var badge: String = ""
    var items: [MediaItem] = []
    var id: String { title }
}

struct CatalogSnapshot: Codable, Hashable {
    var rows: [HomeRow] = []
    var message: String = ""
}

// MARK: - Catálogos de addons (Nuvio · Cinemeta)

struct CatalogPlanSource: Encodable {
    var baseUrl: String
    var manifest: JSONValue
}

struct CatalogPlanRequest: Encodable {
    var types: [String] = ["movie", "series"]
    var query: String = ""
    var limit: Int = 12
    var perSource: Int = 12
    var sources: [CatalogPlanSource] = []
}

struct CatalogPlanRow: Decodable, Hashable, Identifiable {
    var addonId: String = ""
    var addonName: String = ""
    var baseUrl: String = ""
    var type: String = ""
    var catalogId: String = ""
    var name: String = ""
    var url: String = ""
    var search: Bool = false

    var id: String { "\(addonId)|\(type)|\(catalogId)" }
}

struct CatalogPlanEnvelope: Decodable {
    var ok: Bool = false
    var count: Int = 0
    var message: String = ""
    var rows: [CatalogPlanRow] = []
}

// MARK: - Saltos (TheIntroDB · IntroDB · AniSkip · Anime Skip)

struct SkipSource: Encodable {
    var source: String
    var payload: JSONValue
}

struct SkipRequest: Encodable {
    var durationMs: Double
    var priority: [String] = []
    var sources: [SkipSource] = []
}

struct SkipSegment: Codable, Hashable, Identifiable {
    var kind: String = ""
    var label: String = ""
    var startMs: Double = 0
    var endMs: Double = 0
    var source: String = ""
    var confidence: Double? = nil

    var id: String { "\(kind)-\(startMs)" }

    func contains(_ positionMs: Double) -> Bool {
        positionMs + 1200 >= startMs && positionMs < endMs - 900
    }
}

struct SkipEnvelope: Decodable {
    var ok: Bool = false
    var count: Int = 0
    var segments: [SkipSegment] = []
    var message: String = ""
}

// MARK: - Biblioteca

struct LibraryEntry: Codable, Hashable, Identifiable {
    var item: MediaItem
    var addedAt: Double = Date().timeIntervalSince1970
    var updatedAt: Double = Date().timeIntervalSince1970
    var positionMs: Double = 0
    var durationMs: Double = 0
    var season: Int = 0
    var episode: Int = 0
    var saved: Bool = true

    var id: String { item.key }
    var hasProgress: Bool {
        positionMs > 30_000 && durationMs > 0 && positionMs < durationMs * 0.96
    }
    var progress: Double {
        durationMs <= 0 ? 0 : min(max(positionMs / durationMs, 0), 1)
    }
}

// MARK: - Ajustes y copia de seguridad

enum CatalogDefaults {
    static let sources = [
        "https://catalog.nuvio.tv/manifest.json",
        "https://v3-cinemeta.strem.io/manifest.json"
    ]
}

struct AppSettings: Codable, Hashable {
    var omdbKey: String = ""
    var language: String = "es-ES"
    var region: String = "ES"
    var profileId: String = "latino-1080"
    var proxyTemplate: String = ""
    var userAgent: String = "Cineflux/1.0 (iOS)"
    var allowTorrents: Bool = false
    var autoplayNext: Bool = true
    var onboardingDone: Bool = false
    var skipSegmentsEnabled: Bool = true
    var autoSkipIntro: Bool = false
    var autoSkipRecap: Bool = true
    var autoSkipCredits: Bool = false
    var animeSkipClientId: String = ""
    var catalogSources: [String] = CatalogDefaults.sources
    var defaultPlaybackSpeed: Double = 1.0
    var seekStepSeconds: Int = 10
    var rememberPosition: Bool = true
    var subtitlesEnabled: Bool = true
    var subtitleLanguage: String = "es"
    var subtitleSize: String = "medium"
    var pluginTimeoutSec: Int = 12
    var wifiOnlyStreaming: Bool = false
    var compactCards: Bool = false
    var showRatingsOnCards: Bool = true

    init() {}

    /// Decodificación tolerante: las copias antiguas no traen los campos de
    /// saltos y deben seguir funcionando.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        omdbKey = try container.decodeIfPresent(String.self, forKey: .omdbKey) ?? ""
        language = try container.decodeIfPresent(String.self, forKey: .language) ?? "es-ES"
        region = try container.decodeIfPresent(String.self, forKey: .region) ?? "ES"
        profileId = try container.decodeIfPresent(String.self, forKey: .profileId) ?? "latino-1080"
        proxyTemplate = try container.decodeIfPresent(String.self, forKey: .proxyTemplate) ?? ""
        userAgent = try container.decodeIfPresent(String.self, forKey: .userAgent) ?? "Cineflux/1.0 (iOS)"
        allowTorrents = try container.decodeIfPresent(Bool.self, forKey: .allowTorrents) ?? false
        autoplayNext = try container.decodeIfPresent(Bool.self, forKey: .autoplayNext) ?? true
        onboardingDone = try container.decodeIfPresent(Bool.self, forKey: .onboardingDone) ?? false
        skipSegmentsEnabled = try container.decodeIfPresent(Bool.self, forKey: .skipSegmentsEnabled) ?? true
        autoSkipIntro = try container.decodeIfPresent(Bool.self, forKey: .autoSkipIntro) ?? false
        autoSkipRecap = try container.decodeIfPresent(Bool.self, forKey: .autoSkipRecap) ?? true
        autoSkipCredits = try container.decodeIfPresent(Bool.self, forKey: .autoSkipCredits) ?? false
        animeSkipClientId = try container.decodeIfPresent(String.self, forKey: .animeSkipClientId) ?? ""
        catalogSources = try container.decodeIfPresent([String].self, forKey: .catalogSources) ?? CatalogDefaults.sources
        defaultPlaybackSpeed = try container.decodeIfPresent(Double.self, forKey: .defaultPlaybackSpeed) ?? 1.0
        seekStepSeconds = try container.decodeIfPresent(Int.self, forKey: .seekStepSeconds) ?? 10
        rememberPosition = try container.decodeIfPresent(Bool.self, forKey: .rememberPosition) ?? true
        subtitlesEnabled = try container.decodeIfPresent(Bool.self, forKey: .subtitlesEnabled) ?? true
        subtitleLanguage = try container.decodeIfPresent(String.self, forKey: .subtitleLanguage) ?? "es"
        subtitleSize = try container.decodeIfPresent(String.self, forKey: .subtitleSize) ?? "medium"
        pluginTimeoutSec = try container.decodeIfPresent(Int.self, forKey: .pluginTimeoutSec) ?? 12
        wifiOnlyStreaming = try container.decodeIfPresent(Bool.self, forKey: .wifiOnlyStreaming) ?? false
        compactCards = try container.decodeIfPresent(Bool.self, forKey: .compactCards) ?? false
        showRatingsOnCards = try container.decodeIfPresent(Bool.self, forKey: .showRatingsOnCards) ?? true
    }

    var hasCatalogSources: Bool { !catalogSources.isEmpty }
    var hasOmdb: Bool { !omdbKey.trimmingCharacters(in: .whitespaces).isEmpty }
    var rtScoresAvailable: Bool { hasOmdb || !proxyTemplate.isEmpty }
}

struct Backup: Codable {
    var app: String = "cineflux"
    var version: Int = 1
    var settings: AppSettings = AppSettings()
    var plugins: [Plugin] = []
    var profiles: [Profile] = []
    var library: [LibraryEntry] = []
}

// MARK: - Utilidades JSON para TMDB/RT

extension Dictionary where Key == String, Value == Any {
    func str(_ key: String) -> String {
        if let value = self[key] as? String, value != "<null>" { return value }
        if let value = self[key] as? NSNumber { return value.stringValue }
        return ""
    }

    func int(_ key: String) -> Int {
        if let value = self[key] as? Int { return value }
        if let value = self[key] as? NSNumber { return value.intValue }
        if let value = self[key] as? String { return Int(value) ?? 0 }
        return 0
    }

    func double(_ key: String) -> Double {
        if let value = self[key] as? Double { return value }
        if let value = self[key] as? NSNumber { return value.doubleValue }
        if let value = self[key] as? String { return Double(value) ?? 0 }
        return 0
    }

    func bool(_ key: String) -> Bool {
        if let value = self[key] as? Bool { return value }
        if let value = self[key] as? NSNumber { return value.boolValue }
        return false
    }

    func arr(_ key: String) -> [[String: Any]] {
        (self[key] as? [[String: Any]]) ?? []
    }

    func dict(_ key: String) -> [String: Any]? {
        self[key] as? [String: Any]
    }

    func strList(_ key: String) -> [String] {
        (self[key] as? [Any])?.compactMap { $0 as? String } ?? []
    }
}

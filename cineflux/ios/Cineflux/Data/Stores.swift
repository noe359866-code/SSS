import Foundation

// MARK: - Ajustes

/// Ajustes locales. Es `Sendable` porque solo guarda valores inmutables y
/// `UserDefaults`; las mutaciones llegan desde la interfaz.
final class SettingsStore: ObservableObject, @unchecked Sendable {
    @Published var value: AppSettings {
        didSet { persist() }
    }

    private let defaults = UserDefaults.standard
    private var ready = false

    init() {
        let loaded: AppSettings
        if let data = UserDefaults.standard.data(forKey: "cineflux.settings.v1"),
           let decoded = try? JSONDecoder().decode(AppSettings.self, from: data) {
            loaded = decoded
        } else {
            loaded = AppSettings()
        }
        value = loaded
        ready = true
    }

    func update(_ block: (inout AppSettings) -> Void) {
        var copy = value
        block(&copy)
        value = copy
    }

    func raw(_ key: String) -> Data? { defaults.data(forKey: key) }
    func putRaw(_ key: String, _ data: Data?) {
        if let data { defaults.set(data, forKey: key) } else { defaults.removeObject(forKey: key) }
    }

    func clearAll() {
        defaults.removeObject(forKey: "cineflux.settings.v1")
        value = AppSettings()
    }

    private func persist() {
        guard ready else { return }
        if let data = try? JSONEncoder().encode(value) {
            defaults.set(data, forKey: "cineflux.settings.v1")
        }
    }
}

// MARK: - Plugins

@MainActor
final class PluginStore: ObservableObject {
    @Published private(set) var plugins: [Plugin] = []

    private let settings: SettingsStore
    private let http: HTTPClient
    private let key = "cineflux.plugins.v1"

    init(settings: SettingsStore, http: HTTPClient) {
        self.settings = settings
        self.http = http
        if let data = settings.raw(key), let decoded = try? JSONDecoder().decode([Plugin].self, from: data) {
            plugins = decoded.filter { $0.canStream }.sorted { $0.priority < $1.priority }
        }
    }

    var streamPlugins: [Plugin] {
        plugins.filter { $0.enabled && $0.canStream && !$0.manifest.baseUrl.isEmpty }
            .sorted { $0.priority < $1.priority }
    }

    func setEnabled(_ plugin: Plugin, enabled: Bool) {
        update(plugin.key) { $0.enabled = enabled }
    }

    func remove(_ plugin: Plugin) {
        plugins.removeAll { $0.key == plugin.key }
        persist()
    }

    func move(_ plugin: Plugin, delta: Int) {
        guard let index = plugins.firstIndex(where: { $0.key == plugin.key }) else { return }
        let target = min(max(index + delta, 0), plugins.count - 1)
        guard target != index else { return }
        var list = plugins
        let item = list.remove(at: index)
        list.insert(item, at: target)
        plugins = list.enumerated().map { offset, element in
            var copy = element
            copy.priority = offset
            return copy
        }
        persist()
    }

    func replaceAll(_ list: [Plugin]) {
        plugins = list.filter { $0.canStream }.sorted { $0.priority < $1.priority }.enumerated().map { offset, element in
            var copy = element
            copy.priority = offset
            return copy
        }
        persist()
    }

    func install(input: String, baseURL: String = "") async -> (Bool, String) {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return (false, Messages.needBaseUrl) }

        let isURL = trimmed.lowercased().hasPrefix("http://") || trimmed.lowercased().hasPrefix("https://")
        guard isURL else { return (false, Messages.needBaseUrl) }
        var envelope: ManifestEnvelope?
        if isURL {
            guard let body = try? await http.text(trimmed, settings: settings.value) else {
                return (false, Messages.network)
            }
            envelope = NativeCore.manifest(body, baseURL: baseURL.isEmpty ? trimmed : baseURL)
        } else {
            envelope = NativeCore.manifest(trimmed, baseURL: baseURL)
        }

        guard let result = envelope, result.ok, var manifest = result.manifest else {
            let message = Messages.scrub(result?.message ?? Messages.manifest)
            return (false, message.isEmpty ? Messages.manifest : message)
        }
        guard !manifest.baseUrl.isEmpty else { return (false, Messages.needBaseUrl) }
        guard !manifest.resources.isEmpty else { return (false, Messages.manifest) }
        guard manifest.capabilities.streams else {
            return (false, "Ese plugin solo aporta catálogo. Añádelo como fuente en Ajustes → Catálogo.")
        }
        manifest.manifestUrl = manifest.manifestUrl.isEmpty
            ? manifest.baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/manifest.json"
            : manifest.manifestUrl

        let pluginKey = manifest.baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let existing = plugins.first { $0.key == pluginKey }
        let plugin = Plugin(
            key: pluginKey,
            manifest: manifest,
            enabled: existing?.enabled ?? true,
            priority: existing?.priority ?? plugins.count,
            installedAt: existing?.installedAt ?? Date().timeIntervalSince1970,
            origin: isURL ? "url" : "raw",
            rawManifest: trimmed
        )
        plugins.removeAll { $0.key == pluginKey }
        plugins.append(plugin)
        plugins.sort { $0.priority < $1.priority }
        persist()
        return (true, "\(manifest.name) instalado.")
    }

    func refresh(_ plugin: Plugin) async -> (Bool, String) {
        let url = plugin.manifest.manifestUrl.isEmpty
            ? plugin.manifest.baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/manifest.json"
            : plugin.manifest.manifestUrl
        guard let body = try? await http.text(url, settings: settings.value) else {
            return (false, Messages.network)
        }
        guard let result = NativeCore.manifest(body, baseURL: plugin.manifest.baseUrl),
              result.ok, let manifest = result.manifest else {
            return (false, Messages.manifest)
        }
        update(plugin.key) {
            $0.manifest = manifest
            $0.origin = "url"
        }
        return (true, "\(manifest.name) actualizado.")
    }

    func test(_ plugin: Plugin) async -> (Bool, String) {
        let url = plugin.manifest.manifestUrl.isEmpty
            ? plugin.manifest.baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/manifest.json"
            : plugin.manifest.manifestUrl
        guard let body = try? await http.text(url, settings: settings.value) else {
            return (false, Messages.network)
        }
        guard let result = NativeCore.manifest(body, baseURL: plugin.manifest.baseUrl),
              result.ok, let manifest = result.manifest else {
            return (false, Messages.manifest)
        }
        var parts = [manifest.name]
        if manifest.capabilities.streams { parts.append("streams") }
        if manifest.capabilities.catalog { parts.append("\(manifest.catalogs.count) catálogos") }
        if manifest.capabilities.meta { parts.append("meta") }
        if manifest.capabilities.subtitles { parts.append("subtítulos") }
        return (true, "Responde correctamente · " + parts.joined(separator: " · "))
    }

    func resolve(
        type: String,
        imdbId: String,
        tmdbId: String,
        season: Int,
        episode: Int,
        profile: Profile
    ) async -> RankEnvelope {
        let active = streamPlugins
        guard !active.isEmpty else {
            return RankEnvelope(ok: false, message: Messages.noOptions)
        }
        let request = PlanRequest(
            type: type,
            imdbId: imdbId,
            tmdbId: tmdbId,
            season: season,
            episode: episode,
            addons: active.map { PlanRequest.PlanAddon(id: $0.key, manifest: $0.manifest, baseUrl: $0.manifest.baseUrl) }
        )
        guard let plan = NativeCore.plan(request), plan.ok, !plan.addons.isEmpty else {
            return RankEnvelope(ok: false, message: Messages.noOptions)
        }

        var payloads: [AddonPayload] = []
        await withTaskGroup(of: AddonPayload?.self) { group in
            for planned in plan.addons {
                guard let plugin = active.first(where: { $0.key == planned.id }) else { continue }
                group.addTask { await self.fetchPayload(planned: planned, plugin: plugin) }
            }
            for await item in group {
                if let item { payloads.append(item) }
            }
        }
        guard !payloads.isEmpty else {
            return RankEnvelope(ok: false, message: Messages.noOptions)
        }
        if let ranked = NativeCore.rank(
            profile: profile,
            payloads: payloads,
            allowTorrents: settings.value.allowTorrents
        ) {
            return ranked
        }
        return RankEnvelope(ok: false, message: Messages.noOptions)
    }

    private func fetchPayload(planned: PlannedAddon, plugin: Plugin) async -> AddonPayload? {
        for url in planned.urls {
            guard let json = await http.tryJSON(url, settings: settings.value),
                  let streams = json["streams"] as? [[String: Any]], !streams.isEmpty else {
                continue
            }
            let payload = JSONValue.object([
                "streams": .array(streams.map { JSONValue.from($0) })
            ])
            return AddonPayload(
                id: planned.id,
                name: planned.name.isEmpty ? plugin.manifest.name : planned.name,
                priority: plugin.priority,
                payload: payload
            )
        }
        return nil
    }

    private func update(_ key: String, _ block: (inout Plugin) -> Void) {
        guard let index = plugins.firstIndex(where: { $0.key == key }) else { return }
        var copy = plugins[index]
        block(&copy)
        plugins[index] = copy
        persist()
    }

    private func persist() {
        if let data = try? JSONEncoder().encode(plugins) {
            settings.putRaw(key, data)
        }
    }
}

// MARK: - Perfiles

@MainActor
final class ProfileStore: ObservableObject {
    @Published private(set) var profiles: [Profile] = []
    @Published private(set) var selectedId: String

    private let settings: SettingsStore
    private let key = "cineflux.profiles.v1"

    init(settings: SettingsStore) {
        self.settings = settings
        selectedId = settings.value.profileId
        if let data = settings.raw(key), let decoded = try? JSONDecoder().decode([Profile].self, from: data), !decoded.isEmpty {
            profiles = decoded
        } else {
            profiles = NativeCore.defaultProfiles()
        }
    }

    var selected: Profile {
        profiles.first { $0.id == selectedId } ?? profiles.first ?? FallbackProfiles.all[0]
    }

    func select(_ id: String) {
        guard profiles.contains(where: { $0.id == id }) else { return }
        selectedId = id
        settings.update { $0.profileId = id }
    }

    func save(_ profile: Profile) -> String? {
        if let error = validate(profile) { return error }
        if let index = profiles.firstIndex(where: { $0.id == profile.id }) {
            profiles[index] = profile
        } else {
            profiles.append(profile)
        }
        persist()
        return nil
    }

    func delete(_ profile: Profile) -> String? {
        guard profiles.count > 1 else { return "Necesitas al menos un perfil." }
        profiles.removeAll { $0.id == profile.id }
        if selectedId == profile.id, let first = profiles.first {
            select(first.id)
        }
        persist()
        return nil
    }

    func replaceAll(_ list: [Profile]) {
        guard !list.isEmpty else { return }
        profiles = list
        if !list.contains(where: { $0.id == selectedId }), let first = list.first {
            select(first.id)
        }
        persist()
    }

    func resetToDefaults() {
        profiles = NativeCore.defaultProfiles()
        if let first = profiles.first { select(first.id) }
        persist()
    }

    private func validate(_ profile: Profile) -> String? {
        if profile.name.trimmingCharacters(in: .whitespaces).isEmpty { return "Ponle un nombre al perfil." }
        if profile.name.count > 40 { return "El nombre del perfil es demasiado largo." }
        if profile.strictLanguage && profile.languages.isEmpty {
            return "Elige al menos un idioma si el perfil es estricto."
        }
        let allowed = [360, 480, 720, 1080, 1440, 2160]
        if !allowed.contains(profile.targetQuality) { return "La calidad objetivo no es válida." }
        if !allowed.contains(profile.maxQuality) { return "El techo de calidad no es válido." }
        return nil
    }

    private func persist() {
        if let data = try? JSONEncoder().encode(profiles) {
            settings.putRaw(key, data)
        }
    }
}

// MARK: - Biblioteca

@MainActor
final class LibraryStore: ObservableObject {
    @Published private(set) var entries: [LibraryEntry] = []

    private let settings: SettingsStore
    private let key = "cineflux.library.v1"

    init(settings: SettingsStore) {
        self.settings = settings
        if let data = settings.raw(key), let decoded = try? JSONDecoder().decode([LibraryEntry].self, from: data) {
            entries = decoded.sorted { $0.updatedAt > $1.updatedAt }
        }
    }

    var saved: [LibraryEntry] { entries.filter { $0.saved } }
    var continueWatching: [LibraryEntry] {
        entries.filter { $0.hasProgress }.sorted { $0.updatedAt > $1.updatedAt }
    }

    func isSaved(_ item: MediaItem) -> Bool {
        entries.contains { $0.item.key == item.key && $0.saved }
    }

    func toggle(_ item: MediaItem) {
        if let index = entries.firstIndex(where: { $0.item.key == item.key }) {
            if entries[index].saved {
                if entries[index].hasProgress {
                    entries[index].saved = false
                } else {
                    entries.remove(at: index)
                }
            } else {
                entries[index].saved = true
                entries[index].updatedAt = Date().timeIntervalSince1970
            }
        } else {
            entries.append(LibraryEntry(item: item))
        }
        persist()
    }

    func recordProgress(_ item: MediaItem, positionMs: Double, durationMs: Double, season: Int, episode: Int) {
        guard positionMs > 0, durationMs > 0 else { return }
        let now = Date().timeIntervalSince1970
        if let index = entries.firstIndex(where: { $0.item.key == item.key }) {
            entries[index].positionMs = positionMs
            entries[index].durationMs = durationMs
            entries[index].season = season
            entries[index].episode = episode
            entries[index].updatedAt = now
        } else {
            entries.append(
                LibraryEntry(
                    item: item,
                    addedAt: now,
                    updatedAt: now,
                    positionMs: positionMs,
                    durationMs: durationMs,
                    season: season,
                    episode: episode,
                    saved: false
                )
            )
        }
        persist()
    }

    func remove(_ item: MediaItem) {
        entries.removeAll { $0.item.key == item.key }
        persist()
    }

    func clearProgress() {
        for index in entries.indices {
            entries[index].positionMs = 0
            entries[index].durationMs = 0
        }
        persist()
    }

    func replaceAll(_ list: [LibraryEntry]) {
        entries = list.sorted { $0.updatedAt > $1.updatedAt }
        persist()
    }

    func clearAll() {
        entries = []
        persist()
    }

    private func persist() {
        entries.sort { $0.updatedAt > $1.updatedAt }
        if let data = try? JSONEncoder().encode(entries) {
            settings.putRaw(key, data)
        }
    }
}

// MARK: - Modelo raíz

@MainActor
final class AppModel: ObservableObject {
    let settings: SettingsStore
    let plugins: PluginStore
    let profiles: ProfileStore
    let library: LibraryStore
    let catalog: CatalogRepository
    let skipTimes: SkipTimesService

    private let http: HTTPClient

    init() {
        let http = HTTPClient()
        let settings = SettingsStore()
        self.http = http
        self.settings = settings
        self.plugins = PluginStore(settings: settings, http: http)
        self.profiles = ProfileStore(settings: settings)
        self.library = LibraryStore(settings: settings)
        self.catalog = CatalogRepository(settings: settings, http: http)
        self.skipTimes = SkipTimesService(settings: settings, http: http)
    }

    func exportBackup() -> String {
        let backup = Backup(
            settings: settings.value,
            plugins: plugins.plugins,
            profiles: profiles.profiles,
            library: library.entries
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        guard let data = try? encoder.encode(backup),
              let text = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return text
    }

    func importBackup(_ raw: String) -> String? {
        guard let data = raw.trimmingCharacters(in: .whitespacesAndNewlines).data(using: .utf8),
              let backup = try? JSONDecoder().decode(Backup.self, from: data) else {
            return "Esa copia no tiene el formato de Cineflux."
        }
        guard backup.app == "cineflux" else { return "Esa copia no es de Cineflux." }
        settings.value = backup.settings
        plugins.replaceAll(backup.plugins)
        profiles.replaceAll(backup.profiles)
        library.replaceAll(backup.library)
        return nil
    }

    func clearEverything() {
        plugins.replaceAll([])
        library.clearAll()
        settings.clearAll()
    }
}

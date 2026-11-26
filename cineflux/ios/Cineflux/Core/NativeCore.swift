import Foundation
import CinefluxCore

/// Puente con el núcleo C++ compartido (el mismo que usa Android).
/// Swift aporta la interfaz y la red; el núcleo valida manifests, resuelve
/// identificadores y ordena los streams según el perfil.
enum NativeCore {
    static func version() -> String {
        String(cString: cfx_version())
    }

    static func defaultProfiles() -> [Profile] {
        let envelope: ProfilesEnvelope? = decode(cfx_default_profiles())
        let profiles = envelope?.profiles ?? []
        return profiles.isEmpty ? FallbackProfiles.all : profiles
    }

    static func manifest(_ input: String, baseURL: String = "") -> ManifestEnvelope? {
        var result: ManifestEnvelope?
        input.withCString { manifestPointer in
            baseURL.withCString { basePointer in
                result = decode(cfx_parse_manifest(manifestPointer, basePointer))
            }
        }
        return result
    }

    static func plan(_ request: PlanRequest) -> PlanEnvelope? {
        guard let data = try? JSONEncoder().encode(request) else { return nil }
        return call(data, cfx_plan_requests)
    }

    static func catalogPlan(_ request: CatalogPlanRequest) -> CatalogPlanEnvelope? {
        guard let data = try? JSONEncoder().encode(request) else { return nil }
        return call(data, cfx_plan_catalog)
    }

    static func skip(durationMs: Double, sources: [SkipSource]) -> SkipEnvelope? {
        guard durationMs > 0, !sources.isEmpty else { return nil }
        let request = SkipRequest(durationMs: durationMs, sources: sources)
        guard let data = try? JSONEncoder().encode(request) else { return nil }
        return call(data, cfx_skip_segments)
    }

    static func rank(profile: Profile, payloads: [AddonPayload], allowTorrents: Bool) -> RankEnvelope? {
        let request = RankRequest(
            profile: profile,
            addons: payloads,
            options: RankOptions(allowTorrents: allowTorrents, limit: 12, includeBackups: true)
        )
        guard let data = try? JSONEncoder().encode(request) else { return nil }
        return call(data, cfx_rank_streams)
    }

    private static func call<T: Decodable>(
        _ data: Data,
        _ function: (UnsafePointer<CChar>?) -> UnsafePointer<CChar>?
    ) -> T? {
        guard let text = String(data: data, encoding: .utf8) else { return nil }
        var result: T?
        text.withCString { pointer in
            result = decode(function(pointer))
        }
        return result
    }

    private static func decode<T: Decodable>(_ pointer: UnsafePointer<CChar>?) -> T? {
        guard let pointer else { return nil }
        defer { cfx_free(UnsafeMutablePointer(mutating: pointer)) }
        let text = String(cString: pointer)
        guard let data = text.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }
}

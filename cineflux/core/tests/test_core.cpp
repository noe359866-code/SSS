// Pruebas del núcleo compartido. Sin dependencias: se compilan con g++/clang++
// y se ejecutan desde `make -C core test`.
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <string>
#include <vector>

#include "cineflux/capi.h"
#include "cineflux/catalog.hpp"
#include "cineflux/errors.hpp"
#include "cineflux/ids.hpp"
#include "cineflux/json.hpp"
#include "cineflux/manifest.hpp"
#include "cineflux/profile.hpp"
#include "cineflux/skip.hpp"
#include "cineflux/stream.hpp"

namespace {

int g_checks = 0;
int g_failures = 0;

void check(bool condition, const std::string& what, int line) {
  ++g_checks;
  if (condition) return;
  ++g_failures;
  std::cout << "  ✗ " << what << "  (línea " << line << ")\n";
}

void check_eq(const std::string& actual, const std::string& expected, const std::string& what,
              int line) {
  ++g_checks;
  if (actual == expected) return;
  ++g_failures;
  std::cout << "  ✗ " << what << "\n      esperado: [" << expected << "]\n      obtenido: ["
            << actual << "]\n      línea " << line << "\n";
}

template <typename A, typename B>
void check_eq(A actual, B expected, const std::string& what, int line) {
  ++g_checks;
  if (static_cast<long long>(actual) == static_cast<long long>(expected)) return;
  ++g_failures;
  std::cout << "  ✗ " << what << " (esperado " << expected << ", obtenido " << actual
            << ", línea " << line << ")\n";
}

#define CHECK(cond) check((cond), #cond, __LINE__)
#define CHECK_EQ(actual, expected) check_eq((actual), (expected), #actual, __LINE__)
#define SECTION(name) std::cout << "\n" << name << "\n"

bool contains(const std::string& haystack, const std::string& needle) {
  return haystack.find(needle) != std::string::npos;
}

bool has_digit(const std::string& text) {
  for (char c : text) {
    if (c >= '0' && c <= '9') return true;
  }
  return false;
}

std::string capi_take(const char* ptr) {
  if (!ptr) return "";
  std::string out(ptr);
  cfx_free(const_cast<char*>(ptr));
  return out;
}

cfx::Json sample_manifest_json() {
  cfx::Json json;
  cfx::Json::parse(R"({
    "id": "org.cineflux.demo",
    "version": "1.2.0",
    "name": "Demo Streams",
    "description": "Plugin de ejemplo",
    "types": ["movie", "series"],
    "resources": [
      {"name": "stream", "types": ["movie", "series"], "idPrefixes": ["tt"]},
      "catalog"
    ],
    "catalogs": [{"type": "movie", "id": "top", "name": "Top"}],
    "transportUrl": "https://plugins.demo/manifest.json"
  })",
                   json);
  return json;
}

cfx::Json stream_json(const std::string& name, const std::string& title,
                      const std::string& url) {
  cfx::Json json = cfx::Json::object();
  json.set("name", name);
  json.set("title", title);
  json.set("url", url);
  return json;
}

cfx::StreamCandidate candidate(const std::string& label, const std::string& url) {
  cfx::Json json = stream_json(label, label, url);
  return cfx::normalize_stream(json, "demo", "Demo", 0);
}

void test_json() {
  SECTION("1 · JSON");
  cfx::Json json;
  std::string error;
  const bool ok = cfx::Json::parse(
      R"({"a":1,"b":[true,null,"ñandú \u00e9"],"c":{"d":2.5},"e":"l\"x\\y"})", json, &error);
  CHECK(ok);
  CHECK_EQ(json["a"].integer(), 1);
  CHECK_EQ(json["b"].size(), static_cast<std::size_t>(3));
  CHECK_EQ(json["b"][2].string(), std::string("ñandú é"));
  CHECK(json["b"][1].is_null());
  CHECK_EQ(json["c"]["d"].number(), 2.5);
  CHECK_EQ(json["e"].string(), std::string("l\"x\\y"));
  CHECK_EQ(json["missing"].string("fallback"), std::string("fallback"));
  CHECK(!json.has("missing"));

  cfx::Json round;
  CHECK(cfx::Json::parse(json.dump(), round, &error));
  CHECK_EQ(round.dump(), json.dump());
  CHECK(round.dump().find("\\u00f1") == std::string::npos);

  cfx::Json invalid;
  CHECK(!cfx::Json::parse("{\"a\": }", invalid, &error));
  CHECK(!cfx::Json::parse("{\"a\":1} basura", invalid, &error));
  CHECK(!cfx::Json::parse("[1,2", invalid, &error));
  CHECK(cfx::Json::parse(" [1,2] ", invalid, &error));
}

void test_urls() {
  SECTION("2 · URLs y manifest");
  CHECK_EQ(cfx::normalize_base_url("https://host.tld/manifest.json"), std::string("https://host.tld"));
  CHECK_EQ(cfx::normalize_base_url("https://host.tld/abc/manifest.json?x=1#y"),
           std::string("https://host.tld/abc"));
  CHECK_EQ(cfx::normalize_base_url("https://host.tld/base/"), std::string("https://host.tld/base"));
  CHECK_EQ(cfx::manifest_url_for("https://host.tld/base/"), std::string("https://host.tld/base/manifest.json"));
  CHECK_EQ(cfx::join_url("https://host.tld/base", "catalog/movie/top.json"),
           std::string("https://host.tld/base/catalog/movie/top.json"));
  CHECK_EQ(cfx::build_resource_url("https://h", "stream", "movie", "tt1"),
           std::string("https://h/stream/movie/tt1.json"));

  cfx::Manifest manifest;
  manifest.base_url = "https://h";
  cfx::CatalogDef catalog;
  catalog.type = "movie";
  catalog.id = "search";
  CHECK_EQ(cfx::catalog_url_for(manifest, catalog, "acción y ñ", "", 100),
           std::string("https://h/catalog/movie/search/search=acci%C3%B3n%20y%20%C3%B1&skip=100.json"));

  CHECK_EQ(cfx::url_encode("tt123:1:2"), std::string("tt123:1:2"));
  CHECK_EQ(cfx::url_encode("a b/c"), std::string("a%20b%2Fc"));
  CHECK(cfx::looks_like_imdb_id("tt0111161"));
  CHECK(!cfx::looks_like_imdb_id("tmdb:1"));
}

void test_manifest() {
  SECTION("3 · Parseo de manifests");
  const cfx::Json raw = sample_manifest_json();
  const cfx::ManifestResult result = cfx::parse_manifest(raw.dump());
  CHECK(result.ok);
  CHECK_EQ(result.manifest.id, std::string("org.cineflux.demo"));
  CHECK_EQ(result.manifest.name, std::string("Demo Streams"));
  CHECK_EQ(result.manifest.base_url, std::string("https://plugins.demo"));
  CHECK_EQ(result.manifest.types.size(), static_cast<std::size_t>(2));
  CHECK(result.manifest.has_resource("stream"));
  CHECK(result.manifest.has_resource("catalog"));
  CHECK_EQ(result.manifest.catalogs.size(), static_cast<std::size_t>(1));
  CHECK_EQ(result.manifest.catalogs[0].name, std::string("Top"));
  CHECK(result.manifest.can_stream("movie", "tt123"));
  CHECK(!result.manifest.can_stream("movie", "tmdb:1"));

  const cfx::Json normalized = result.manifest.to_json();
  CHECK(normalized["capabilities"]["streams"].boolean());
  CHECK_EQ(normalized["baseUrl"].string(), std::string("https://plugins.demo"));

  // Manifest en crudo sin URL de transporte: se acepta con aviso.
  cfx::Json raw_no_transport;
  cfx::Json::parse(R"({"id":"x","name":"X","resources":["stream"],"types":["movie"]})",
                   raw_no_transport);
  const cfx::ManifestResult no_transport = cfx::parse_manifest(raw_no_transport.dump());
  CHECK(no_transport.ok);
  CHECK(no_transport.manifest.base_url.empty());
  CHECK(!no_transport.warnings.empty());

  // Override de base (instalación desde URL).
  const cfx::ManifestResult override_result =
      cfx::parse_manifest(raw_no_transport.dump(), "https://cdn.example/plugin/manifest.json");
  CHECK_EQ(override_result.manifest.base_url, std::string("https://cdn.example/plugin"));

  // URL directa.
  const cfx::ManifestResult url_result = cfx::parse_manifest("https://addon.example/manifest.json");
  CHECK(url_result.ok);
  CHECK_EQ(url_result.manifest.base_url, std::string("https://addon.example"));
  CHECK(!url_result.warnings.empty());

  // Errores humanos, jamás códigos.
  const cfx::ManifestResult broken = cfx::parse_manifest("{ no json");
  CHECK(!broken.ok);
  CHECK(!contains(broken.message, "403"));
  CHECK(!contains(broken.message, "HTTP"));
  const cfx::ManifestResult missing_id = cfx::parse_manifest(R"({"name":"X","resources":["stream"]})");
  CHECK(!missing_id.ok);
  CHECK(contains(missing_id.message, "id"));
  CHECK(!has_digit(missing_id.message));

  // Catálogos con nombre inferido y extras.
  cfx::Json cat_manifest;
  cfx::Json::parse(R"({"id":"c","name":"C","resources":["catalog"],
    "catalogs":[{"type":"series","id":"genre:drama","extra":[{"name":"genre"},{"name":"skip"}]},
                {"type":"movie","id":"search","extra":["search"]}]})",
                   cat_manifest);
  const cfx::ManifestResult cat = cfx::parse_manifest(cat_manifest.dump(), "https://h/p");
  CHECK(cat.ok);
  CHECK_EQ(cat.manifest.catalogs.size(), static_cast<std::size_t>(2));
  CHECK(cat.manifest.catalogs[0].extra_genre);
  CHECK(cat.manifest.catalogs[1].extra_search);
  CHECK(contains(cat.manifest.catalogs[0].name, "Género"));
  CHECK_EQ(cat.manifest.types[0], std::string("series"));
}

void test_ids() {
  SECTION("4 · Identificadores");
  const std::vector<std::string> series =
      cfx::stream_id_candidates("series", "tt0903747", "1396", 2, 5);
  CHECK_EQ(series.size(), static_cast<std::size_t>(4));
  CHECK_EQ(series[0], std::string("tt0903747:2:5"));
  CHECK_EQ(series[1], std::string("tmdb:1396:2:5"));
  CHECK_EQ(series[2], std::string("tt0903747"));
  CHECK_EQ(series[3], std::string("tmdb:1396"));

  const std::vector<std::string> movie =
      cfx::stream_id_candidates("movie", "tt0133093", "603", 0, 0);
  CHECK_EQ(movie[0], std::string("tt0133093"));
  CHECK_EQ(movie[1], std::string("tmdb:603"));

  cfx::Manifest manifest;
  manifest.base_url = "https://h";
  cfx::Resource stream_resource;
  stream_resource.name = "stream";
  stream_resource.types = {"movie", "series"};
  stream_resource.id_prefixes = {"tt"};
  manifest.resources = {stream_resource};
  manifest.types = {"movie", "series"};

  const std::vector<std::string> only_imdb =
      cfx::stream_ids_for(manifest, "movie", "tt1", "550", 0, 0);
  CHECK_EQ(only_imdb.size(), static_cast<std::size_t>(1));
  CHECK_EQ(only_imdb[0], std::string("tt1"));
  CHECK_EQ(cfx::stream_url_for(manifest, "movie", "tt1"), std::string("https://h/stream/movie/tt1.json"));

  cfx::Manifest open_manifest = manifest;
  open_manifest.resources[0].id_prefixes.clear();
  const std::vector<std::string> any_id =
      cfx::stream_ids_for(open_manifest, "movie", "tt1", "550", 0, 0);
  CHECK_EQ(any_id.size(), static_cast<std::size_t>(2));
}

void test_detection() {
  SECTION("5 · Detección de calidad, idioma y pistas");
  CHECK_EQ(cfx::detect_quality("1080p WEB-DL"), 1080);
  CHECK_EQ(cfx::detect_quality("4K HDR10"), 2160);
  CHECK_EQ(cfx::detect_quality("HDTV 720p"), 720);
  CHECK_EQ(cfx::detect_quality("FullHD"), 1080);
  CHECK_EQ(cfx::detect_quality("BluRay 2160p REMUX"), 2160);
  CHECK_EQ(cfx::detect_quality("CAM"), 0);
  CHECK_EQ(cfx::quality_label(2160), std::string("4K"));
  CHECK_EQ(cfx::quality_label(1080), std::string("1080p"));
  CHECK_EQ(cfx::quality_label(0), std::string("Auto"));

  const std::vector<std::string> latino = cfx::detect_languages("Latino Dual 1080p");
  CHECK_EQ(latino.size(), static_cast<std::size_t>(2));
  CHECK_EQ(latino[0], std::string("latino"));
  CHECK_EQ(latino[1], std::string("dual"));

  const std::vector<std::string> castellano = cfx::detect_languages("Castellano 720p");
  CHECK_EQ(castellano[0], std::string("castellano"));

  const std::vector<std::string> subs = cfx::detect_languages("Subtitulado VOSE 1080p");
  CHECK_EQ(subs[0], std::string("sub"));

  CHECK_EQ(cfx::detect_languages("Inglés original").size(), static_cast<std::size_t>(1));
  CHECK_EQ(cfx::language_label({"latino", "sub"}), std::string("Latino"));

  CHECK_EQ(cfx::parse_size_bytes("1.4 GB"), 1400000000LL);
  CHECK_EQ(cfx::parse_size_bytes("700MB"), 700000000LL);
  CHECK_EQ(cfx::parse_size_bytes("2,5 GB"), 2500000000LL);

  CHECK(cfx::looks_like_direct_media("https://cdn/x.m3u8?token=1"));
  CHECK(cfx::looks_like_direct_media("https://cdn/x.mp4"));
  CHECK(!cfx::looks_like_direct_media("https://web/ver/123"));
  CHECK(cfx::looks_like_page("https://web/ver/123"));
  CHECK(cfx::is_torrent_stream("magnet:?xt=urn:btih:abc", ""));
  CHECK(cfx::is_torrent_stream("", "abcdef0123456789"));

  const cfx::StreamCandidate cam = candidate("Cam Latino 1080p", "https://cdn/cam.mp4");
  CHECK(cam.cam);
  const cfx::StreamCandidate hdr = candidate("4K HDR REMUX HEVC", "https://cdn/a.mp4");
  CHECK_EQ(hdr.quality, 2160);
  CHECK(hdr.hdr);
  CHECK(hdr.remux);
  CHECK(hdr.hevc);
  CHECK(!hdr.cam);
}

void test_normalize_and_dedupe() {
  SECTION("6 · Normalización y duplicados");
  cfx::Json payload;
  cfx::Json::parse(R"({"streams":[
    {"url":"https://cdn/a.m3u8","name":"Latino 1080p","title":"Película 1.8 GB",
     "behaviorHints":{"filename":"Peli.1080p.Latino.mkv"},
     "subtitles":[{"url":"https://subs/es.srt","lang":"es","label":"Español"}]},
    {"infoHash":"ABCDEF","name":"Torrent 4K"},
    {"url":"https://web/ver/1","name":"Reproductor externo"},
    {}
  ]})",
                   payload);
  const std::vector<cfx::StreamCandidate> streams =
      cfx::streams_from_response(payload, "a", "Addon A", 1);
  CHECK_EQ(streams.size(), static_cast<std::size_t>(3));
  CHECK_EQ(streams[0].quality, 1080);
  CHECK_EQ(streams[0].languages[0], std::string("latino"));
  CHECK_EQ(streams[0].size_bytes, 1800000000LL);
  CHECK_EQ(streams[0].subtitles.size(), static_cast<std::size_t>(1));
  CHECK(streams[1].torrent);
  CHECK(!streams[1].info_hash.empty());
  CHECK_EQ(streams[2].external_url, std::string(""));

  // Duplicados por URL: conserva el de mejor calidad.
  cfx::StreamCandidate low = candidate("Latino 720p", "https://cdn/same.mp4");
  cfx::StreamCandidate high = candidate("Latino 1080p HDR", "https://cdn/same.mp4");
  const std::vector<cfx::StreamCandidate> unique = cfx::dedupe_streams({low, high});
  CHECK_EQ(unique.size(), static_cast<std::size_t>(1));
  CHECK_EQ(unique[0].quality, 1080);

  // Duplicados por torrent + calidad.
  cfx::Json t1 = stream_json("Torrent", "1080p", "");
  t1.set("infoHash", "HASH");
  cfx::Json t2 = t1;
  t2.set("title", "1080p HDR");
  auto one = cfx::normalize_stream(t1, "a", "A", 0);
  auto two = cfx::normalize_stream(t2, "b", "B", 0);
  CHECK_EQ(cfx::dedupe_streams({one, two}).size(), static_cast<std::size_t>(1));
}

void test_ranking() {
  SECTION("7 · Selección automática por perfil");
  std::vector<cfx::PlaybackProfile> profiles = cfx::default_profiles();
  CHECK_EQ(profiles.size(), static_cast<std::size_t>(6));

  cfx::PlaybackProfile latino;
  for (const auto& profile : profiles) {
    if (profile.id == "latino-1080") latino = profile;
    CHECK(cfx::validate_profile(profile).ok);
  }
  CHECK_EQ(latino.languages[0], std::string("latino"));

  std::vector<cfx::StreamCandidate> streams = {
      candidate("Castellano 1080p", "https://cdn/castellano.mp4"),
      candidate("Latino 1080p", "https://cdn/latino1080.mp4"),
      candidate("Latino 720p", "https://cdn/latino720.mp4"),
      candidate("Cam Latino 1080p", "https://cdn/cam.mp4"),
  };
  // Stream de torrent: debe quedar fuera del perfil (torrents desactivados).
  cfx::Json torrent_json = stream_json("Latino 2160p", "Torrent 4K", "");
  torrent_json.set("infoHash", "ABCDEF0123456789");
  streams.push_back(cfx::normalize_stream(torrent_json, "t", "Torrents", 0));

  const cfx::RankingResult ranking = cfx::rank_streams(streams, latino);
  CHECK(ranking.ok);
  CHECK_EQ(ranking.candidates[0].url, std::string("https://cdn/latino1080.mp4"));
  CHECK_EQ(ranking.candidates[0].role, std::string("primary"));
  CHECK_EQ(ranking.candidates[1].role, std::string("backup"));
  CHECK_EQ(ranking.candidates[0].quality, 1080);
  CHECK(ranking.rejected >= 2);  // cam + torrent
  for (const auto& item : ranking.candidates) {
    CHECK(!item.cam);
    CHECK(!item.torrent);
  }

  // 4K: gana el 2160 aunque también haya 1080.
  cfx::PlaybackProfile uhd;
  for (const auto& profile : profiles) {
    if (profile.id == "latino-4k") uhd = profile;
  }
  std::vector<cfx::StreamCandidate> uhd_streams = {
      candidate("Latino 1080p", "https://cdn/a.mp4"),
      candidate("Latino 2160p HDR", "https://cdn/b.mp4"),
  };
  const cfx::RankingResult uhd_ranking = cfx::rank_streams(uhd_streams, uhd);
  CHECK_EQ(uhd_ranking.candidates[0].quality, 2160);

  // Móvil: prefiere 720 y evita archivos enormes.
  cfx::PlaybackProfile movil;
  for (const auto& profile : profiles) {
    if (profile.id == "movil") movil = profile;
  }
  cfx::StreamCandidate big720 = candidate("Latino 720p", "https://cdn/720.mp4");
  big720.size_bytes = 900LL * 1000 * 1000;
  cfx::StreamCandidate big1080 = candidate("Latino 1080p", "https://cdn/1080.mp4");
  big1080.size_bytes = 9LL * 1000 * 1000 * 1000;
  const cfx::RankingResult movil_ranking = cfx::rank_streams({big1080, big720}, movil);
  CHECK_EQ(movil_ranking.candidates[0].quality, 720);

  // Subtitulado: prefiere VO + subtítulos frente a latino.
  cfx::PlaybackProfile subs;
  for (const auto& profile : profiles) {
    if (profile.id == "subtitulado") subs = profile;
  }
  const cfx::RankingResult subs_ranking =
      cfx::rank_streams({candidate("Latino 2160p", "https://cdn/lat.mp4"),
                         candidate("English 1080p Subtitulado", "https://cdn/en.mp4")},
                        subs);
  CHECK(contains(subs_ranking.candidates[0].url, "en.mp4"));

  // Idioma estricto: descarta lo que no coincide.
  cfx::PlaybackProfile strict = latino;
  strict.strict_language = true;
  const cfx::RankingResult strict_ranking =
      cfx::rank_streams({candidate("Castellano 1080p", "https://cdn/c.mp4")}, strict);
  CHECK(!strict_ranking.ok);

  // Sin opciones: mensaje humano y sin códigos.
  const cfx::RankingResult empty = cfx::rank_streams({}, latino);
  CHECK(!empty.ok);
  CHECK(!empty.message.empty());
  CHECK(!has_digit(empty.message));
  CHECK(!contains(empty.message, "HTTP"));

  // Si el techo deja la lista vacía, se relaja en lugar de fallar.
  const cfx::RankingResult relaxed =
      cfx::rank_streams({candidate("Latino 2160p", "https://cdn/4k.mp4")}, latino);
  CHECK(relaxed.ok);
  CHECK_EQ(relaxed.candidates[0].quality, 2160);
  CHECK(contains(relaxed.candidates[0].rationale, "fuera-de-perfil"));

  // Preferencia de plugins: mismo stream, prioridad menor gana.
  cfx::StreamCandidate p0 = candidate("Latino 1080p", "https://cdn/first.mp4");
  cfx::StreamCandidate p2 = candidate("Latino 1080p", "https://cdn/second.mp4");
  p0.addon_id = "first";
  p0.addon_priority = 0;
  p2.addon_id = "second";
  p2.addon_priority = 2;
  const cfx::RankingResult priority_ranking = cfx::rank_streams({p2, p0}, latino);
  CHECK_EQ(priority_ranking.candidates[0].addon_id, std::string("first"));
}

void test_messages() {
  SECTION("8 · Mensajes sin códigos técnicos");
  const cfx::Failure all[] = {
      cfx::Failure::Network,       cfx::Failure::Timeout,  cfx::Failure::Manifest,
      cfx::Failure::NoStreams,     cfx::Failure::Playback, cfx::Failure::PlaybackExhausted,
      cfx::Failure::NotFound,      cfx::Failure::Unsupported,
  };
  for (auto failure : all) {
    const std::string message = cfx::user_message(failure);
    CHECK(!message.empty());
    CHECK(!has_digit(message));
    CHECK(!contains(message, "HTTP"));
    CHECK(!contains(message, "http"));
    CHECK(!contains(message, "403"));
    CHECK(!contains(message, "error"));
  }
  const std::string dirty = "fallo HTTP 403 en https://x.y/z CORS";
  const std::string clean = cfx::scrub_user_text(dirty);
  CHECK(!contains(clean, "403"));
  CHECK(!contains(clean, "HTTP"));
  CHECK(!contains(clean, "http"));
  CHECK(!contains(clean, "CORS"));
  CHECK(!clean.empty());
}

void test_c_api() {
  SECTION("9 · API C (Android/iOS)");
  CHECK_EQ(std::string(cfx_version()), std::string("1.0.0"));

  const std::string profiles_json = capi_take(cfx_default_profiles());
  cfx::Json profiles;
  CHECK(cfx::Json::parse(profiles_json, profiles));
  CHECK(profiles["ok"].boolean());
  CHECK_EQ(profiles["profiles"].size(), static_cast<std::size_t>(6));

  const cfx::Json manifest = sample_manifest_json();
  const std::string manifest_out = capi_take(cfx_parse_manifest(manifest.dump().c_str(), ""));
  cfx::Json parsed;
  CHECK(cfx::Json::parse(manifest_out, parsed));
  CHECK(parsed["ok"].boolean());
  CHECK_EQ(parsed["manifest"]["baseUrl"].string(), std::string("https://plugins.demo"));

  // Plan de peticiones: serie con temporada/episodio.
  cfx::Json plan_request;
  cfx::Json::parse(R"({"type":"series","imdbId":"tt0903747","tmdbId":"1396",
      "season":3,"episode":7,"addons":[{"id":"demo","manifest":{}}]})",
                   plan_request);
  plan_request.at_mut("addons").at_mut(0).set("manifest", manifest);
  const std::string plan_out = capi_take(cfx_plan_requests(plan_request.dump().c_str()));
  cfx::Json plan;
  CHECK(cfx::Json::parse(plan_out, plan));
  CHECK(plan["ok"].boolean());
  CHECK_EQ(plan["addons"].size(), static_cast<std::size_t>(1));
  CHECK_EQ(plan["addons"][0]["ids"][0].string(), std::string("tt0903747:3:7"));
  CHECK_EQ(plan["addons"][0]["urls"][0].string(),
           std::string("https://plugins.demo/stream/series/tt0903747:3:7.json"));

  // Ranking de extremo a extremo.
  cfx::Json rank_request;
  rank_request.set("profile", cfx::profile_to_json(cfx::default_profiles()[0]));
  cfx::Json addon = cfx::Json::object();
  addon.set("id", "demo");
  addon.set("name", "Demo");
  addon.set("priority", 0);
  cfx::Json payload;
  cfx::Json::parse(R"({"streams":[
     {"name":"Castellano 1080p","url":"https://cdn/c.mp4"},
     {"name":"Latino 1080p","url":"https://cdn/l.mp4"},
     {"name":"Latino 2160p","url":"https://cdn/4k.mp4"}]})",
                   payload);
  addon.set("payload", payload);
  cfx::Json addons = cfx::Json::array();
  addons.push(addon);
  rank_request.set("addons", addons);
  cfx::Json options = cfx::Json::object();
  options.set("limit", 5);
  rank_request.set("options", options);

  const std::string rank_out = capi_take(cfx_rank_streams(rank_request.dump().c_str()));
  cfx::Json ranking;
  CHECK(cfx::Json::parse(rank_out, ranking));
  CHECK(ranking["ok"].boolean());
  CHECK_EQ(ranking["candidates"].size(), static_cast<std::size_t>(2));
  CHECK_EQ(ranking["candidates"][0]["url"].string(), std::string("https://cdn/l.mp4"));
  CHECK_EQ(ranking["candidates"][0]["role"].string(), std::string("primary"));
  CHECK_EQ(ranking["candidates"][0]["qualityLabel"].string(), std::string("1080p"));
  CHECK(!has_digit(ranking["message"].string()));
}

}  // namespace

void test_skip_segments() {
  using cfx::Json;
  SECTION("12 · Saltos de intro, recap y créditos");

  Json out;
  {
    const char* request = R"({
      "durationMs": 1400000,
      "sources": [
        {"source":"introdb","payload":{
          "intro":{"start_ms":1000,"end_ms":61000,"confidence":0.92},
          "recap":null,
          "outro":{"start_ms":1300000,"end_ms":1350000}}},
        {"source":"theintrodb","payload":{
          "intro":[{"start_ms":2000,"end_ms":62000}],
          "credits":[{"start_ms":1390000,"end_ms":1600000}]}}
      ]})";
    const char* raw = cfx_skip_segments(request);
    CHECK(Json::parse(raw, out));
    cfx_free(const_cast<char*>(raw));
    CHECK(out["ok"].boolean());
    CHECK_EQ(out["count"].integer(), 2);
    CHECK_EQ(out["segments"][0]["kind"].string(), std::string("intro"));
    CHECK_EQ(out["segments"][0]["startMs"].integer(), 2000);
    CHECK_EQ(out["segments"][0]["source"].string(), std::string("theintrodb"));
    CHECK_EQ(out["segments"][1]["kind"].string(), std::string("credits"));
    CHECK_EQ(out["segments"][1]["endMs"].integer(), 1400000);
    CHECK_EQ(out["segments"][0]["label"].string(), std::string("Saltar intro"));
  }

  {
    const char* request = R"({
      "durationMs": 1400000,
      "priority": ["introdb"],
      "sources": [
        {"source":"introdb","payload":{"intro":{"start_ms":1000,"end_ms":61000}}},
        {"source":"theintrodb","payload":{"intro":[{"start_ms":2000,"end_ms":62000}]}}
      ]})";
    const char* raw = cfx_skip_segments(request);
    CHECK(Json::parse(raw, out));
    cfx_free(const_cast<char*>(raw));
    CHECK_EQ(out["segments"][0]["startMs"].integer(), 1000);
    CHECK_EQ(out["segments"][0]["source"].string(), std::string("introdb"));
  }

  {
    const char* request = R"({
      "durationMs": 1400000,
      "sources": [{"source":"introdb","payload":{
        "intro":{"start_ms":1000,"end_ms":4000},
        "recap":{"start_ms":9000,"end_ms":5000},
        "post_credits":{"start_ms":1300000,"end_ms":1350000}}}]})";
    const char* raw = cfx_skip_segments(request);
    CHECK(Json::parse(raw, out));
    cfx_free(const_cast<char*>(raw));
    CHECK_EQ(out["count"].integer(), 1);
    CHECK_EQ(out["segments"][0]["kind"].string(), std::string("credits"));
  }

  {
    const char* request = R"({
      "durationMs": 1500000,
      "sources": [{"source":"aniskip","payload":{"found":true,"results":[
        {"interval":{"startTime":12,"endTime":102},"skipType":"op"},
        {"interval":{"startTime":5,"endTime":9},"skipType":"recap"},
        {"interval":{"startTime":1300,"endTime":1380},"skipType":"ed"},
        {"interval":{"startTime":400,"endTime":430},"skipType":"mixed-op"}]}}]})";
    const char* raw = cfx_skip_segments(request);
    CHECK(Json::parse(raw, out));
    cfx_free(const_cast<char*>(raw));
    CHECK_EQ(out["count"].integer(), 2);
    CHECK_EQ(out["segments"][0]["startMs"].integer(), 12000);
    CHECK_EQ(out["segments"][0]["source"].string(), std::string("aniskip"));
    CHECK_EQ(out["segments"][1]["kind"].string(), std::string("credits"));
  }

  {
    const char* request = R"({
      "durationMs": 600000,
      "sources": [{"source":"animeskip","payload":{"timestamps":[
        {"at":10,"type":{"name":"Intro"}},
        {"at":90,"type":{"name":"Credits"}}]}}]})";
    const char* raw = cfx_skip_segments(request);
    CHECK(Json::parse(raw, out));
    cfx_free(const_cast<char*>(raw));
    CHECK_EQ(out["count"].integer(), 2);
    CHECK_EQ(out["segments"][0]["startMs"].integer(), 10000);
    CHECK_EQ(out["segments"][0]["endMs"].integer(), 90000);
    CHECK_EQ(out["segments"][1]["endMs"].integer(), 600000);
  }

  {
    const char* request = R"({"sources":[]})";
    const char* raw = cfx_skip_segments(request);
    CHECK(Json::parse(raw, out));
    cfx_free(const_cast<char*>(raw));
    CHECK(!out["ok"].boolean());
  }

  {
    const char* request = R"({"durationMs":900000,"sources":[
      {"source":"desconocido","payload":{"intro":{"start_ms":0,"end_ms":30000}}},
      {"source":"introdb","payload":"no-json"}]})";
    const char* raw = cfx_skip_segments(request);
    CHECK(Json::parse(raw, out));
    cfx_free(const_cast<char*>(raw));
    CHECK(out["ok"].boolean());
    CHECK_EQ(out["count"].integer(), 0);
  }
}


void test_catalog_plan() {
  using cfx::Json;
  SECTION("13 · Catálogos de addons (Nuvio · Cinemeta)");

  const char* nuvio = R"({
    "id":"com.nuvio.tmdb.catalogs","version":"1.2.0","name":"Nuvio Catalog Addon",
    "resources":["catalog",{"name":"meta","types":["movie","series"],"idPrefixes":["tt","tmdb:"]}],
    "types":["movie","series"],
    "catalogs":[
      {"type":"movie","id":"trending-movies","name":"Trending Movies","extra":[{"name":"skip","isRequired":false}]},
      {"type":"movie","id":"popular-movies","name":"Popular Movies","extra":[{"name":"search","isRequired":false},{"name":"genre","isRequired":false}]},
      {"type":"series","id":"year","name":"New","extra":[{"name":"genre","isRequired":true},{"name":"skip"}]}
    ]})";

  cfx::ManifestResult parsed =
      cfx::parse_manifest(nuvio, "https://catalog.nuvio.tv/manifest.json");
  CHECK(parsed.ok);
  CHECK_EQ(parsed.manifest.catalogs.size(), static_cast<std::size_t>(3));
  CHECK(parsed.manifest.has_resource("catalog"));
  CHECK(parsed.manifest.has_resource("meta"));
  CHECK_EQ(parsed.manifest.catalogs[1].id, std::string("popular-movies"));
  CHECK(parsed.manifest.catalogs[1].extra_search);
  CHECK_EQ(parsed.manifest.catalogs[2].required_extras.size(), static_cast<std::size_t>(1));
  CHECK_EQ(parsed.manifest.catalogs[2].required_extras[0], std::string("genre"));
  CHECK_EQ(parsed.manifest.to_json()["catalogs"][2]["required"][0].string(),
           std::string("genre"));

  const char* cinemeta = R"({
    "id":"com.linvo.cinemeta","version":"3.0.14","name":"Cinemeta",
    "resources":["catalog","meta"],"types":["movie","series"],"idPrefixes":["tt"],
    "catalogs":[
      {"type":"movie","id":"top","name":"Popular","extra":[{"name":"search"},{"name":"genre"},{"name":"skip"}]},
      {"type":"series","id":"top","name":"Popular","extra":[{"name":"search"},{"name":"genre"},{"name":"skip"}]},
      {"type":"series","id":"calendar-videos","name":"Calendar","extra":[{"name":"calendarVideosIds","isRequired":true}]}
    ]})";
  cfx::ManifestResult cinemeta_parsed =
      cfx::parse_manifest(cinemeta, "https://v3-cinemeta.strem.io/manifest.json");
  CHECK(cinemeta_parsed.ok);

  Json request = Json::object();
  Json types = Json::array();
  types.push(Json("movie"));
  types.push(Json("series"));
  request.set("types", types);
  request.set("limit", 10);
  request.set("perSource", 6);

  Json sources = Json::array();
  auto source = [](const std::string& base, const cfx::Manifest& manifest) {
    Json item = Json::object();
    item.set("baseUrl", base);
    item.set("manifest", manifest.to_json());
    return item;
  };
  sources.push(source("https://catalog.nuvio.tv", parsed.manifest));
  sources.push(source("https://v3-cinemeta.strem.io", cinemeta_parsed.manifest));
  request.set("sources", sources);

  const char* raw = cfx_plan_catalog(request.dump().c_str());
  Json plan;
  CHECK(Json::parse(raw, plan));
  cfx_free(const_cast<char*>(raw));
  CHECK(plan["ok"].boolean());
  CHECK_EQ(plan["count"].integer(), 4);
  CHECK_EQ(plan["rows"][0]["catalogId"].string(), std::string("trending-movies"));
  CHECK_EQ(plan["rows"][0]["url"].string(),
           std::string("https://catalog.nuvio.tv/catalog/movie/trending-movies.json"));
  CHECK_EQ(plan["rows"][2]["addonId"].string(), std::string("com.linvo.cinemeta"));
  CHECK_EQ(plan["rows"][2]["url"].string(),
           std::string("https://v3-cinemeta.strem.io/catalog/movie/top.json"));

  request.set("query", "the matrix");
  raw = cfx_plan_catalog(request.dump().c_str());
  Json search;
  CHECK(Json::parse(raw, search));
  cfx_free(const_cast<char*>(raw));
  CHECK(search["ok"].boolean());
  CHECK_EQ(search["count"].integer(), 3);
  CHECK_EQ(search["rows"][0]["url"].string(),
           std::string("https://catalog.nuvio.tv/catalog/movie/popular-movies/search=the%20matrix.json"));
  CHECK(search["rows"][0]["search"].boolean());

  Json empty = Json::object();
  raw = cfx_plan_catalog(empty.dump().c_str());
  Json none;
  CHECK(Json::parse(raw, none));
  cfx_free(const_cast<char*>(raw));
  CHECK(!none["ok"].boolean());
  CHECK(!none["message"].string().empty());
}

void test_js_plugins() {
  using cfx::Json;
  SECTION("14 · Plugins JS locales (Nuvio)");

  const char* repo = R"({
    "name": "Nuvio Providers",
    "version": "2.1.0",
    "scrapers": [
      {
        "id": "showbox",
        "name": "ShowBox",
        "description": "Streams HD",
        "version": "1.2.0",
        "enabled": true,
        "supportedTypes": ["movie", "tv"],
        "filename": "providers/showbox.js"
      },
      {
        "name": "Direct One",
        "url": "https://cdn.example.com/direct.js",
        "supportedTypes": ["movie"]
      }
    ]
  })";

  const char* raw_repo = cfx_parse_js_repo(repo, "https://raw.example.com/nuvio/manifest.json");
  Json parsed_repo;
  CHECK(Json::parse(raw_repo, parsed_repo));
  cfx_free(const_cast<char*>(raw_repo));

  CHECK(parsed_repo["ok"].boolean());
  CHECK_EQ(parsed_repo["name"].string(), std::string("Nuvio Providers"));
  CHECK_EQ(parsed_repo["plugins"].items().size(), static_cast<std::size_t>(2));
  CHECK_EQ(parsed_repo["plugins"][0]["id"].string(), std::string("showbox"));
  CHECK_EQ(parsed_repo["plugins"][0]["scriptUrl"].string(),
           std::string("https://raw.example.com/nuvio/providers/showbox.js"));
  CHECK_EQ(parsed_repo["plugins"][0]["types"].items().size(), static_cast<std::size_t>(2));
  CHECK_EQ(parsed_repo["plugins"][0]["types"][1].string(), std::string("series"));
  CHECK_EQ(parsed_repo["plugins"][1]["id"].string(), std::string("direct-one"));
  CHECK_EQ(parsed_repo["plugins"][1]["scriptUrl"].string(),
           std::string("https://cdn.example.com/direct.js"));

  // Normalización de streams devueltos por getStreams()
  const char* js_output = R"([
    {
      "name": "ShowBox",
      "title": "Dune.2024.1080p.WEB-DL.mkv",
      "url": "https://stream.example.com/master.m3u8",
      "quality": "1080p",
      "size": "2.4 GB",
      "headers": {"Referer": "https://ref.example.com/", "User-Agent": "Cineflux/1.0"},
      "subtitles": [{"url": "https://sub.example.com/es.vtt", "lang": "es"}]
    },
    {
      "title": "Sin URL"
    }
  ])";
  const char* raw_norm = cfx_normalize_js_streams(js_output, "ShowBox");
  Json norm;
  CHECK(Json::parse(raw_norm, norm));
  cfx_free(const_cast<char*>(raw_norm));

  CHECK_EQ(norm["streams"].items().size(), static_cast<std::size_t>(1));
  CHECK_EQ(norm["streams"][0]["url"].string(),
           std::string("https://stream.example.com/master.m3u8"));
  CHECK_EQ(norm["streams"][0]["name"].string(), std::string("ShowBox · 1080p"));
  CHECK_EQ(norm["streams"][0]["behaviorHints"]["proxyHeaders"]["request"]["Referer"].string(),
           std::string("https://ref.example.com/"));
  CHECK_EQ(norm["streams"][0]["subtitles"].items().size(), static_cast<std::size_t>(1));

  // Repositorio inválido
  const char* bad_repo = cfx_parse_js_repo(R"({"name":"Vacío"})", "");
  Json bad;
  CHECK(Json::parse(bad_repo, bad));
  cfx_free(const_cast<char*>(bad_repo));
  CHECK(!bad["ok"].boolean());
}

int main() {
  std::cout << "Cineflux · pruebas del núcleo C++\n================================";
  test_json();
  test_urls();
  test_manifest();
  test_ids();
  test_detection();
  test_normalize_and_dedupe();
  test_ranking();
  test_messages();
  test_c_api();
  test_skip_segments();
  test_catalog_plan();
  test_js_plugins();

  std::cout << "\n================================\n";
  std::cout << g_checks - g_failures << "/" << g_checks << " comprobaciones correctas\n";
  if (g_failures > 0) {
    std::cout << g_failures << " fallo(s)\n";
    return 1;
  }
  std::cout << "Todo correcto.\n";
  return 0;
}

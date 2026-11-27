#include "cineflux/capi.h"

#include "cineflux/catalog.hpp"

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <iterator>
#include <string>
#include <vector>

#include "cineflux/errors.hpp"
#include "cineflux/ids.hpp"
#include "cineflux/js_plugin.hpp"
#include "cineflux/json.hpp"
#include "cineflux/manifest.hpp"
#include "cineflux/profile.hpp"
#include "cineflux/skip.hpp"
#include "cineflux/stream.hpp"

#ifndef CINEFLUX_VERSION
#define CINEFLUX_VERSION "1.0.0"
#endif

namespace {

char* to_c_string(const std::string& text) {
  char* buffer = static_cast<char*>(std::malloc(text.size() + 1));
  if (!buffer) return nullptr;
  std::memcpy(buffer, text.data(), text.size());
  buffer[text.size()] = '\0';
  return buffer;
}

char* error_json(cfx::Failure failure) {
  cfx::Json out = cfx::Json::object();
  out.set("ok", false);
  out.set("message", cfx::user_message(failure));
  return to_c_string(out.dump());
}

char* raw_error(const std::string& message) {
  cfx::Json out = cfx::Json::object();
  out.set("ok", false);
  out.set("message", cfx::scrub_user_text(message));
  return to_c_string(out.dump());
}

cfx::Json subtitle_json(const cfx::Subtitle& subtitle) {
  cfx::Json item = cfx::Json::object();
  item.set("url", subtitle.url);
  item.set("lang", subtitle.language);
  item.set("label", subtitle.label);
  return item;
}

cfx::Json stream_json(const cfx::StreamCandidate& stream, std::size_t index) {
  cfx::Json item = cfx::Json::object();
  item.set("addonId", stream.addon_id);
  item.set("addonName", stream.addon_name);
  item.set("url", stream.url);
  item.set("externalUrl", stream.external_url);
  item.set("quality", stream.quality);
  item.set("qualityLabel", stream.quality_label);
  item.set("languageLabel", stream.language_label);
  item.set("title", cfx::safe_label(stream.title));
  item.set("hdr", stream.hdr);
  item.set("cam", stream.cam);
  item.set("torrent", stream.torrent);
  item.set("hevc", stream.hevc);
  item.set("sizeBytes", static_cast<double>(stream.size_bytes));
  item.set("score", stream.score);
  item.set("role", index == 0 ? "primary" : "backup");

  cfx::Json languages = cfx::Json::array();
  for (const auto& language : stream.languages) languages.push(cfx::Json(language));
  item.set("languages", std::move(languages));

  cfx::Json subtitles = cfx::Json::array();
  for (const auto& subtitle : stream.subtitles) subtitles.push(subtitle_json(subtitle));
  item.set("subtitles", std::move(subtitles));
  return item;
}

cfx::Json parse_request(const char* request_json) {
  cfx::Json request;
  if (!request_json) return request;
  cfx::Json::parse(request_json, request);
  return request;
}

}  // namespace

extern "C" {

const char* cfx_version(void) { return CINEFLUX_VERSION; }

const char* cfx_default_profiles(void) {
  cfx::Json out = cfx::Json::object();
  out.set("ok", true);
  cfx::Json profiles = cfx::Json::array();
  for (const auto& profile : cfx::default_profiles()) {
    profiles.push(cfx::profile_to_json(profile));
  }
  out.set("profiles", std::move(profiles));
  return to_c_string(out.dump());
}

const char* cfx_parse_manifest(const char* manifest_json_or_url,
                               const char* base_url_override) {
  if (!manifest_json_or_url) return error_json(cfx::Failure::Manifest);
  const cfx::ManifestResult result =
      cfx::parse_manifest(manifest_json_or_url, base_url_override ? base_url_override : "");

  cfx::Json out = cfx::Json::object();
  out.set("ok", result.ok);
  if (!result.ok) {
    out.set("message", cfx::scrub_user_text(result.message));
    return to_c_string(out.dump());
  }
  out.set("manifest", result.manifest.to_json());
  cfx::Json warnings = cfx::Json::array();
  for (const auto& warning : result.warnings) {
    warnings.push(cfx::Json(cfx::scrub_user_text(warning)));
  }
  out.set("warnings", std::move(warnings));
  return to_c_string(out.dump());
}

const char* cfx_plan_requests(const char* request_json) {
  const cfx::Json request = parse_request(request_json);
  if (!request.is_object()) return error_json(cfx::Failure::Network);

  const std::string type = request["type"].string("movie");
  const std::string imdb_id = request["imdbId"].string();
  const std::string tmdb_id = request["tmdbId"].string();
  const int season = request["season"].integer(0);
  const int episode = request["episode"].integer(0);
  if (imdb_id.empty() && tmdb_id.empty()) {
    return raw_error("Este título no tiene identificador para buscar opciones.");
  }

  cfx::Json out = cfx::Json::object();
  cfx::Json addons_json = cfx::Json::array();
  const cfx::Json& addons = request["addons"];
  for (const auto& entry : addons.items()) {
    if (!entry.is_object()) continue;
    const std::string addon_id = entry["id"].string();
    if (addon_id.empty()) continue;

    std::string manifest_raw = entry["manifest"].dump();
    if (entry["manifest"].is_string()) manifest_raw = entry["manifest"].string();
    const cfx::ManifestResult parsed =
        cfx::parse_manifest(manifest_raw, entry["baseUrl"].string());
    if (!parsed.ok) continue;

    const std::vector<std::string> ids = cfx::stream_ids_for(
        parsed.manifest, type, imdb_id, tmdb_id, season, episode);
    if (ids.empty()) continue;

    cfx::Json item = cfx::Json::object();
    item.set("id", addon_id);
    item.set("name", parsed.manifest.name);
    cfx::Json ids_json = cfx::Json::array();
    cfx::Json urls_json = cfx::Json::array();
    for (const auto& id : ids) {
      ids_json.push(cfx::Json(id));
      urls_json.push(cfx::Json(cfx::stream_url_for(parsed.manifest, type, id)));
    }
    item.set("ids", std::move(ids_json));
    item.set("urls", std::move(urls_json));
    addons_json.push(std::move(item));
  }

  out.set("ok", true);
  out.set("addons", std::move(addons_json));
  return to_c_string(out.dump());
}

const char* cfx_rank_streams(const char* request_json) {
  const cfx::Json request = parse_request(request_json);
  if (!request.is_object()) return error_json(cfx::Failure::NoStreams);

  const cfx::PlaybackProfile profile = cfx::profile_from_json(request["profile"]);
  cfx::RankingOptions options;
  const cfx::Json& opts = request["options"];
  options.allow_torrents = opts["allowTorrents"].boolean(false);
  options.limit = static_cast<std::size_t>(std::max(1, opts["limit"].integer(12)));
  options.include_backups = opts["includeBackups"].boolean(true);

  std::vector<cfx::StreamCandidate> streams;
  const cfx::Json& addons = request["addons"];
  for (const auto& entry : addons.items()) {
    if (!entry.is_object()) continue;
    const std::string addon_id = entry["id"].string();
    const std::string addon_name = entry["name"].string(addon_id);
    const int priority = entry["priority"].integer(0);
    if (addon_id.empty()) continue;

    cfx::Json payload = entry["payload"];
    if (payload.is_string()) {
      cfx::Json parsed;
      if (cfx::Json::parse(payload.string(), parsed)) payload = std::move(parsed);
    }
    if (!payload.is_object()) continue;

    std::vector<cfx::StreamCandidate> batch =
        cfx::streams_from_response(payload, addon_id, addon_name, priority);
    streams.insert(streams.end(), std::make_move_iterator(batch.begin()),
                   std::make_move_iterator(batch.end()));
  }

  cfx::RankingResult ranking = cfx::rank_streams(std::move(streams), profile, options);

  cfx::Json out = cfx::Json::object();
  out.set("ok", ranking.ok);
  out.set("received", static_cast<double>(ranking.received));
  out.set("playable", static_cast<double>(ranking.playable));
  out.set("rejected", static_cast<double>(ranking.rejected));
  out.set("message", cfx::user_message(ranking.failure));
  cfx::Json candidates = cfx::Json::array();
  for (std::size_t i = 0; i < ranking.candidates.size(); ++i) {
    candidates.push(stream_json(ranking.candidates[i], i));
  }
  out.set("candidates", std::move(candidates));
  return to_c_string(out.dump());
}

const char* cfx_skip_segments(const char* request_json) {
  const cfx::Json request = parse_request(request_json);
  if (!request.is_object()) {
    return raw_error("No hay datos de saltos disponibles.");
  }
  const cfx::SkipResult result = cfx::skip_segments_from_request(request);
  return to_c_string(cfx::skip_segments_to_json(result).dump());
}

const char* cfx_plan_catalog(const char* request_json) {
  const cfx::Json request = parse_request(request_json);
  if (!request.is_object()) {
    return raw_error("No hay catálogos disponibles ahora mismo.");
  }
  cfx::Json body = request;
  cfx::CatalogPlan plan = cfx::plan_catalog(body);
  return to_c_string(plan.to_json().dump());
}

const char* cfx_parse_js_repo(const char* repo_json,
                              const char* base_url_override) {
  const std::string raw = repo_json ? repo_json : "";
  const std::string base = base_url_override ? base_url_override : "";
  cfx::JsRepoResult result = cfx::parse_js_repo(raw, base);
  return to_c_string(result.to_json().dump());
}

const char* cfx_normalize_js_streams(const char* raw_json,
                                     const char* plugin_name) {
  const std::string raw = raw_json ? raw_json : "";
  const std::string name = plugin_name ? plugin_name : "";
  cfx::Json parsed;
  if (!cfx::Json::parse(raw, parsed)) {
    cfx::Json empty = cfx::Json::object();
    empty.set("streams", cfx::Json::array());
    return to_c_string(empty.dump());
  }
  return to_c_string(cfx::normalize_js_streams(parsed, name).dump());
}

void cfx_free(char* pointer) { std::free(pointer); }

}  // extern "C"

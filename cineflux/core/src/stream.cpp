#include "cineflux/stream.hpp"

#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <limits>
#include <map>
#include <set>
#include <string>

#include "cineflux/errors.hpp"

namespace cfx {
namespace {

constexpr int kQualityLadder[] = {360, 480, 720, 1080, 1440, 2160};

std::string lower_ascii(const std::string& value) {
  std::string out = value;
  std::transform(out.begin(), out.end(), out.begin(),
                 [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
  return out;
}

bool is_word_char(char c) {
  return std::isalnum(static_cast<unsigned char>(c)) != 0 || c == '_';
}

// Busca `token` como palabra independiente (evita que "hd" case con "hdts").
bool has_token(const std::string& text, const std::string& token) {
  if (token.empty()) return false;
  std::size_t pos = 0;
  while ((pos = text.find(token, pos)) != std::string::npos) {
    const bool before_ok = pos == 0 || !is_word_char(text[pos - 1]);
    const std::size_t end = pos + token.size();
    const bool after_ok = end >= text.size() || !is_word_char(text[end]);
    if (before_ok && after_ok) return true;
    pos = end;
  }
  return false;
}

bool has_any(const std::string& text, std::initializer_list<const char*> tokens) {
  for (const char* token : tokens) {
    if (has_token(text, token)) return true;
  }
  return false;
}

int quality_index(int quality) {
  for (int i = 0; i < 6; ++i) {
    if (kQualityLadder[i] == quality) return i;
  }
  return -1;
}

void add_unique(std::vector<std::string>& list, const std::string& value) {
  if (value.empty()) return;
  if (std::find(list.begin(), list.end(), value) == list.end()) list.push_back(value);
}

std::string trim_copy(const std::string& value) {
  std::size_t begin = 0;
  std::size_t end = value.size();
  while (begin < end && std::isspace(static_cast<unsigned char>(value[begin]))) ++begin;
  while (end > begin && std::isspace(static_cast<unsigned char>(value[end - 1]))) --end;
  return value.substr(begin, end - begin);
}

}  // namespace

int detect_quality(const std::string& text) {
  const std::string value = lower_ascii(text);
  struct Marker {
    const char* token;
    int quality;
  };
  static const Marker markers[] = {
      {"2160p", 2160}, {"4k", 2160}, {"uhd", 2160},
      {"1440p", 1440}, {"2k", 1440},
      {"1080p", 1080}, {"1080i", 1080}, {"fullhd", 1080}, {"full hd", 1080}, {"fhd", 1080},
      {"720p", 720}, {"hd", 720},
      {"480p", 480}, {"sd", 480},
      {"360p", 360},
  };
  for (const auto& marker : markers) {
    if (has_token(value, marker.token)) return marker.quality;
  }
  // Formatos sin sufijo "p": "x264 1080", "1920x1080" → 1080.
  static const Marker bare[] = {
      {"1920x1080", 1080}, {"1280x720", 720}, {"3840x2160", 2160}, {"1080", 1080},
      {"720", 720}, {"2160", 2160},
  };
  for (const auto& marker : bare) {
    if (has_token(value, marker.token)) return marker.quality;
  }
  return 0;
}

std::string quality_label(int quality) {
  switch (normalize_quality(quality)) {
    case 2160: return "4K";
    case 1440: return "2K";
    case 1080: return "1080p";
    case 720: return "720p";
    case 480: return "480p";
    default: return "Auto";
  }
}

std::vector<std::string> detect_languages(const std::string& text) {
  const std::string value = lower_ascii(text);
  std::vector<std::string> result;

  if (has_any(value, {"latino", "latam", "es-la", "esla", "lat"})) add_unique(result, "latino");
  if (has_any(value, {"castellano", "españa", "spain", "cast", "spa", "esp", "es-es"})) {
    add_unique(result, "castellano");
  }
  if (has_any(value, {"dual", "multi", "dual audio", "multi audio", "multiaudio"})) {
    add_unique(result, "dual");
  }
  if (has_any(value, {"sub", "subs", "subtitulado", "subtitulada", "vose", "vostfr",
                      "subtitles", "subbed", "srt", "hardsub", "softsub"})) {
    add_unique(result, "sub");
  }
  if (has_any(value, {"eng", "english", "ingles", "inglés", "original", "vo"})) {
    add_unique(result, "en");
  }
  if (result.empty() && has_any(value, {"spanish", "español", "espanol"})) {
    add_unique(result, "castellano");
  }
  return result;
}

std::string language_label(const std::vector<std::string>& languages) {
  std::string out;
  for (const auto& language : languages) {
    if (language == "sub") continue;
    if (!out.empty()) out += " · ";
    if (language == "latino") out += "Latino";
    else if (language == "castellano") out += "Castellano";
    else if (language == "dual") out += "Dual";
    else if (language == "multi") out += "Multi";
    else if (language == "es") out += "Español";
    else if (language == "en") out += "Original";
    else out += language;
  }
  if (out.empty()) {
    for (const auto& language : languages) {
      if (language == "sub") return "Subtitulado";
    }
  }
  return out;
}

long long parse_size_bytes(const std::string& text) {
  const std::string value = lower_ascii(text);
  for (std::size_t i = 0; i < value.size(); ++i) {
    if (!std::isdigit(static_cast<unsigned char>(value[i]))) continue;
    // Lee el número.
    std::size_t end = i;
    bool dot = false;
    while (end < value.size()) {
      const char c = value[end];
      if (std::isdigit(static_cast<unsigned char>(c))) {
        ++end;
        continue;
      }
      if ((c == '.' || c == ',') && !dot && end + 1 < value.size() &&
          std::isdigit(static_cast<unsigned char>(value[end + 1]))) {
        dot = true;
        ++end;
        continue;
      }
      break;
    }
    std::string number_text = value.substr(i, end - i);
    std::replace(number_text.begin(), number_text.end(), ',', '.');
    if (number_text.empty() || number_text == ".") {
      i = end;
      continue;
    }
    // Salta espacios y lee la unidad.
    std::size_t unit_pos = end;
    while (unit_pos < value.size() && value[unit_pos] == ' ') ++unit_pos;
    std::string unit;
    while (unit_pos < value.size() && unit.size() < 5 &&
           std::isalpha(static_cast<unsigned char>(value[unit_pos]))) {
      unit += value[unit_pos++];
    }
    double number = 0;
    try {
      number = std::stod(number_text);
    } catch (...) {
      i = end;
      continue;
    }
    long long multiplier = 0;
    if (unit == "tb") multiplier = 1000LL * 1000 * 1000 * 1000;
    else if (unit == "gb" || unit == "gib") multiplier = 1000LL * 1000 * 1000;
    else if (unit == "mb" || unit == "mib") multiplier = 1000LL * 1000;
    else if (unit == "kb" || unit == "kib") multiplier = 1000LL;
    if (multiplier > 0 && number > 0) {
      return static_cast<long long>(number * static_cast<double>(multiplier));
    }
    i = end;
  }
  return 0;
}

bool looks_like_direct_media(const std::string& url) {
  const std::string value = lower_ascii(url);
  auto cut = value.find_first_of("?#");
  const std::string path = cut == std::string::npos ? value : value.substr(0, cut);
  static const char* suffixes[] = {".mp4", ".m4v",  ".webm", ".mkv", ".m3u8",
                                   ".mpd", ".mov",  ".avi",  ".ts"};
  for (const char* suffix : suffixes) {
    const std::size_t len = std::char_traits<char>::length(suffix);
    if (path.size() >= len && path.compare(path.size() - len, len, suffix) == 0) return true;
  }
  return false;
}

bool looks_like_page(const std::string& url) {
  if (url.empty()) return false;
  const std::string value = lower_ascii(url);
  if (value.rfind("http", 0) != 0) return true;
  return !looks_like_direct_media(url);
}

bool is_torrent_stream(const std::string& url, const std::string& info_hash) {
  if (!info_hash.empty()) return true;
  const std::string value = lower_ascii(url);
  return value.rfind("magnet:", 0) == 0 || value.size() == 40;  // hash suelto
}

StreamCandidate normalize_stream(const Json& stream, const std::string& addon_id,
                                 const std::string& addon_name, int addon_priority) {
  StreamCandidate candidate;
  candidate.addon_id = addon_id;
  candidate.addon_name = addon_name;
  candidate.addon_priority = addon_priority;
  candidate.url = trim_copy(stream["url"].string());
  candidate.external_url = trim_copy(stream["externalUrl"].string());
  candidate.info_hash = trim_copy(stream["infoHash"].string());

  std::string name = stream["name"].string();
  std::string title = stream["title"].string();
  std::string description = stream["description"].string();
  std::string filename = stream["behaviorHints"]["filename"].string();
  const std::string text = name + " " + title + " " + description + " " + filename;

  candidate.quality = detect_quality(text);
  candidate.quality_label = quality_label(candidate.quality);
  candidate.languages = detect_languages(text);
  candidate.language_label = language_label(candidate.languages);
  candidate.hdr = has_any(lower_ascii(text), {"hdr", "hdr10", "dolby vision", "dolbyvision", "hlg", "dv"});
  candidate.cam = has_any(lower_ascii(text),
                          {"cam", "camrip", "hdcam", "ts", "hdts", "telesync", "telecine",
                           "scr", "screener", "dvdscr", "predvd", "hc"});
  candidate.hevc = has_any(lower_ascii(text), {"hevc", "x265", "h265", "h 265"});
  candidate.remux = has_any(lower_ascii(text), {"remux", "bdremux"});
  candidate.three_d = has_any(lower_ascii(text), {"3d", "hsbs", "sbs"});
  candidate.torrent = is_torrent_stream(candidate.url, candidate.info_hash);

  const Json& video_size = stream["behaviorHints"]["videoSize"];
  if (video_size.is_number()) {
    candidate.size_bytes = static_cast<long long>(video_size.number());
  }
  if (candidate.size_bytes <= 0) candidate.size_bytes = parse_size_bytes(text);

  if (stream["subtitles"].is_array()) {
    for (const auto& item : stream["subtitles"].items()) {
      if (!item.is_object()) continue;
      Subtitle subtitle;
      subtitle.url = item["url"].string();
      subtitle.language = item["lang"].string();
      subtitle.label = item["label"].string(subtitle.language);
      if (!subtitle.url.empty()) candidate.subtitles.push_back(std::move(subtitle));
    }
  }

  // Etiqueta corta y segura para la interfaz.
  std::string label = candidate.quality_label;
  if (!candidate.language_label.empty()) label += " · " + candidate.language_label;
  if (label.empty()) label = safe_label(name);
  if (label.empty()) label = safe_label(title);
  if (label.size() > 60) label = label.substr(0, 57) + "…";
  candidate.title = label;
  return candidate;
}

std::vector<StreamCandidate> streams_from_response(const Json& response,
                                                   const std::string& addon_id,
                                                   const std::string& addon_name,
                                                   int addon_priority) {
  std::vector<StreamCandidate> out;
  const Json& streams = response["streams"];
  if (!streams.is_array()) return out;
  for (const auto& item : streams.items()) {
    if (!item.is_object()) continue;
    const std::string url = trim_copy(item["url"].string());
    const std::string hash = trim_copy(item["infoHash"].string());
    const std::string external = trim_copy(item["externalUrl"].string());
    if (url.empty() && hash.empty() && external.empty()) continue;
    out.push_back(normalize_stream(item, addon_id, addon_name, addon_priority));
  }
  return out;
}

std::vector<StreamCandidate> dedupe_streams(std::vector<StreamCandidate> streams) {
  std::map<std::string, std::size_t> seen;
  std::vector<StreamCandidate> out;
  for (auto& stream : streams) {
    std::string key;
    if (!stream.info_hash.empty()) {
      key = "hash:" + lower_ascii(stream.info_hash) + ":" + std::to_string(stream.quality);
    } else if (!stream.url.empty()) {
      key = "url:" + stream.url;
    } else {
      key = "page:" + stream.external_url;
    }
    auto it = seen.find(key);
    if (it == seen.end()) {
      seen[key] = out.size();
      out.push_back(std::move(stream));
    } else {
      auto& existing = out[it->second];
      const int existing_rank = existing.quality + static_cast<int>(existing.languages.size()) * 10;
      const int incoming_rank = stream.quality + static_cast<int>(stream.languages.size()) * 10;
      if (incoming_rank > existing_rank ||
          (incoming_rank == existing_rank &&
           stream.addon_priority < existing.addon_priority)) {
        existing = std::move(stream);
      }
    }
  }
  return out;
}

int score_stream(StreamCandidate& stream, const PlaybackProfile& profile, int addon_count) {
  stream.rationale.clear();
  stream.score = -1;

  // Filtros duros -----------------------------------------------------------
  if (stream.url.empty()) {
    stream.rationale = "sin-url";
    return -1;
  }
  if (stream.torrent && !profile.allow_torrent) {
    stream.rationale = "torrent-no-soportado";
    return -1;
  }
  if (stream.cam && !profile.allow_cam) {
    stream.rationale = "cam";
    return -1;
  }
  const bool has_language_match =
      profile.languages.empty() ||
      std::any_of(stream.languages.begin(), stream.languages.end(), [&](const std::string& tag) {
        return std::find(profile.languages.begin(), profile.languages.end(), tag) !=
               profile.languages.end();
      });
  if (profile.strict_language && !has_language_match) {
    stream.rationale = "idioma";
    return -1;
  }
  if (stream.quality > 0 && stream.quality > profile.max_quality) {
    stream.rationale = "calidad-fuera-de-perfil";
    return -1;
  }

  int score = 100;

  // Idioma ------------------------------------------------------------------
  if (profile.languages.empty()) {
    score += 10;
  } else if (stream.languages.empty()) {
    score -= 8;
    stream.rationale = "idioma-desconocido";
  } else {
    int best_rank = -1;
    for (const auto& tag : stream.languages) {
      auto it = std::find(profile.languages.begin(), profile.languages.end(), tag);
      if (it == profile.languages.end()) continue;
      const int rank = static_cast<int>(std::distance(profile.languages.begin(), it));
      if (best_rank < 0 || rank < best_rank) best_rank = rank;
    }
    if (best_rank >= 0) {
      score += 42 - 8 * std::min(best_rank, 3);
      stream.rationale = "idioma";
    } else {
      score -= 30;
      stream.rationale = "idioma-otro";
    }
    if (profile.prefer_dual &&
        std::find(stream.languages.begin(), stream.languages.end(), "dual") !=
            stream.languages.end() &&
        std::find(profile.languages.begin(), profile.languages.end(), "dual") !=
            profile.languages.end()) {
      score += 5;
    }
  }

  const bool has_sub_tag =
      std::find(stream.languages.begin(), stream.languages.end(), "sub") != stream.languages.end();
  if (profile.prefer_subtitles) {
    score += has_sub_tag ? 14 : 0;
    if (!stream.subtitles.empty()) score += 6;
  } else if (has_sub_tag) {
    score -= 6;
  }

  // Calidad -----------------------------------------------------------------
  const int target_index = std::max(0, quality_index(profile.target_quality));
  if (stream.quality == 0) {
    score -= 12;
  } else {
    const int stream_index = quality_index(stream.quality);
    if (stream_index >= 0) {
      const int distance = std::abs(stream_index - target_index);
      score += 32 - distance * 9;
    }
  }
  if (profile.data_saver) {
    if (stream.quality >= 2160) score -= 32;
    else if (stream.quality >= 1080) score -= 12;
    else if (stream.quality == 720) score += 8;
    if (stream.size_bytes > 3LL * 1000 * 1000 * 1000) score -= 20;
  } else {
    if (stream.size_bytes > 0 && stream.size_bytes < 350LL * 1000 * 1000) score -= 6;
    if (stream.size_bytes > 8LL * 1000 * 1000 * 1000) score -= 4;
  }
  if (stream.hdr) score += profile.prefer_hdr ? 7 : 1;
  if (stream.remux) score += profile.data_saver ? -10 : 3;
  if (stream.hevc) score += 2;
  if (stream.three_d) score -= 8;

  // Entrega -----------------------------------------------------------------
  if (stream.url.rfind("http", 0) == 0) {
    if (looks_like_direct_media(stream.url)) {
      score += 10;
    } else if (looks_like_page(stream.url)) {
      score -= 14;
    }
  }
  if (!stream.external_url.empty() && stream.url.empty()) score -= 20;

  // Preferencia de plugins (orden del usuario) -------------------------------
  const int normalized_priority = std::max(0, stream.addon_priority);
  score -= normalized_priority * 4;
  if (addon_count > 0 && normalized_priority == 0) score += 3;

  stream.score = std::max(0, score);
  return stream.score;
}

RankingResult rank_streams(std::vector<StreamCandidate> streams,
                           const PlaybackProfile& profile,
                           const RankingOptions& options) {
  RankingResult result;
  result.received = streams.size();
  streams = dedupe_streams(std::move(streams));

  std::set<std::string> addons;
  for (const auto& stream : streams) addons.insert(stream.addon_id);
  const int addon_count = static_cast<int>(addons.size());

  // Copia para el reintento sin techo de calidad.
  const std::vector<StreamCandidate> pool = streams;
  std::vector<StreamCandidate> scored;
  for (auto stream : streams) {
    if (stream.torrent && !options.allow_torrents && !profile.allow_torrent) {
      ++result.rejected;
      continue;
    }
    const int score = score_stream(stream, profile, addon_count);
    if (score < 0) {
      ++result.rejected;
      continue;
    }
    scored.push_back(std::move(stream));
  }

  auto sort_candidates = [](std::vector<StreamCandidate>& list) {
    std::stable_sort(list.begin(), list.end(), [](const StreamCandidate& a, const StreamCandidate& b) {
      if (a.score != b.score) return a.score > b.score;
      if (a.quality != b.quality) return a.quality > b.quality;
      if (a.addon_priority != b.addon_priority) return a.addon_priority < b.addon_priority;
      return a.title < b.title;
    });
  };
  sort_candidates(scored);

  // Si el techo de calidad deja la lista vacía, se reintenta sin ese techo
  // para no dejar al usuario sin reproducción (los perfiles con techo son
  // preferencias, no una prohibición).
  if (scored.empty() && !pool.empty() && profile.max_quality < 2160) {
    std::vector<StreamCandidate> relaxed;
    for (auto stream : pool) {
      StreamCandidate copy = stream;
      PlaybackProfile lenient = profile;
      lenient.max_quality = 2160;
      const int score = score_stream(copy, lenient, addon_count);
      if (score < 0) continue;
      copy.score = score - 6;  // penalización por salir del perfil
      copy.rationale += "+fuera-de-perfil";
      relaxed.push_back(std::move(copy));
    }
    sort_candidates(relaxed);
    scored = std::move(relaxed);
  }

  result.playable = scored.size();
  if (scored.empty()) {
    result.ok = false;
    result.failure = Failure::NoStreams;
    result.message = user_message(Failure::NoStreams);
    return result;
  }

  const std::size_t limit = options.limit == 0 ? scored.size() : options.limit;
  for (std::size_t i = 0; i < scored.size() && result.candidates.size() < limit; ++i) {
    if (!options.include_backups && i > 0) break;
    scored[i].role = result.candidates.empty() ? "primary" : "backup";
    result.candidates.push_back(std::move(scored[i]));
  }
  result.ok = true;
  return result;
}

}  // namespace cfx

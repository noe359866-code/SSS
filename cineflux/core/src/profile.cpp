#include "cineflux/profile.hpp"

#include <algorithm>
#include <cctype>
#include <set>

namespace cfx {
namespace {

constexpr int kQualities[] = {360, 480, 720, 1080, 1440, 2160};

std::string trim(const std::string& value) {
  std::size_t begin = 0;
  std::size_t end = value.size();
  while (begin < end && std::isspace(static_cast<unsigned char>(value[begin]))) ++begin;
  while (end > begin && std::isspace(static_cast<unsigned char>(value[end - 1]))) --end;
  return value.substr(begin, end - begin);
}

std::string lower(std::string value) {
  std::transform(value.begin(), value.end(), value.begin(),
                 [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
  return value;
}

std::vector<std::string> string_array(const Json& value) {
  std::vector<std::string> out;
  if (!value.is_array()) return out;
  for (const auto& item : value.items()) {
    if (!item.is_string()) continue;
    std::string tag = lower(trim(item.string()));
    if (!tag.empty()) out.push_back(tag);
  }
  return out;
}

bool valid_hex_color(const std::string& value) {
  if (value.size() != 7 || value.front() != '#') return false;
  for (std::size_t i = 1; i < value.size(); ++i) {
    if (!std::isxdigit(static_cast<unsigned char>(value[i]))) return false;
  }
  return true;
}

}  // namespace

int normalize_quality(int quality) {
  if (quality <= 0) return 0;
  int best = 360;
  int best_distance = 1 << 30;
  for (int q : kQualities) {
    int distance = quality > q ? quality - q : q - quality;
    if (distance < best_distance) {
      best_distance = distance;
      best = q;
    }
  }
  return best;
}

PlaybackProfile profile_from_json(const Json& json) {
  PlaybackProfile profile;
  profile.id = trim(json["id"].string());
  profile.name = trim(json["name"].string());
  profile.description = json["description"].string();
  profile.languages = string_array(json["languages"]);
  profile.target_quality = normalize_quality(json["targetQuality"].integer(1080));
  profile.max_quality = normalize_quality(json["maxQuality"].integer(2160));
  profile.allow_cam = json["allowCam"].boolean(false);
  profile.allow_torrent = json["allowTorrent"].boolean(false);
  profile.prefer_hdr = json["preferHdr"].boolean(false);
  profile.prefer_subtitles = json["preferSubtitles"].boolean(false);
  profile.prefer_dual = json["preferDual"].boolean(true);
  profile.strict_language = json["strictLanguage"].boolean(false);
  profile.data_saver = json["dataSaver"].boolean(false);
  std::string accent = json["accent"].string("#22d3ee");
  profile.accent = valid_hex_color(accent) ? accent : "#22d3ee";
  if (profile.id.empty()) {
    profile.id = lower(profile.name);
    std::replace(profile.id.begin(), profile.id.end(), ' ', '-');
  }
  if (profile.max_quality < profile.target_quality) {
    profile.max_quality = profile.target_quality;
  }
  return profile;
}

Json profile_to_json(const PlaybackProfile& profile) {
  Json json = Json::object();
  json.set("id", profile.id);
  json.set("name", profile.name);
  json.set("description", profile.description);
  json.set("accent", profile.accent);
  Json languages = Json::array();
  for (const auto& item : profile.languages) languages.push(Json(item));
  json.set("languages", std::move(languages));
  json.set("targetQuality", profile.target_quality);
  json.set("maxQuality", profile.max_quality);
  json.set("allowCam", profile.allow_cam);
  json.set("allowTorrent", profile.allow_torrent);
  json.set("preferHdr", profile.prefer_hdr);
  json.set("preferSubtitles", profile.prefer_subtitles);
  json.set("preferDual", profile.prefer_dual);
  json.set("strictLanguage", profile.strict_language);
  json.set("dataSaver", profile.data_saver);
  return json;
}

ProfileValidation validate_profile(const PlaybackProfile& profile) {
  ProfileValidation result;
  if (profile.name.empty()) {
    result.error = "Ponle un nombre al perfil.";
    return result;
  }
  if (profile.name.size() > 40) {
    result.error = "El nombre del perfil es demasiado largo.";
    return result;
  }
  if (profile.strict_language && profile.languages.empty()) {
    result.error = "Elige al menos un idioma si el perfil es estricto.";
    return result;
  }
  if (profile.target_quality <= 0) {
    result.error = "La calidad objetivo no es válida.";
    return result;
  }
  static const std::set<std::string> known = {
      "latino", "castellano", "es", "en", "dual", "sub", "multi", "original"};
  for (const auto& language : profile.languages) {
    if (!known.count(language)) {
      result.error = "Idioma desconocido en el perfil: " + language;
      return result;
    }
  }
  result.ok = true;
  return result;
}

std::vector<PlaybackProfile> default_profiles() {
  PlaybackProfile latino_1080;
  latino_1080.id = "latino-1080";
  latino_1080.name = "Latino 1080p";
  latino_1080.description = "Audio latino; Full HD como tope.";
  latino_1080.accent = "#22d3ee";
  latino_1080.languages = {"latino", "dual", "es"};
  latino_1080.target_quality = 1080;
  latino_1080.max_quality = 1080;

  PlaybackProfile latino_4k = latino_1080;
  latino_4k.id = "latino-4k";
  latino_4k.name = "Latino 4K";
  latino_4k.description = "Audio latino; máxima calidad disponible.";
  latino_4k.accent = "#f43f5e";
  latino_4k.target_quality = 2160;
  latino_4k.max_quality = 2160;
  latino_4k.prefer_hdr = true;

  PlaybackProfile castellano = latino_1080;
  castellano.id = "castellano";
  castellano.name = "Castellano";
  castellano.description = "Español de España.";
  castellano.accent = "#f59e0b";
  castellano.languages = {"castellano", "es", "dual"};
  castellano.target_quality = 1080;
  castellano.max_quality = 1080;

  PlaybackProfile subtitulado;
  subtitulado.id = "subtitulado";
  subtitulado.name = "Subtitulado";
  subtitulado.description = "Voz original y subtítulos.";
  subtitulado.accent = "#a78bfa";
  subtitulado.languages = {"en", "original"};
  subtitulado.target_quality = 2160;
  subtitulado.max_quality = 2160;
  subtitulado.prefer_subtitles = true;
  subtitulado.prefer_dual = false;
  subtitulado.prefer_hdr = true;

  PlaybackProfile movil = latino_1080;
  movil.id = "movil";
  movil.name = "Móvil / datos";
  movil.description = "Menos peso, arranque rápido.";
  movil.accent = "#34d399";
  movil.languages = {"latino", "dual", "es", "castellano"};
  movil.target_quality = 720;
  movil.max_quality = 1080;
  movil.data_saver = true;

  PlaybackProfile automatico;
  automatico.id = "automatico";
  automatico.name = "Automático";
  automatico.description = "La mejor opción disponible, sin filtros de idioma.";
  automatico.accent = "#94a3b8";
  automatico.languages = {};
  automatico.target_quality = 1080;
  automatico.max_quality = 2160;

  return {latino_1080, latino_4k, castellano, subtitulado, movil, automatico};
}

std::string profile_summary(const PlaybackProfile& profile) {
  std::string summary;
  if (!profile.languages.empty()) {
    for (std::size_t i = 0; i < profile.languages.size(); ++i) {
      if (i) summary += " · ";
      summary += profile.languages[i];
    }
  } else {
    summary = "cualquier idioma";
  }
  summary += " · " + std::to_string(profile.max_quality) + "p máx";
  if (profile.data_saver) summary += " · ahorro de datos";
  if (profile.prefer_subtitles) summary += " · subtítulos";
  return summary;
}

}  // namespace cfx

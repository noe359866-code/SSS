// Perfiles de reproducción: la única decisión del usuario.
//
// Un perfil describe idioma preferido, calidad objetivo, límites y
// preferencias. Con él, el núcleo puntúa todos los streams disponibles y
// elige uno automáticamente.
#pragma once

#include <string>
#include <vector>

#include "cineflux/json.hpp"

namespace cfx {

struct Subtitle {
  std::string url;
  std::string language;
  std::string label;
};

struct PlaybackProfile {
  std::string id;
  std::string name;
  std::string accent = "#22d3ee";  // color de acento en la interfaz
  std::string description;

  std::vector<std::string> languages;  // orden de preferencia; vacío = cualquiera
  int target_quality = 1080;           // 480, 720, 1080, 1440, 2160
  int max_quality = 2160;              // techo permitido

  bool allow_cam = false;
  bool allow_torrent = false;
  bool prefer_hdr = false;
  bool prefer_subtitles = false;
  bool prefer_dual = true;
  bool strict_language = false;
  bool data_saver = false;
};

struct ProfileValidation {
  bool ok = false;
  std::string error;
};

std::vector<PlaybackProfile> default_profiles();

PlaybackProfile profile_from_json(const Json& json);
Json profile_to_json(const PlaybackProfile& profile);

ProfileValidation validate_profile(const PlaybackProfile& profile);

// Ajusta calidades a la escala usada por la app.
int normalize_quality(int quality);
std::string profile_summary(const PlaybackProfile& profile);

}  // namespace cfx

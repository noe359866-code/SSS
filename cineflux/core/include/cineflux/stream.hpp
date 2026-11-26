// Modelo de streams y algoritmo de selección automática.
//
// El usuario NO elige fuente ni web: elige un perfil y el reproductor
// selecciona el stream. Este archivo contiene la lógica pura y testeable:
// normalización, fusión de duplicados, puntuación por perfil y orden final.
#pragma once

#include <string>
#include <vector>

#include "cineflux/errors.hpp"
#include "cineflux/json.hpp"
#include "cineflux/profile.hpp"

namespace cfx {

struct StreamCandidate {
  std::string addon_id;
  std::string addon_name;
  int addon_priority = 0;

  std::string url;           // URL reproducible (http/https)
  std::string external_url;  // página externa, no reproducible directamente
  std::string info_hash;     // torrent (no reproducible en móvil sin motor)

  std::vector<Subtitle> subtitles;

  int quality = 0;           // 2160, 1440, 1080, 720, 480, 360, 0 = desconocida
  std::string quality_label;
  std::vector<std::string> languages;  // latino, castellano, es, en, dual, sub…
  std::string language_label;
  std::string title;         // etiqueta corta para la interfaz

  bool hdr = false;
  bool cam = false;
  bool torrent = false;
  bool hevc = false;
  bool remux = false;
  bool three_d = false;

  long long size_bytes = 0;
  int score = 0;
  std::string role;          // primary | backup
  std::string rationale;     // por qué fue elegido (para logs, no para UI)
};

struct RankingOptions {
  bool allow_torrents = false;
  std::size_t limit = 12;
  bool include_backups = true;
};

struct RankingResult {
  std::vector<StreamCandidate> candidates;  // ordenados, el primero es el elegido
  std::size_t received = 0;
  std::size_t playable = 0;
  std::size_t rejected = 0;
  bool ok = false;
  Failure failure = Failure::None;  // requiere errors.hpp
  std::string message;
};

// --- utilidades de detección ------------------------------------------------
int detect_quality(const std::string& text);
std::string quality_label(int quality);
std::vector<std::string> detect_languages(const std::string& text);
std::string language_label(const std::vector<std::string>& languages);
long long parse_size_bytes(const std::string& text);
bool looks_like_direct_media(const std::string& url);
bool looks_like_page(const std::string& url);
bool is_torrent_stream(const std::string& url, const std::string& info_hash);

// Normaliza un stream tal y como lo entrega un plugin (respuesta de addon).
StreamCandidate normalize_stream(const Json& stream, const std::string& addon_id,
                                 const std::string& addon_name, int addon_priority);

// Convierte la respuesta completa `{ "streams": [...] }` de un addon.
std::vector<StreamCandidate> streams_from_response(const Json& response,
                                                  const std::string& addon_id,
                                                  const std::string& addon_name,
                                                  int addon_priority);

// Elimina duplicados (misma URL, o mismo torrent + calidad) conservando el mejor.
std::vector<StreamCandidate> dedupe_streams(std::vector<StreamCandidate> streams);

// Puntúa un candidato contra un perfil. Los descartes duros devuelven
// `score = -1` y un motivo en `rationale`.
int score_stream(StreamCandidate& stream, const PlaybackProfile& profile,
                 int addon_count);

// Ordena y filtra. El primer elemento es el que se reproducirá.
RankingResult rank_streams(std::vector<StreamCandidate> streams,
                           const PlaybackProfile& profile,
                           const RankingOptions& options = RankingOptions());

}  // namespace cfx

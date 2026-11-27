// Cineflux · plugins JS locales (repositorios estilo Nuvio y archivos .js).
#pragma once

#include <string>
#include <vector>

#include "cineflux/json.hpp"

namespace cfx {

struct JsPluginEntry {
  std::string id;
  std::string name;
  std::string description;
  std::string version;
  std::string script_url;          // URL resuelta del archivo .js
  std::vector<std::string> types;  // "movie", "series"
  bool enabled_by_default = true;

  Json to_json() const;
};

struct JsRepoResult {
  bool ok = false;
  std::string name;
  std::string version;
  std::string base_url;
  std::vector<JsPluginEntry> plugins;
  std::string message;

  Json to_json() const;
};

// Parsea un manifest de repositorio JS (formato Nuvio: `scrapers` o `providers`).
// Resuelve rutas relativas (`filename`) usando `base_url_override`.
JsRepoResult parse_js_repo(const std::string& repo_json,
                           const std::string& base_url_override = "");

// Normaliza la salida cruda de `getStreams()` (array o `{streams:[...]}`) al
// formato `{"streams":[...]}` compatible con `cfx_rank_streams`.
Json normalize_js_streams(const Json& raw, const std::string& plugin_name = "");

}  // namespace cfx

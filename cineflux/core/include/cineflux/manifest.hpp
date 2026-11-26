// Manifest de plugin (formato addon, compatible con manifests "en crudo").
#pragma once

#include <string>
#include <vector>

#include "cineflux/json.hpp"

namespace cfx {

struct Resource {
  std::string name;                       // stream, catalog, meta, subtitles…
  std::vector<std::string> types;         // movie, series, channel…
  std::vector<std::string> id_prefixes;   // tt, tmdb, kitsu…
  bool accepts(const std::string& type, const std::string& id) const;
};

struct CatalogDef {
  std::string type;   // movie | series
  std::string id;     // top, popular, genre:…
  std::string name;   // título visible
  bool extra_search = false;
  bool extra_genre = false;
  bool extra_skip = false;
  std::vector<std::string> required_extras;  // extras sin los que el catálogo no responde

  Json to_json() const;
};

struct Manifest {
  std::string id;
  std::string name;
  std::string version;
  std::string description;
  std::string logo;
  std::string background;
  std::vector<std::string> types;
  std::vector<Resource> resources;
  std::vector<CatalogDef> catalogs;
  std::vector<std::string> id_prefixes;
  std::string base_url;      // base de transporte sin /manifest.json
  std::string manifest_url;  // URL original del manifest, si existe

  bool has_resource(const std::string& name) const;
  const Resource* resource(const std::string& name) const;
  bool supports_type(const std::string& type) const;
  bool can_stream(const std::string& type, const std::string& id) const;
  Json to_json() const;
};

struct ManifestResult {
  bool ok = false;
  Manifest manifest;
  std::string message;              // mensaje apto para la interfaz (sin códigos)
  std::vector<std::string> warnings;
};

// Acepta una URL de manifest, o un manifest "en crudo" (JSON) que incluya
// `transportUrl` / `baseUrl` / `url`. `base_url_override` tiene prioridad.
ManifestResult parse_manifest(const std::string& raw_or_url,
                              const std::string& base_url_override = "");

// Normaliza una URL base: quita /manifest.json, query y barra final.
std::string normalize_base_url(const std::string& url);

// Une base + ruta relativa, respetando bases con subcarpeta.
std::string join_url(const std::string& base, const std::string& path);

// Construye la URL de un recurso: {base}/{resource}/{type}/{id}.json[?extra]
std::string build_resource_url(const std::string& base, const std::string& resource,
                               const std::string& type, const std::string& id,
                               const std::string& extra = "");

// id de catálogo + extras codificados ("genre=acción&skip=20") como en el
// ecosistema de addons.
std::string catalog_path(const CatalogDef& catalog, const std::string& extra = "");

}  // namespace cfx

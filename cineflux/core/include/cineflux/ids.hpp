// Resolución de identificadores y URLs de plugins.
#pragma once

#include <string>
#include <vector>

#include "cineflux/manifest.hpp"

namespace cfx {

// Codifica un componente de URL dejando intactos los caracteres seguros y
// los dos puntos (los ids de episodio usan "tt123:1:2").
std::string url_encode(const std::string& value);

// Ids que se pueden pedir a un plugin para un título, en orden de preferencia:
//   serie  → tt123:1:2, tt123, tmdb:456:1:2, 456
//   película → tt123, tmdb:456, 456
std::vector<std::string> stream_id_candidates(const std::string& type,
                                              const std::string& imdb_id,
                                              const std::string& tmdb_id,
                                              int season, int episode);

// Igual que arriba pero filtrado por lo que el manifest declara aceptar.
std::vector<std::string> stream_ids_for(const Manifest& manifest, const std::string& type,
                                        const std::string& imdb_id, const std::string& tmdb_id,
                                        int season, int episode);

// URL de stream lista para pedir: {base}/stream/{type}/{id}.json
std::string stream_url_for(const Manifest& manifest, const std::string& type,
                           const std::string& id);

// URL de catálogo con extras opcionales (search / genre / skip).
std::string catalog_url_for(const Manifest& manifest, const CatalogDef& catalog,
                            const std::string& search = "", const std::string& genre = "",
                            int skip = 0);

// URL del manifest de un plugin.
std::string manifest_url_for(const std::string& base_or_url);

// Convierte "tt123" en "tt0123" si hiciera falta y valida el prefijo mínimo.
bool looks_like_imdb_id(const std::string& id);

}  // namespace cfx

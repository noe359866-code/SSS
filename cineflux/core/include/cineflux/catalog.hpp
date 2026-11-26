// Planificación de catálogos de addons (Nuvio, Cinemeta y cualquier manifest
// compatible). El núcleo decide qué filas pedir y con qué URL; las apps solo
// descargan y pintan.
#pragma once

#include <string>
#include <vector>

#include "cineflux/json.hpp"

namespace cfx {

struct CatalogRow {
  std::string addon_id;
  std::string addon_name;
  std::string base_url;
  std::string type;        // movie | series
  std::string catalog_id;  // trending-movies, top, popular-movies…
  std::string name;        // título visible
  std::string url;         // URL final del catálogo
  bool search = false;     // la fila nace de una búsqueda

  Json to_json() const;
};

struct CatalogPlan {
  bool ok = false;
  std::vector<CatalogRow> rows;
  std::string message;

  Json to_json() const;
};

// request = {
//   "types": ["movie","series"], "query": "", "limit": 12, "perSource": 12,
//   "sources": [{"baseUrl":"https://…","manifest":{…}}]
// }
CatalogPlan plan_catalog(const Json& request);

}  // namespace cfx

#include "cineflux/catalog.hpp"

#include <algorithm>
#include <set>

#include "cineflux/ids.hpp"
#include "cineflux/manifest.hpp"

namespace cfx {
namespace {

std::vector<std::string> string_list(const Json& value) {
  std::vector<std::string> out;
  if (!value.is_array()) return out;
  for (const auto& item : value.items()) {
    if (item.is_string() && !item.string().empty()) out.push_back(item.string());
  }
  return out;
}

bool has_required(const Json& catalog) {
  const Json& required = catalog["required"];
  if (required.is_array()) {
    for (const auto& item : required.items()) {
      if (item.is_string() && !item.string().empty()) return true;
      if (item.is_number() && item.number() > 0) return true;
    }
  }
  const Json& extra_required = catalog["extraRequired"];
  if (extra_required.is_array()) {
    for (const auto& item : extra_required.items()) {
      if (item.is_string() && !item.string().empty()) return true;
    }
  }
  for (const auto& extra : catalog["extra"].items()) {
    if (extra.is_object() && extra["isRequired"].boolean()) return true;
  }
  return false;
}

bool is_unsupported_catalog(const std::string& id) {
  // Catálogos que dependen de listas de IDs que no tenemos (últimos vídeos,
  // calendario) o de una selección de género obligatoria.
  if (id == "last-videos" || id == "calendar-videos") return true;
  // Los catálogos "por año/idioma" dependen de un extra que sin elegirlo
  // devuelven resultados arbitrarios.
  return id.find("by-year") != std::string::npos ||
         id.find("by-language") != std::string::npos;
}

}  // namespace

Json CatalogRow::to_json() const {
  Json obj = Json::object();
  obj.set("addonId", addon_id);
  obj.set("addonName", addon_name);
  obj.set("baseUrl", base_url);
  obj.set("type", type);
  obj.set("catalogId", catalog_id);
  obj.set("name", name);
  obj.set("url", url);
  obj.set("search", search);
  return obj;
}

Json CatalogPlan::to_json() const {
  Json obj = Json::object();
  obj.set("ok", ok);
  obj.set("count", static_cast<double>(rows.size()));
  obj.set("message", message);
  Json list = Json::array();
  for (const auto& row : rows) list.push(row.to_json());
  obj.set("rows", std::move(list));
  return obj;
}

CatalogPlan plan_catalog(const Json& request) {
  CatalogPlan plan;
  std::vector<std::string> types = string_list(request["types"]);
  if (types.empty()) types = {"movie", "series"};
  const std::string query = request["query"].string();
  const bool searching = !query.empty();

  int limit = static_cast<int>(request["limit"].number(12));
  if (limit <= 0) limit = 12;
  if (limit > 40) limit = 40;
  int per_source = static_cast<int>(request["perSource"].number(12));
  if (per_source <= 0) per_source = 12;
  if (per_source > limit) per_source = limit;

  std::set<std::string> seen;
  const Json& sources = request["sources"];
  if (sources.is_array()) {
    for (const auto& source : sources.items()) {
      if (static_cast<int>(plan.rows.size()) >= limit) break;
      if (!source.is_object()) continue;
      const Json& manifest = source["manifest"];
      if (!manifest.is_object()) continue;

      std::string base = source["baseUrl"].string();
      if (base.empty()) base = manifest["baseUrl"].string();
      if (base.empty()) continue;
      base = normalize_base_url(base);

      const std::string addon_id = manifest["id"].string();
      const std::string addon_name = manifest["name"].string(base);

      int used = 0;
      for (const auto& catalog : manifest["catalogs"].items()) {
        if (static_cast<int>(plan.rows.size()) >= limit || used >= per_source) break;
        if (!catalog.is_object()) continue;
        const std::string type = catalog["type"].string();
        const std::string id = catalog["id"].string();
        if (type.empty() || id.empty()) continue;
        if (std::find(types.begin(), types.end(), type) == types.end()) continue;
        if (has_required(catalog) || is_unsupported_catalog(id)) continue;

        bool supports_search = false;
        for (const auto& extra : catalog["extra"].items()) {
          if (extra.is_string() && extra.string() == "search") supports_search = true;
          if (extra.is_object() && extra["name"].string() == "search") supports_search = true;
        }
        if (searching && !supports_search) continue;

        const std::string extra = searching ? "search=" + url_encode(query) : "";
        const std::string key = addon_id + "|" + type + "|" + id + "|" + query;
        if (!seen.insert(key).second) continue;

        CatalogRow row;
        row.addon_id = addon_id;
        row.addon_name = addon_name;
        row.base_url = base;
        row.type = type;
        row.catalog_id = id;
        row.name = catalog["name"].string(id);
        row.url = build_resource_url(base, "catalog", url_encode(type), url_encode(id), extra);
        row.search = searching;
        plan.rows.push_back(std::move(row));
        ++used;
      }
    }
  }

  plan.ok = !plan.rows.empty();
  if (!plan.ok) {
    plan.message = searching
        ? "Ningún catálogo puede buscar ahora mismo. Revisa tus fuentes en Ajustes."
        : "No hay catálogos disponibles ahora mismo. Revisa tus fuentes en Ajustes.";
  }
  return plan;
}

}  // namespace cfx

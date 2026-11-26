#include "cineflux/manifest.hpp"

#include <algorithm>
#include <cctype>
#include <set>

#include "cineflux/errors.hpp"

namespace cfx {
namespace {

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

bool starts_with(const std::string& value, const std::string& prefix) {
  return value.size() >= prefix.size() && value.compare(0, prefix.size(), prefix) == 0;
}

bool ends_with_ci(const std::string& value, const std::string& suffix) {
  if (value.size() < suffix.size()) return false;
  return lower(value.substr(value.size() - suffix.size())) == lower(suffix);
}

std::string capitalize(const std::string& value) {
  if (value.empty()) return value;
  std::string out = value;
  out[0] = static_cast<char>(std::toupper(static_cast<unsigned char>(out[0])));
  std::replace(out.begin(), out.end(), '_', ' ');
  std::replace(out.begin(), out.end(), '-', ' ');
  return out;
}

std::string pretty_catalog_name(const CatalogDef& catalog) {
  std::string id = catalog.id;
  std::string prefix = catalog.type == "series" ? "Series · " : "Películas · ";
  if (id == "top") return prefix + "Top";
  if (id == "popular") return prefix + "Populares";
  if (id == "trending") return prefix + "Tendencias";
  if (id == "new") return prefix + "Novedades";
  if (id == "featured") return prefix + "Destacadas";
  if (id == "imdbRating") return prefix + "Mejor valoradas";
  if (starts_with(id, "genre:")) {
    return "Género · " + capitalize(id.substr(6));
  }
  if (starts_with(id, "search")) return "Buscar";
  auto colon = id.find(':');
  if (colon != std::string::npos) id = id.substr(colon + 1);
  return prefix + capitalize(id);
}

std::vector<std::string> string_list(const Json& value) {
  std::vector<std::string> out;
  if (!value.is_array()) return out;
  for (const auto& item : value.items()) {
    if (item.is_string()) {
      std::string text = trim(item.string());
      if (!text.empty()) out.push_back(text);
    }
  }
  return out;
}

void dedupe_strings(std::vector<std::string>& values) {
  std::set<std::string> seen;
  std::vector<std::string> out;
  for (const auto& value : values) {
    if (value.empty()) continue;
    if (seen.insert(value).second) out.push_back(value);
  }
  values.swap(out);
}

Resource resource_from(const Json& item) {
  Resource resource;
  if (item.is_string()) {
    resource.name = trim(item.string());
    return resource;
  }
  if (!item.is_object()) return resource;
  resource.name = item["name"].string();
  if (resource.name.empty()) resource.name = item["id"].string();
  resource.types = string_list(item["types"]);
  resource.id_prefixes = string_list(item["idPrefixes"]);
  return resource;
}

CatalogDef catalog_from(const Json& item) {
  CatalogDef catalog;
  catalog.type = item["type"].string();
  catalog.id = item["id"].string();
  catalog.name = item["name"].string();
  if (item["extraRequired"].is_array()) {
    for (const auto& required : item["extraRequired"].items()) {
      if (required.is_string() && !required.string().empty()) {
        catalog.required_extras.push_back(required.string());
      }
    }
  }
  if (item["extra"].is_array()) {
    for (const auto& extra : item["extra"].items()) {
      const std::string name = extra.is_string() ? extra.string() : extra["name"].string();
      if (name == "search") catalog.extra_search = true;
      if (name == "genre") catalog.extra_genre = true;
      if (name == "skip") catalog.extra_skip = true;
      if (extra.is_object() && extra["isRequired"].boolean()) {
        catalog.required_extras.push_back(name);
      }
    }
  }
  if (catalog.name.empty()) catalog.name = pretty_catalog_name(catalog);
  return catalog;
}

}  // namespace

bool Resource::accepts(const std::string& a_type, const std::string& id) const {
  if (!types.empty() &&
      std::find(types.begin(), types.end(), a_type) == types.end()) {
    return false;
  }
  if (id_prefixes.empty()) return true;
  for (const auto& prefix : id_prefixes) {
    if (starts_with(id, prefix)) return true;
  }
  return false;
}

Json CatalogDef::to_json() const {
  Json obj = Json::object();
  obj.set("type", type);
  obj.set("id", id);
  obj.set("name", name);
  Json extra = Json::array();
  if (extra_search) extra.push(Json("search"));
  if (extra_genre) extra.push(Json("genre"));
  if (extra_skip) extra.push(Json("skip"));
  obj.set("extra", std::move(extra));

  Json required = Json::array();
  for (const auto& name : required_extras) required.push(Json(name));
  obj.set("required", std::move(required));
  return obj;
}

bool Manifest::has_resource(const std::string& a_name) const {
  return resource(a_name) != nullptr;
}

const Resource* Manifest::resource(const std::string& a_name) const {
  for (const auto& item : resources) {
    if (item.name == a_name) return &item;
  }
  return nullptr;
}

bool Manifest::supports_type(const std::string& a_type) const {
  if (types.empty()) return true;
  return std::find(types.begin(), types.end(), a_type) != types.end();
}

bool Manifest::can_stream(const std::string& a_type, const std::string& id) const {
  const Resource* res = resource("stream");
  if (!res) return false;
  if (!supports_type(a_type)) return false;
  return res->accepts(a_type, id);
}

Json Manifest::to_json() const {
  Json obj = Json::object();
  obj.set("id", id);
  obj.set("name", name);
  obj.set("version", version);
  obj.set("description", description);
  obj.set("logo", logo);
  obj.set("background", background);
  obj.set("baseUrl", base_url);
  obj.set("manifestUrl", manifest_url);

  Json types_json = Json::array();
  for (const auto& item : types) types_json.push(Json(item));
  obj.set("types", std::move(types_json));

  Json resources_json = Json::array();
  for (const auto& res : resources) {
    Json item = Json::object();
    item.set("name", res.name);
    Json types = Json::array();
    for (const auto& t : res.types) types.push(Json(t));
    item.set("types", std::move(types));
    Json prefixes = Json::array();
    for (const auto& p : res.id_prefixes) prefixes.push(Json(p));
    item.set("idPrefixes", std::move(prefixes));
    resources_json.push(std::move(item));
  }
  obj.set("resources", std::move(resources_json));

  Json catalogs_json = Json::array();
  for (const auto& catalog : catalogs) catalogs_json.push(catalog.to_json());
  obj.set("catalogs", std::move(catalogs_json));

  Json prefixes = Json::array();
  for (const auto& p : id_prefixes) prefixes.push(Json(p));
  obj.set("idPrefixes", std::move(prefixes));

  Json caps = Json::object();
  caps.set("streams", has_resource("stream"));
  caps.set("catalog", has_resource("catalog"));
  caps.set("meta", has_resource("meta"));
  caps.set("subtitles", has_resource("subtitles"));
  obj.set("capabilities", std::move(caps));
  return obj;
}

std::string normalize_base_url(const std::string& url) {
  std::string value = trim(url);
  if (value.empty()) return value;
  // Quita query y fragmento.
  auto cut = value.find_first_of("?#");
  if (cut != std::string::npos) value = value.substr(0, cut);
  // Quita la barra final.
  while (!value.empty() && value.back() == '/') value.pop_back();
  // Quita /manifest.json (con o sin barra).
  if (ends_with_ci(value, "/manifest.json")) {
    value = value.substr(0, value.size() - std::string("/manifest.json").size());
  } else if (ends_with_ci(value, "manifest.json")) {
    value = value.substr(0, value.size() - std::string("manifest.json").size());
  }
  while (!value.empty() && value.back() == '/') value.pop_back();
  return value;
}

std::string join_url(const std::string& base, const std::string& path) {
  if (starts_with(lower(path), "http://") || starts_with(lower(path), "https://")) {
    return path;
  }
  if (base.empty()) return path;
  if (path.empty()) return base;
  if (path.front() == '/') return base + path;
  return base + "/" + path;
}

std::string build_resource_url(const std::string& base, const std::string& resource,
                               const std::string& type, const std::string& id,
                               const std::string& extra) {
  std::string path = resource + "/" + type + "/" + id;
  if (!extra.empty()) path += "/" + extra;
  path += ".json";
  return join_url(base, path);
}

std::string catalog_path(const CatalogDef& catalog, const std::string& extra) {
  std::string path = catalog.type + "/" + catalog.id;
  if (!extra.empty()) path += "/" + extra;
  return path;
}

ManifestResult parse_manifest(const std::string& raw_or_url,
                              const std::string& base_url_override) {
  ManifestResult result;
  const std::string input = trim(raw_or_url);
  if (input.empty()) {
    result.message = "Pega una URL o un manifest en crudo para instalar el plugin.";
    return result;
  }

  const std::string lowered = lower(input);
  const bool looks_like_url = starts_with(lowered, "http://") || starts_with(lowered, "https://");
  if (looks_like_url) {
    result.ok = true;
    result.manifest.base_url = normalize_base_url(input);
    result.manifest.manifest_url = input;
    result.warnings.push_back("Descarga el manifest desde esa URL para completar los datos del plugin.");
    return result;
  }

  Json json;
  std::string error;
  if (!Json::parse(input, json, &error)) {
    result.message = "El manifest no es un JSON válido.";
    result.warnings.push_back("Detalle técnico: " + error);
    return result;
  }
  if (!json.is_object()) {
    result.message = "El manifest debe ser un objeto JSON con id y name.";
    return result;
  }

  Manifest manifest;
  manifest.id = trim(json["id"].string());
  manifest.name = trim(json["name"].string());
  manifest.version = json["version"].string("0.0.0");
  manifest.description = json["description"].string();
  manifest.logo = json["logo"].string();
  manifest.background = json["background"].string();
  manifest.types = string_list(json["types"]);
  manifest.id_prefixes = string_list(json["idPrefixes"]);

  if (json["resources"].is_array()) {
    for (const auto& item : json["resources"].items()) {
      Resource res = resource_from(item);
      if (!res.name.empty()) manifest.resources.push_back(std::move(res));
    }
  }
  if (json["catalogs"].is_array()) {
    for (const auto& item : json["catalogs"].items()) {
      if (!item.is_object()) continue;
      CatalogDef catalog = catalog_from(item);
      if (catalog.type.empty() || catalog.id.empty()) continue;
      manifest.catalogs.push_back(std::move(catalog));
    }
  }

  // Infiere recursos a partir de los catálogos declarados.
  if (!manifest.catalogs.empty() && !manifest.has_resource("catalog")) {
    Resource res;
    res.name = "catalog";
    for (const auto& catalog : manifest.catalogs) {
      if (std::find(res.types.begin(), res.types.end(), catalog.type) == res.types.end()) {
        res.types.push_back(catalog.type);
      }
    }
    manifest.resources.push_back(std::move(res));
    result.warnings.push_back("El manifest no declaraba el recurso «catalog»; se ha añadido.");
  }

  // Tipos: declarados o inferidos de recursos y catálogos.
  if (manifest.types.empty()) {
    for (const auto& res : manifest.resources) {
      for (const auto& t : res.types) {
        if (std::find(manifest.types.begin(), manifest.types.end(), t) == manifest.types.end()) {
          manifest.types.push_back(t);
        }
      }
    }
    for (const auto& catalog : manifest.catalogs) {
      if (std::find(manifest.types.begin(), manifest.types.end(), catalog.type) == manifest.types.end()) {
        manifest.types.push_back(catalog.type);
      }
    }
  }
  dedupe_strings(manifest.types);

  if (manifest.id.empty()) {
    result.message = "Al manifest le falta el campo «id».";
    return result;
  }
  if (manifest.name.empty()) {
    result.message = "Al manifest le falta el campo «name».";
    return result;
  }
  if (manifest.resources.empty()) {
    result.message = "El manifest no declara ningún recurso (stream, catalog, meta…).";
    return result;
  }

  std::string transport;
  for (const char* key : {"transportUrl", "baseUrl", "url"}) {
    if (!json[key].string().empty()) {
      transport = json[key].string();
      break;
    }
  }
  if (!trim(base_url_override).empty()) transport = base_url_override;

  if (!transport.empty()) {
    manifest.manifest_url = transport;
    manifest.base_url = normalize_base_url(transport);
  } else {
    result.warnings.push_back(
        "Sin «transportUrl» el plugin no podrá entregar streams; instálalo desde su URL.");
  }

  for (const auto& res : manifest.resources) {
    if (res.name != "stream" && res.name != "catalog" && res.name != "meta" &&
        res.name != "subtitles") {
      result.warnings.push_back("Recurso no utilizado: " + res.name);
    }
  }
  if (manifest.has_resource("stream")) {
    const Resource* res = manifest.resource("stream");
    if (res && res->id_prefixes.empty() && manifest.id_prefixes.empty()) {
      result.warnings.push_back("Sin idPrefixes se probarán todos los identificadores.");
    }
  }

  result.ok = true;
  result.manifest = std::move(manifest);
  return result;
}

}  // namespace cfx

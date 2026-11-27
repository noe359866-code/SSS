#include "cineflux/js_plugin.hpp"

#include <algorithm>
#include <cctype>

#include "cineflux/errors.hpp"
#include "cineflux/manifest.hpp"

namespace cfx {
namespace {

std::string lower_ascii(std::string s) {
  std::transform(s.begin(), s.end(), s.begin(),
                 [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
  return s;
}

bool starts_with_http(const std::string& s) {
  std::string lo = lower_ascii(s);
  return lo.rfind("http://", 0) == 0 || lo.rfind("https://", 0) == 0;
}

std::string normalize_media_type(const std::string& raw) {
  std::string lo = lower_ascii(raw);
  if (lo == "movie" || lo == "movies" || lo == "film") return "movie";
  if (lo == "tv" || lo == "series" || lo == "show" || lo == "shows" || lo == "anime") {
    return "series";
  }
  return "";
}

std::string slugify(const std::string& text) {
  std::string out;
  for (unsigned char c : text) {
    if (std::isalnum(c)) {
      out.push_back(static_cast<char>(std::tolower(c)));
    } else if ((c == ' ' || c == '-' || c == '_' || c == '.') && !out.empty() &&
               out.back() != '-') {
      out.push_back('-');
    }
  }
  while (!out.empty() && out.back() == '-') out.pop_back();
  return out;
}

}  // namespace

Json JsPluginEntry::to_json() const {
  Json t = Json::array();
  for (const auto& item : types) t.push(item);
  Json out = Json::object();
  out.set("id", id);
  out.set("name", name);
  out.set("description", description);
  out.set("version", version);
  out.set("scriptUrl", script_url);
  out.set("types", std::move(t));
  out.set("enabledByDefault", enabled_by_default);
  return out;
}

Json JsRepoResult::to_json() const {
  Json arr = Json::array();
  for (const auto& p : plugins) arr.push(p.to_json());
  Json out = Json::object();
  out.set("ok", ok);
  out.set("name", name);
  out.set("version", version);
  out.set("baseUrl", base_url);
  out.set("plugins", std::move(arr));
  out.set("message", message);
  return out;
}

JsRepoResult parse_js_repo(const std::string& repo_json,
                           const std::string& base_url_override) {
  JsRepoResult result;
  Json root;
  if (!Json::parse(repo_json, root) || !root.is_object()) {
    result.message = user_message(Failure::Manifest);
    return result;
  }

  const Json* list = nullptr;
  if (root["scrapers"].is_array()) {
    list = &root["scrapers"];
  } else if (root["providers"].is_array()) {
    list = &root["providers"];
  } else if (root["plugins"].is_array()) {
    list = &root["plugins"];
  }

  if (!list || list->items().empty()) {
    result.message = "El repositorio no contiene plugins JS válidos.";
    return result;
  }

  std::string base = normalize_base_url(base_url_override);
  if (base.empty()) {
    std::string from_root = root["baseUrl"].string();
    if (from_root.empty()) from_root = root["url"].string();
    base = normalize_base_url(from_root);
  }

  result.name = root["name"].string("Repositorio JS");
  result.version = root["version"].string("1.0.0");
  result.base_url = base;

  for (const auto& item : list->items()) {
    if (!item.is_object()) continue;

    JsPluginEntry entry;
    entry.name = item["name"].string();
    entry.id = item["id"].string();
    entry.description = item["description"].string();
    entry.version = item["version"].string("1.0.0");
    entry.enabled_by_default = item["enabled"].boolean(true);

    std::string raw_url = item["url"].string();
    std::string filename = item["filename"].string();
    if (filename.empty()) filename = item["file"].string();
    if (filename.empty()) filename = item["script"].string();

    if (!raw_url.empty() && starts_with_http(raw_url)) {
      entry.script_url = raw_url;
    } else if (!filename.empty()) {
      if (starts_with_http(filename)) {
        entry.script_url = filename;
      } else if (!base.empty()) {
        entry.script_url = join_url(base, filename);
      }
    }

    if (entry.script_url.empty()) continue;

    if (entry.name.empty()) {
      entry.name = !entry.id.empty() ? entry.id : filename;
    }
    if (entry.id.empty()) {
      entry.id = slugify(entry.name);
    }
    if (entry.id.empty()) continue;

    const Json* types_arr = nullptr;
    if (item["supportedTypes"].is_array()) {
      types_arr = &item["supportedTypes"];
    } else if (item["types"].is_array()) {
      types_arr = &item["types"];
    }
    if (types_arr) {
      for (const auto& t : types_arr->items()) {
        std::string norm = normalize_media_type(t.string());
        if (!norm.empty() &&
            std::find(entry.types.begin(), entry.types.end(), norm) == entry.types.end()) {
          entry.types.push_back(norm);
        }
      }
    }
    if (entry.types.empty()) {
      entry.types = {"movie", "series"};
    }

    result.plugins.push_back(std::move(entry));
  }

  if (result.plugins.empty()) {
    result.message = "No se encontró ningún plugin JS ejecutable en el repositorio.";
    return result;
  }

  result.ok = true;
  return result;
}

Json normalize_js_streams(const Json& raw, const std::string& plugin_name) {
  Json out_streams = Json::array();

  const Json* source = nullptr;
  if (raw.is_array()) {
    source = &raw;
  } else if (raw.is_object() && raw["streams"].is_array()) {
    source = &raw["streams"];
  }

  if (!source) {
    Json out = Json::object();
    out.set("streams", std::move(out_streams));
    return out;
  }

  for (const auto& item : source->items()) {
    if (!item.is_object()) continue;

    std::string url = item["url"].string();
    if (url.empty()) url = item["streamUrl"].string();
    if (url.empty()) url = item["file"].string();
    if (url.empty()) continue;

    std::string name = item["name"].string();
    std::string quality = item["quality"].string();
    std::string title = item["title"].string();
    std::string size = item["size"].string();

    if (name.empty()) {
      name = !plugin_name.empty() ? plugin_name : "JS";
      if (!quality.empty()) name += " · " + quality;
    } else if (!quality.empty() &&
               lower_ascii(name).find(lower_ascii(quality)) == std::string::npos) {
      name += " · " + quality;
    }

    if (title.empty()) {
      title = name;
    }
    if (!size.empty() && title.find(size) == std::string::npos) {
      title += "\n" + size;
    }

    Json stream = Json::object();
    stream.set("url", url);
    stream.set("name", name);
    stream.set("title", title);

    // Propaga `headers` del formato Nuvio a `behaviorHints.proxyHeaders.request`
    Json hints = item["behaviorHints"].is_object() ? item["behaviorHints"] : Json::object();
    if (item["headers"].is_object() && !item["headers"].fields().empty()) {
      Json proxy = hints["proxyHeaders"].is_object() ? hints["proxyHeaders"] : Json::object();
      Json req = proxy["request"].is_object() ? proxy["request"] : Json::object();
      for (const auto& kv : item["headers"].fields()) {
        if (kv.second.is_string() && !kv.second.string().empty()) {
          req.set(kv.first, kv.second.string());
        }
      }
      proxy.set("request", std::move(req));
      hints.set("proxyHeaders", std::move(proxy));
    }
    if (!hints.fields().empty()) {
      stream.set("behaviorHints", std::move(hints));
    }

    if (item["subtitles"].is_array() && !item["subtitles"].items().empty()) {
      stream.set("subtitles", item["subtitles"]);
    }

    out_streams.push(std::move(stream));
  }

  Json out = Json::object();
  out.set("streams", std::move(out_streams));
  return out;
}

}  // namespace cfx

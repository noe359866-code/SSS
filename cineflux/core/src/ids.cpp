#include "cineflux/ids.hpp"

#include <algorithm>
#include <cctype>

namespace cfx {
namespace {

void add_unique(std::vector<std::string>& list, const std::string& value) {
  if (value.empty()) return;
  if (std::find(list.begin(), list.end(), value) == list.end()) list.push_back(value);
}

bool is_safe_url_char(unsigned char c) {
  return std::isalnum(c) != 0 || c == '-' || c == '_' || c == '.' || c == '~' || c == ':';
}

std::string trim(const std::string& value) {
  std::size_t begin = 0;
  std::size_t end = value.size();
  while (begin < end && std::isspace(static_cast<unsigned char>(value[begin]))) ++begin;
  while (end > begin && std::isspace(static_cast<unsigned char>(value[end - 1]))) --end;
  return value.substr(begin, end - begin);
}

}  // namespace

std::string url_encode(const std::string& value) {
  static const char* hex = "0123456789ABCDEF";
  std::string out;
  out.reserve(value.size());
  for (unsigned char c : value) {
    if (is_safe_url_char(c)) {
      out += static_cast<char>(c);
    } else {
      out += '%';
      out += hex[c >> 4];
      out += hex[c & 0x0F];
    }
  }
  return out;
}

bool looks_like_imdb_id(const std::string& id) {
  if (id.size() < 3 || id[0] != 't' || id[1] != 't') return false;
  for (std::size_t i = 2; i < id.size(); ++i) {
    if (!std::isdigit(static_cast<unsigned char>(id[i]))) return false;
  }
  return true;
}

std::vector<std::string> stream_id_candidates(const std::string& type,
                                              const std::string& imdb_id,
                                              const std::string& tmdb_id,
                                              int season, int episode) {
  std::vector<std::string> bases;
  const std::string imdb = trim(imdb_id);
  const std::string tmdb = trim(tmdb_id);
  if (!imdb.empty()) add_unique(bases, imdb);
  if (!tmdb.empty()) add_unique(bases, "tmdb:" + tmdb);

  std::vector<std::string> out;
  const bool episodic = (type == "series" || type == "tv") && season > 0 && episode > 0;
  if (episodic) {
    for (const auto& base : bases) {
      add_unique(out, base + ":" + std::to_string(season) + ":" + std::to_string(episode));
    }
  }
  for (const auto& base : bases) add_unique(out, base);
  return out;
}

std::vector<std::string> stream_ids_for(const Manifest& manifest, const std::string& type,
                                        const std::string& imdb_id, const std::string& tmdb_id,
                                        int season, int episode) {
  std::vector<std::string> all = stream_id_candidates(type, imdb_id, tmdb_id, season, episode);
  std::vector<std::string> filtered;
  for (const auto& id : all) {
    if (manifest.can_stream(type, id)) filtered.push_back(id);
  }
  // Plugins sin idPrefixes aceptan cualquier id: se devuelve la lista completa
  // para que el llamante pueda probar en orden.
  if (filtered.empty() && manifest.has_resource("stream") && manifest.supports_type(type)) {
    return all;
  }
  return filtered;
}

std::string manifest_url_for(const std::string& base_or_url) {
  const std::string value = trim(base_or_url);
  if (value.empty()) return value;
  if (value.size() >= 13 && value.compare(value.size() - 13, 13, "/manifest.json") == 0) {
    return value;
  }
  if (value.size() >= 13 && value.compare(value.size() - 13, 13, "manifest.json") == 0) {
    return value;
  }
  return join_url(normalize_base_url(value), "manifest.json");
}

std::string stream_url_for(const Manifest& manifest, const std::string& type,
                           const std::string& id) {
  return build_resource_url(manifest.base_url, "stream", url_encode(type), url_encode(id));
}

std::string catalog_url_for(const Manifest& manifest, const CatalogDef& catalog,
                            const std::string& search, const std::string& genre, int skip) {
  std::string extra;
  auto append = [&extra](const std::string& key, const std::string& value) {
    if (value.empty()) return;
    if (!extra.empty()) extra += "&";
    extra += url_encode(key) + "=" + url_encode(value);
  };
  if (!search.empty()) append("search", search);
  if (!genre.empty()) append("genre", genre);
  if (skip > 0) append("skip", std::to_string(skip));
  return build_resource_url(manifest.base_url, "catalog", url_encode(catalog.type),
                            url_encode(catalog.id), extra);
}

}  // namespace cfx

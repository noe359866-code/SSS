// Sonda ejecutable del núcleo: simula la respuesta de tres plugins, aplica un
// perfil, muestra la cola de reproducción y fusiona saltos de intro.
#include <iostream>
#include <string>

#include "cineflux/capi.h"
#include "cineflux/json.hpp"

int main() {
  const std::string manifest = R"({
    "id":"org.probe.streams","name":"Probe Streams","version":"1.0.0",
    "types":["movie"],"resources":["stream"],"idPrefixes":["tt"],
    "transportUrl":"https://plugins.example/manifest.json"
  })";

  const char* manifest_response = cfx_parse_manifest(manifest.c_str(), "");
  std::cout << "Manifest normalizado:\n" << manifest_response << "\n\n";
  cfx_free(const_cast<char*>(manifest_response));

  const char* profiles_response = cfx_default_profiles();
  cfx::Json profiles;
  cfx::Json::parse(profiles_response, profiles);
  std::cout << "Perfiles disponibles:\n";
  for (const auto& profile : profiles["profiles"].items()) {
    std::cout << "  · " << profile["name"].string() << "  [" << profile["id"].string() << "]\n";
  }
  cfx_free(const_cast<char*>(profiles_response));

  cfx::Json request = cfx::Json::object();
  request.set("profile", profiles["profiles"][0]);

  cfx::Json addons = cfx::Json::array();
  auto addon = [](const std::string& id, const std::string& name, int priority,
                  const std::string& payload) {
    cfx::Json item = cfx::Json::object();
    item.set("id", id);
    item.set("name", name);
    item.set("priority", priority);
    cfx::Json parsed;
    cfx::Json::parse(payload, parsed);
    item.set("payload", parsed);
    return item;
  };
  addons.push(addon("alfa", "Alfa", 0,
                    R"({"streams":[
                      {"name":"Latino 1080p","title":"1.9 GB","url":"https://cdn/alfa-1080.mp4"},
                      {"name":"Castellano 1080p","url":"https://cdn/alfa-cast.mp4"}
                    ]})"));
  addons.push(addon("beta", "Beta", 1,
                    R"({"streams":[
                      {"name":"Latino 4K HDR","url":"https://cdn/beta-4k.mp4"},
                      {"name":"Latino CAM","url":"https://cdn/beta-cam.mp4"}
                    ]})"));
  addons.push(addon("gamma", "Gamma", 2,
                    R"({"streams":[
                      {"name":"Latino 720p","url":"https://cdn/gamma-720.m3u8"},
                      {"infoHash":"abcdef","name":"Latino 1080p torrent"}
                    ]})"));
  request.set("addons", addons);

  cfx::Json options = cfx::Json::object();
  options.set("limit", 4);
  request.set("options", options);

  const char* ranked = cfx_rank_streams(request.dump().c_str());
  cfx::Json result;
  cfx::Json::parse(ranked, result);
  cfx_free(const_cast<char*>(ranked));

  std::cout << "\nCola de reproducción para «Latino 1080p»:\n";
  int index = 1;
  for (const auto& candidate : result["candidates"].items()) {
    std::cout << "  " << index++ << ". " << candidate["qualityLabel"].string() << " · "
              << candidate["languageLabel"].string() << "  (score " << candidate["score"].integer()
              << ", " << candidate["role"].string() << ")\n     " << candidate["url"].string()
              << "\n";
  }
  std::cout << "\nRecibidos: " << result["received"].integer()
            << " · reproducibles: " << result["playable"].integer()
            << " · descartados: " << result["rejected"].integer() << "\n";
  if (!result["ok"].boolean()) {
    std::cout << "Mensaje de interfaz: " << result["message"].string() << "\n";
  }
    // Saltos: TheIntroDB + AniSkip para el mismo episodio.
  cfx::Json skip_request = cfx::Json::object();
  skip_request.set("durationMs", 1440000);
  cfx::Json sources = cfx::Json::array();
  auto source = [](const std::string& id, const std::string& payload) {
    cfx::Json item = cfx::Json::object();
    item.set("source", id);
    cfx::Json parsed;
    cfx::Json::parse(payload, parsed);
    item.set("payload", parsed);
    return item;
  };
  sources.push(source("theintrodb",
                      R"({"intro":[{"start_ms":12000,"end_ms":102000}],"credits":[{"start_ms":1330000,"end_ms":1440000}]})"));
  sources.push(source("aniskip",
                      R"({"results":[{"interval":{"startTime":1300,"endTime":1390},"skipType":"ed"}]})"));
  skip_request.set("sources", sources);

  const char* skip_response = cfx_skip_segments(skip_request.dump().c_str());
  std::cout << "\nSaltos combinados:\n" << skip_response << "\n";
  cfx_free(const_cast<char*>(skip_response));

  return 0;
}

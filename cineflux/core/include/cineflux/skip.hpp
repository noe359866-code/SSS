// Cineflux · saltos de intro, recap y créditos.
//
// Las apps solo descargan el JSON de cada servicio (TheIntroDB, IntroDB,
// AniSkip o Anime Skip) y lo entregan aquí junto con la duración conocida.
// El núcleo entiende cada formato, descarta datos imposibles, elige una
// fuente por tipo de salto y devuelve una lista limpia y ordenada.
#pragma once

#include <string>
#include <vector>

#include "cineflux/json.hpp"

namespace cfx {

enum class SkipKind { Intro, Recap, Credits, Preview };

struct SkipSegment {
  SkipKind kind = SkipKind::Intro;
  long long start_ms = 0;
  long long end_ms = 0;
  std::string source;   // theintrodb | introdb | aniskip | animeskip
  double confidence = -1.0;
};

struct SkipResult {
  bool ok = false;
  std::vector<SkipSegment> segments;
  std::string message;
};

const char* skip_kind_name(SkipKind kind);
std::string skip_label(SkipKind kind);

// request:
//   {"durationMs":1417160,
//    "priority":["theintrodb","introdb","aniskip","animeskip"],
//    "sources":[{"source":"introdb","payload":{...}}]}
SkipResult skip_segments_from_request(const Json& request);
Json skip_segments_to_json(const SkipResult& result);

}  // namespace cfx

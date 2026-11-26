#include "cineflux/skip.hpp"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <string>
#include <vector>

namespace cfx {
namespace {

std::string lower(std::string value) {
  std::transform(value.begin(), value.end(), value.begin(),
                 [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
  return value;
}

bool has_word(const std::string& text, const std::string& word) {
  return lower(text).find(word) != std::string::npos;
}

bool kind_from_text(const std::string& raw, SkipKind* out) {
  const std::string name = lower(raw);
  if (name.empty()) return false;
  if (name == "op" || name == "ed" || name == "mixed-op" || name == "mixed-ed") {
    *out = (name == "op" || name == "mixed-op") ? SkipKind::Intro : SkipKind::Credits;
    return true;
  }
  if (has_word(name, "recap")) {
    *out = SkipKind::Recap;
    return true;
  }
  if (has_word(name, "preview") || has_word(name, "next")) {
    *out = SkipKind::Preview;
    return true;
  }
  if (has_word(name, "intro") || has_word(name, "opening")) {
    *out = SkipKind::Intro;
    return true;
  }
  if (has_word(name, "credit") || has_word(name, "outro") || has_word(name, "ending")) {
    *out = SkipKind::Credits;
    return true;
  }
  return false;
}

bool number_at(const Json& object, const char* key, double* out) {
  const Json& value = object[key];
  if (!value.is_number()) return false;
  *out = value.number();
  return true;
}

// Lee un par inicio/fin en milisegundos desde los distintos nombres que usan
// los servicios (start_ms, startMs, start_sec, startSec…).
bool read_span(const Json& object, long long* start_ms, long long* end_ms) {
  if (!object.is_object()) return false;
  double start = 0;
  double end = 0;
  bool has_start = number_at(object, "startMs", &start) || number_at(object, "start_ms", &start);
  bool has_end = number_at(object, "endMs", &end) || number_at(object, "end_ms", &end);
  if (!has_start) {
    double seconds = 0;
    if (number_at(object, "startSec", &seconds) || number_at(object, "start_sec", &seconds) ||
        number_at(object, "startTime", &seconds)) {
      start = seconds * 1000.0;
      has_start = true;
    }
  }
  if (!has_end) {
    double seconds = 0;
    if (number_at(object, "endSec", &seconds) || number_at(object, "end_sec", &seconds) ||
        number_at(object, "endTime", &seconds)) {
      end = seconds * 1000.0;
      has_end = true;
    }
  }
  if (!has_start || !has_end) return false;
  *start_ms = static_cast<long long>(std::llround(start));
  *end_ms = static_cast<long long>(std::llround(end));
  return true;
}

void add_span(std::vector<SkipSegment>* out, SkipKind kind, const Json& object,
              const std::string& source) {
  long long start = 0;
  long long end = 0;
  if (!read_span(object, &start, &end)) return;
  SkipSegment segment;
  segment.kind = kind;
  segment.start_ms = start;
  segment.end_ms = end;
  segment.source = source;
  if (object["confidence"].is_number()) segment.confidence = object["confidence"].number();
  out->push_back(std::move(segment));
}

void parse_introdb(const Json& payload, std::vector<SkipSegment>* out) {
  struct Entry {
    const char* key;
    SkipKind kind;
  };
  static const Entry entries[] = {
      {"intro", SkipKind::Intro},
      {"recap", SkipKind::Recap},
      {"outro", SkipKind::Credits},
      {"post_credits", SkipKind::Credits},
  };
  for (const auto& entry : entries) {
    const Json& value = payload[entry.key];
    if (value.is_object()) add_span(out, entry.kind, value, "introdb");
  }
}

void parse_theintrodb(const Json& payload, std::vector<SkipSegment>* out) {
  struct Entry {
    const char* key;
    SkipKind kind;
  };
  static const Entry entries[] = {
      {"intro", SkipKind::Intro},
      {"recap", SkipKind::Recap},
      {"credits", SkipKind::Credits},
      {"outro", SkipKind::Credits},
      {"preview", SkipKind::Preview},
  };
  for (const auto& entry : entries) {
    const Json& value = payload[entry.key];
    if (value.is_array()) {
      for (const auto& item : value.items()) add_span(out, entry.kind, item, "theintrodb");
    } else if (value.is_object()) {
      add_span(out, entry.kind, value, "theintrodb");
    }
  }
}

void parse_aniskip(const Json& payload, std::vector<SkipSegment>* out) {
  for (const auto& item : payload["results"].items()) {
    const Json& interval = item["interval"];
    long long start = 0;
    long long end = 0;
    if (!read_span(interval, &start, &end)) continue;
    SkipKind kind = SkipKind::Intro;
    if (!kind_from_text(item["skipType"].string(), &kind)) continue;
    SkipSegment segment;
    segment.kind = kind;
    segment.start_ms = start;
    segment.end_ms = end;
    segment.source = "aniskip";
    out->push_back(std::move(segment));
  }
}

// Anime Skip solo marca el inicio de cada sección: el fin es el siguiente
// marcador. Los tramos de más de 10 minutos se consideran ruido.
void parse_animeskip(const Json& payload, long long duration_ms,
                     std::vector<SkipSegment>* out) {
  struct Mark {
    long long at_ms = 0;
    SkipKind kind = SkipKind::Intro;
    bool valid = false;
  };
  std::vector<Mark> marks;
  for (const auto& item : payload["timestamps"].items()) {
    const Json& at = item["at"];
    if (!at.is_number()) continue;
    const long long start = static_cast<long long>(std::llround(at.number() * 1000.0));
    SkipKind kind = SkipKind::Intro;
    if (!kind_from_text(item["type"]["name"].string(), &kind)) continue;
    Mark mark;
    mark.at_ms = start;
    mark.kind = kind;
    mark.valid = true;
    marks.push_back(mark);
  }
  std::sort(marks.begin(), marks.end(),
            [](const Mark& a, const Mark& b) { return a.at_ms < b.at_ms; });
  for (std::size_t i = 0; i < marks.size(); ++i) {
    if (!marks[i].valid || marks[i].kind == SkipKind::Preview) continue;
    long long end = duration_ms;
    for (std::size_t j = i + 1; j < marks.size(); ++j) {
      if (marks[j].at_ms > marks[i].at_ms) {
        end = marks[j].at_ms;
        break;
      }
    }
    if (end <= marks[i].at_ms) continue;
    if (duration_ms > 0 && end > duration_ms) end = duration_ms;
    if (end - marks[i].at_ms > 600000) continue;
    SkipSegment segment;
    segment.kind = marks[i].kind;
    segment.start_ms = marks[i].at_ms;
    segment.end_ms = end;
    segment.source = "animeskip";
    out->push_back(std::move(segment));
  }
}

// Descarta tramos invertidos, demasiado cortos o fuera de la duración real.
bool clean_segment(SkipSegment* segment, long long duration_ms) {
  if (duration_ms <= 0) return false;
  if (segment->start_ms < 0) segment->start_ms = 0;
  if (segment->start_ms >= duration_ms) return false;
  if (segment->end_ms > duration_ms) segment->end_ms = duration_ms;
  if (segment->end_ms <= segment->start_ms) return false;
  if (segment->end_ms - segment->start_ms < 5000) return false;
  return true;
}

int priority_of(const std::vector<std::string>& priority, const std::string& source) {
  for (std::size_t i = 0; i < priority.size(); ++i) {
    if (priority[i] == source) return static_cast<int>(i);
  }
  return static_cast<int>(priority.size());
}

}  // namespace

const char* skip_kind_name(SkipKind kind) {
  switch (kind) {
    case SkipKind::Intro: return "intro";
    case SkipKind::Recap: return "recap";
    case SkipKind::Credits: return "credits";
    case SkipKind::Preview: return "preview";
  }
  return "intro";
}

std::string skip_label(SkipKind kind) {
  switch (kind) {
    case SkipKind::Intro: return "Saltar intro";
    case SkipKind::Recap: return "Saltar recap";
    case SkipKind::Credits: return "Saltar créditos";
    case SkipKind::Preview: return "Saltar avance";
  }
  return "Saltar";
}

SkipResult skip_segments_from_request(const Json& request) {
  SkipResult result;
  if (!request.is_object()) {
    result.message = "No hay datos de saltos disponibles.";
    return result;
  }

  const long long duration_ms =
      static_cast<long long>(request["durationMs"].number(0));
  if (duration_ms <= 0) {
    result.message = "Todavía no conocemos la duración de este contenido.";
    return result;
  }

  std::vector<std::string> priority;
  for (const auto& item : request["priority"].items()) {
    if (item.is_string() && !item.string().empty()) priority.push_back(item.string());
  }
  if (priority.empty()) {
    priority = {"theintrodb", "introdb", "aniskip", "animeskip"};
  }

  std::vector<SkipSegment> parsed;
  for (const auto& entry : request["sources"].items()) {
    if (!entry.is_object()) continue;
    const std::string source = entry["source"].string();
    if (source.empty()) continue;
    Json payload = entry["payload"];
    if (payload.is_string()) {
      Json decoded;
      if (Json::parse(payload.string(), decoded)) payload = std::move(decoded);
    }
    if (!payload.is_object()) continue;

    if (source == "introdb") {
      parse_introdb(payload, &parsed);
    } else if (source == "theintrodb") {
      parse_theintrodb(payload, &parsed);
    } else if (source == "aniskip") {
      parse_aniskip(payload, &parsed);
    } else if (source == "animeskip") {
      parse_animeskip(payload, duration_ms, &parsed);
    }
  }

  std::vector<SkipSegment> valid;
  valid.reserve(parsed.size());
  for (auto& segment : parsed) {
    if (clean_segment(&segment, duration_ms)) valid.push_back(segment);
  }

  static const SkipKind kinds[] = {SkipKind::Intro, SkipKind::Recap, SkipKind::Credits};
  for (SkipKind kind : kinds) {
    const SkipSegment* best = nullptr;
    int best_priority = 1 << 20;
    for (const auto& segment : valid) {
      if (segment.kind != kind) continue;
      if (segment.end_ms <= segment.start_ms) continue;
      const int rank = priority_of(priority, segment.source);
      if (rank < best_priority ||
          (rank == best_priority && best != nullptr && segment.start_ms < best->start_ms)) {
        best = &segment;
        best_priority = rank;
      }
    }
    if (best) result.segments.push_back(*best);
  }

  std::sort(result.segments.begin(), result.segments.end(),
            [](const SkipSegment& a, const SkipSegment& b) {
              return a.start_ms < b.start_ms;
            });
  result.ok = true;
  return result;
}

Json skip_segments_to_json(const SkipResult& result) {
  Json out = Json::object();
  out.set("ok", result.ok);
  out.set("count", static_cast<double>(result.segments.size()));
  out.set("message", result.message);
  Json segments = Json::array();
  for (const auto& segment : result.segments) {
    Json item = Json::object();
    item.set("kind", skip_kind_name(segment.kind));
    item.set("label", skip_label(segment.kind));
    item.set("startMs", static_cast<double>(segment.start_ms));
    item.set("endMs", static_cast<double>(segment.end_ms));
    item.set("source", segment.source);
    if (segment.confidence >= 0) item.set("confidence", segment.confidence);
    segments.push(std::move(item));
  }
  out.set("segments", std::move(segments));
  return out;
}

}  // namespace cfx

// Mensajes para la interfaz.
//
// Regla del producto: la app nunca muestra códigos de estado ni errores de
// fuentes (403, 404, 500, "HTTP", URLs roscas…). Todo fallo se traduce a un
// mensaje humano y, en reproducción, se resuelve cambiando de opción en
// silencio. `scrub_user_text` es la red de seguridad: se aplica a cualquier
// texto que vaya a la pantalla.
#pragma once

#include <algorithm>
#include <cctype>
#include <string>
#include <vector>

namespace cfx {

enum class Failure {
  None,
  Network,
  Timeout,
  Manifest,
  NoStreams,
  Playback,
  PlaybackExhausted,
  NotFound,
  Unsupported,
};

inline const char* user_message(Failure failure) {
  switch (failure) {
    case Failure::None:
      return "";
    case Failure::Network:
      return "No hemos podido conectar con el servicio. Inténtalo de nuevo en un momento.";
    case Failure::Timeout:
      return "La búsqueda está tardando más de lo normal. Prueba otra vez.";
    case Failure::Manifest:
      return "Ese manifest no es válido o está incompleto.";
    case Failure::NoStreams:
      return "No hay ninguna opción disponible ahora mismo para este perfil.";
    case Failure::Playback:
      return "No hemos podido iniciar la reproducción. Probamos otra opción.";
    case Failure::PlaybackExhausted:
      return "No encontramos una opción compatible. Prueba con otro perfil.";
    case Failure::NotFound:
      return "No encontramos ese título.";
    case Failure::Unsupported:
      return "Este contenido todavía no está disponible para tu perfil.";
  }
  return "Algo no ha salido como esperábamos. Inténtalo de nuevo.";
}

// Elimina cualquier rastro técnico de un texto mostrable.
inline std::string scrub_user_text(const std::string& text) {
  static const std::vector<std::string> banned = {
      "HTTP", "https://", "http://", "CORS", "ECONN", "ERR_", "status",
      "401", "403", "404", "408", "429", "500", "502", "503", "504",
      "javascript:", "undefined", "null",
  };
  std::string out = text;
  for (const auto& token : banned) {
    std::size_t pos = 0;
    while ((pos = out.find(token, pos)) != std::string::npos) {
      out.erase(pos, token.size());
    }
  }
  // Compacta espacios repetidos que puedan quedar tras el recorte.
  std::string compact;
  compact.reserve(out.size());
  bool last_space = false;
  for (char c : out) {
    const bool space = std::isspace(static_cast<unsigned char>(c)) != 0;
    if (space && last_space) continue;
    compact += space ? ' ' : c;
    last_space = space;
  }
  // Recorta espacios de los extremos.
  std::size_t begin = 0;
  std::size_t end = compact.size();
  while (begin < end && compact[begin] == ' ') ++begin;
  while (end > begin && compact[end - 1] == ' ') --end;
  return compact.substr(begin, end - begin);
}

// Los títulos internos de un stream pueden traer rutas, nombres de addons o
// códigos. Para la interfaz solo hace falta la etiqueta de calidad/idioma.
inline std::string safe_label(const std::string& text) {
  if (text.empty()) return "";
  std::string cleaned;
  cleaned.reserve(text.size());
  bool last_space = false;
  for (char c : text) {
    const bool space = std::isspace(static_cast<unsigned char>(c)) != 0;
    if (space && last_space) continue;
    cleaned += space ? ' ' : c;
    last_space = space;
  }
  return scrub_user_text(cleaned);
}

}  // namespace cfx

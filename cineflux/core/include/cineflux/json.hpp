// Cineflux · núcleo compartido C++17
// Parser/serializador JSON mínimo, sin dependencias externas.
// Se usa tanto en Android (JNI) como en iOS (C ABI) para leer manifests,
// respuestas de addons y describir candidatos de reproducción.
#pragma once

#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <map>
#include <string>
#include <vector>

namespace cfx {

class Json {
 public:
  enum class Type { Null, Bool, Number, String, Array, Object };

  Json() = default;
  Json(bool v) : type_(Type::Bool), bool_(v) {}
  Json(double v) : type_(Type::Number), number_(v) {}
  Json(int v) : type_(Type::Number), number_(static_cast<double>(v)) {}
  Json(long long v) : type_(Type::Number), number_(static_cast<double>(v)) {}
  Json(const char* v) : type_(Type::String), string_(v ? v : "") {}
  Json(std::string v) : type_(Type::String), string_(std::move(v)) {}

  static Json array() {
    Json j;
    j.type_ = Type::Array;
    return j;
  }
  static Json object() {
    Json j;
    j.type_ = Type::Object;
    return j;
  }

  Type type() const { return type_; }
  bool is_null() const { return type_ == Type::Null; }
  bool is_bool() const { return type_ == Type::Bool; }
  bool is_number() const { return type_ == Type::Number; }
  bool is_string() const { return type_ == Type::String; }
  bool is_array() const { return type_ == Type::Array; }
  bool is_object() const { return type_ == Type::Object; }

  bool boolean(bool fallback = false) const {
    return type_ == Type::Bool ? bool_ : fallback;
  }
  double number(double fallback = 0.0) const {
    return type_ == Type::Number ? number_ : fallback;
  }
  int integer(int fallback = 0) const {
    return type_ == Type::Number ? static_cast<int>(number_) : fallback;
  }
  std::string string(const std::string& fallback = "") const {
    return type_ == Type::String ? string_ : fallback;
  }

  bool has(const std::string& key) const {
    if (type_ != Type::Object) return false;
    return fields_.find(key) != fields_.end();
  }
  const Json& at(const std::string& key) const {
    if (type_ != Type::Object) return null_value();
    auto it = fields_.find(key);
    return it == fields_.end() ? null_value() : it->second;
  }
  const std::vector<Json>& items() const { return items_; }
  const std::map<std::string, Json>& fields() const { return fields_; }

  void set(const std::string& key, Json value) {
    if (type_ != Type::Object) {
      type_ = Type::Object;
      fields_.clear();
    }
    fields_[key] = std::move(value);
  }
  void push(Json value) {
    if (type_ != Type::Array) {
      type_ = Type::Array;
      items_.clear();
    }
    items_.push_back(std::move(value));
  }

  const Json& operator[](const std::string& key) const { return at(key); }
  const Json& operator[](std::size_t index) const {
    return index < items_.size() ? items_[index] : null_value();
  }

  // Acceso mutable explícito (no se usa operator[] para no crear claves al leer).
  Json& at_mut(const std::string& key) { return fields_[key]; }
  Json& at_mut(std::size_t index) {
    if (index >= items_.size()) items_.resize(index + 1);
    return items_[index];
  }
  std::size_t size() const {
    if (type_ == Type::Array) return items_.size();
    if (type_ == Type::Object) return fields_.size();
    return 0;
  }

  std::string dump() const {
    std::string out;
    dump_into(out);
    return out;
  }

  // Devuelve false y rellena `error` si el texto no es JSON válido.
  static bool parse(const std::string& text, Json& out, std::string* error = nullptr) {
    Parser parser(text);
    Json value;
    std::string perr;
    if (!parser.parse_value(value, perr)) {
      if (error) *error = perr;
      return false;
    }
    parser.skip_space();
    if (!parser.eof()) {
      if (error) *error = "contenido adicional al final del JSON";
      return false;
    }
    out = std::move(value);
    return true;
  }

 private:
  static const Json& null_value() {
    static const Json kNull;
    return kNull;
  }

  static void escape_into(const std::string& in, std::string& out) {
    out += '"';
    for (unsigned char c : in) {
      switch (c) {
        case '"': out += "\\\""; break;
        case '\\': out += "\\\\"; break;
        case '\b': out += "\\b"; break;
        case '\f': out += "\\f"; break;
        case '\n': out += "\\n"; break;
        case '\r': out += "\\r"; break;
        case '\t': out += "\\t"; break;
        default:
          if (c < 0x20) {
            char buf[8];
            std::snprintf(buf, sizeof(buf), "\\u%04x", c);
            out += buf;
          } else {
            out += static_cast<char>(c);
          }
      }
    }
    out += '"';
  }

  void dump_into(std::string& out) const {
    switch (type_) {
      case Type::Null: out += "null"; break;
      case Type::Bool: out += bool_ ? "true" : "false"; break;
      case Type::Number: {
        if (number_ == static_cast<double>(static_cast<long long>(number_)) &&
            std::fabs(number_) < 1e15) {
          out += std::to_string(static_cast<long long>(number_));
        } else {
          char buf[40];
          std::snprintf(buf, sizeof(buf), "%.10g", number_);
          out += buf;
        }
        break;
      }
      case Type::String: escape_into(string_, out); break;
      case Type::Array: {
        out += '[';
        for (std::size_t i = 0; i < items_.size(); ++i) {
          if (i) out += ',';
          items_[i].dump_into(out);
        }
        out += ']';
        break;
      }
      case Type::Object: {
        out += '{';
        bool first = true;
        for (const auto& entry : fields_) {
          if (!first) out += ',';
          first = false;
          escape_into(entry.first, out);
          out += ':';
          entry.second.dump_into(out);
        }
        out += '}';
        break;
      }
    }
  }

  class Parser {
   public:
    explicit Parser(const std::string& text) : text_(text) {}

    bool eof() const { return pos_ >= text_.size(); }
    void skip_space() {
      while (pos_ < text_.size()) {
        char c = text_[pos_];
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
          ++pos_;
        } else {
          break;
        }
      }
    }

    bool parse_value(Json& out, std::string& err, int depth = 0) {
      if (depth > 64) {
        err = "profundidad excesiva";
        return false;
      }
      skip_space();
      if (eof()) {
        err = "JSON vacío";
        return false;
      }
      char c = text_[pos_];
      if (c == '{') return parse_object(out, err, depth);
      if (c == '[') return parse_array(out, err, depth);
      if (c == '"') {
        std::string s;
        if (!parse_string(s, err)) return false;
        out = Json(std::move(s));
        return true;
      }
      if (c == 't') {
        if (!consume("true")) return fail(err, "literal inválido");
        out = Json(true);
        return true;
      }
      if (c == 'f') {
        if (!consume("false")) return fail(err, "literal inválido");
        out = Json(false);
        return true;
      }
      if (c == 'n') {
        if (!consume("null")) return fail(err, "literal inválido");
        out = Json();
        return true;
      }
      return parse_number(out, err);
    }

   private:
    static bool fail(std::string& err, const char* message) {
      err = message;
      return false;
    }

    bool consume(const char* literal) {
      std::size_t len = std::char_traits<char>::length(literal);
      if (text_.compare(pos_, len, literal) != 0) return false;
      pos_ += len;
      return true;
    }

    bool parse_object(Json& out, std::string& err, int depth) {
      ++pos_;  // {
      Json obj = Json::object();
      skip_space();
      if (pos_ < text_.size() && text_[pos_] == '}') {
        ++pos_;
        out = std::move(obj);
        return true;
      }
      while (true) {
        skip_space();
        if (pos_ >= text_.size() || text_[pos_] != '"') {
          return fail(err, "se esperaba una clave");
        }
        std::string key;
        if (!parse_string(key, err)) return false;
        skip_space();
        if (pos_ >= text_.size() || text_[pos_] != ':') {
          return fail(err, "se esperaba ':'");
        }
        ++pos_;
        Json value;
        if (!parse_value(value, err, depth + 1)) return false;
        obj.set(key, std::move(value));
        skip_space();
        if (pos_ >= text_.size()) return fail(err, "objeto sin cerrar");
        if (text_[pos_] == ',') {
          ++pos_;
          continue;
        }
        if (text_[pos_] == '}') {
          ++pos_;
          out = std::move(obj);
          return true;
        }
        return fail(err, "se esperaba ',' o '}'");
      }
    }

    bool parse_array(Json& out, std::string& err, int depth) {
      ++pos_;  // [
      Json arr = Json::array();
      skip_space();
      if (pos_ < text_.size() && text_[pos_] == ']') {
        ++pos_;
        out = std::move(arr);
        return true;
      }
      while (true) {
        Json value;
        if (!parse_value(value, err, depth + 1)) return false;
        arr.push(std::move(value));
        skip_space();
        if (pos_ >= text_.size()) return fail(err, "array sin cerrar");
        if (text_[pos_] == ',') {
          ++pos_;
          continue;
        }
        if (text_[pos_] == ']') {
          ++pos_;
          out = std::move(arr);
          return true;
        }
        return fail(err, "se esperaba ',' o ']'");
      }
    }

    static void append_utf8(std::string& out, unsigned int cp) {
      if (cp <= 0x7F) {
        out += static_cast<char>(cp);
      } else if (cp <= 0x7FF) {
        out += static_cast<char>(0xC0 | (cp >> 6));
        out += static_cast<char>(0x80 | (cp & 0x3F));
      } else if (cp <= 0xFFFF) {
        out += static_cast<char>(0xE0 | (cp >> 12));
        out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
        out += static_cast<char>(0x80 | (cp & 0x3F));
      } else {
        out += static_cast<char>(0xF0 | (cp >> 18));
        out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
        out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
        out += static_cast<char>(0x80 | (cp & 0x3F));
      }
    }

    bool parse_hex4(unsigned int& out) {
      if (pos_ + 4 > text_.size()) return false;
      out = 0;
      for (int i = 0; i < 4; ++i) {
        char c = text_[pos_ + i];
        out <<= 4;
        if (c >= '0' && c <= '9') {
          out |= static_cast<unsigned int>(c - '0');
        } else if (c >= 'a' && c <= 'f') {
          out |= static_cast<unsigned int>(c - 'a' + 10);
        } else if (c >= 'A' && c <= 'F') {
          out |= static_cast<unsigned int>(c - 'A' + 10);
        } else {
          return false;
        }
      }
      pos_ += 4;
      return true;
    }

    bool parse_string(std::string& out, std::string& err) {
      ++pos_;  // "
      out.clear();
      while (pos_ < text_.size()) {
        unsigned char c = static_cast<unsigned char>(text_[pos_]);
        if (c == '"') {
          ++pos_;
          return true;
        }
        if (c == '\\') {
          ++pos_;
          if (pos_ >= text_.size()) return fail(err, "escape incompleto");
          char esc = text_[pos_++];
          switch (esc) {
            case '"': out += '"'; break;
            case '\\': out += '\\'; break;
            case '/': out += '/'; break;
            case 'b': out += '\b'; break;
            case 'f': out += '\f'; break;
            case 'n': out += '\n'; break;
            case 'r': out += '\r'; break;
            case 't': out += '\t'; break;
            case 'u': {
              unsigned int cp = 0;
              if (!parse_hex4(cp)) return fail(err, "\\u inválido");
              if (cp >= 0xD800 && cp <= 0xDBFF && pos_ + 6 <= text_.size() &&
                  text_[pos_] == '\\' && text_[pos_ + 1] == 'u') {
                pos_ += 2;
                unsigned int low = 0;
                if (!parse_hex4(low) || low < 0xDC00 || low > 0xDFFF) {
                  return fail(err, "par suplente inválido");
                }
                cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
              }
              append_utf8(out, cp);
              break;
            }
            default: return fail(err, "escape desconocido");
          }
          continue;
        }
        if (c < 0x20) return fail(err, "carácter de control en la cadena");
        out += static_cast<char>(c);
        ++pos_;
      }
      return fail(err, "cadena sin cerrar");
    }

    bool parse_number(Json& out, std::string& err) {
      std::size_t start = pos_;
      if (pos_ < text_.size() && (text_[pos_] == '-' || text_[pos_] == '+')) ++pos_;
      bool digits = false;
      while (pos_ < text_.size() && std::isdigit(static_cast<unsigned char>(text_[pos_]))) {
        ++pos_;
        digits = true;
      }
      if (pos_ < text_.size() && text_[pos_] == '.') {
        ++pos_;
        while (pos_ < text_.size() && std::isdigit(static_cast<unsigned char>(text_[pos_]))) {
          ++pos_;
          digits = true;
        }
      }
      if (digits && pos_ < text_.size() && (text_[pos_] == 'e' || text_[pos_] == 'E')) {
        ++pos_;
        if (pos_ < text_.size() && (text_[pos_] == '-' || text_[pos_] == '+')) ++pos_;
        bool exp_digits = false;
        while (pos_ < text_.size() && std::isdigit(static_cast<unsigned char>(text_[pos_]))) {
          ++pos_;
          exp_digits = true;
        }
        if (!exp_digits) return fail(err, "exponente inválido");
      }
      if (!digits) return fail(err, "se esperaba un valor");
      try {
        out = Json(std::stod(text_.substr(start, pos_ - start)));
      } catch (...) {
        return fail(err, "número inválido");
      }
      return true;
    }

    const std::string& text_;
    std::size_t pos_ = 0;
  };

  Type type_ = Type::Null;
  bool bool_ = false;
  double number_ = 0;
  std::string string_;
  std::vector<Json> items_;
  std::map<std::string, Json> fields_;
};

}  // namespace cfx

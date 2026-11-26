/*
 * Cineflux · API C del núcleo compartido.
 *
 * Superficie única para Android (JNI) e iOS (Swift). Todas las funciones que
 * devuelven `const char*` entregan JSON en UTF-8 asignado con malloc: el
 * llamante debe liberarlo con cfx_free(). Las respuestas siempre siguen el
 * formato {"ok": bool, ...} y los mensajes están limpios de códigos técnicos.
 */
#ifndef CINEFLUX_CAPI_H
#define CINEFLUX_CAPI_H

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#define CFX_EXPORT __declspec(dllexport)
#else
#define CFX_EXPORT __attribute__((visibility("default")))
#endif

/* Versión del núcleo, por ejemplo "1.0.0". Cadena estática: no liberar. */
CFX_EXPORT const char* cfx_version(void);

/* Perfiles de reproducción por defecto: {"ok":true,"profiles":[...]} */
CFX_EXPORT const char* cfx_default_profiles(void);

/*
 * Valida un manifest (JSON en crudo o URL) y lo normaliza.
 *   {"ok":true,"manifest":{...},"warnings":[...]}
 *   {"ok":false,"message":"..."}
 */
CFX_EXPORT const char* cfx_parse_manifest(const char* manifest_json_or_url,
                                          const char* base_url_override);

/*
 * Planifica las peticiones a los plugins antes de pedir streams.
 * Entrada:
 *   {"type":"movie|series","imdbId":"tt123","tmdbId":"550",
 *    "season":1,"episode":2,
 *    "addons":[{"id":"a","manifest":{...}}]}
 * Salida:
 *   {"ok":true,"addons":[{"id":"a","ids":["..."],"urls":["..."]}]}
 */
CFX_EXPORT const char* cfx_plan_requests(const char* request_json);

/*
 * Normaliza, deduplica y ordena los streams recibidos según el perfil.
 * Entrada:
 *   {"profile":{...},
 *    "addons":[{"id":"a","name":"A","priority":0,"payload":{...}}],
 *    "options":{"allowTorrents":false,"limit":12}}
 * Salida:
 *   {"ok":true,"candidates":[{...,"role":"primary|backup"}],
 *    "received":37,"playable":21,"rejected":16,"message":""}
 */
CFX_EXPORT const char* cfx_rank_streams(const char* request_json);

/*
 * Une los saltos de intro, recap y créditos de varios servicios.
 * Entrada:
 *   {"durationMs":1417160,
 *    "priority":["theintrodb","introdb","aniskip","animeskip"],
 *    "sources":[{"source":"introdb","payload":{...}},
 *               {"source":"theintrodb","payload":{...}},
 *               {"source":"aniskip","payload":{...}},
 *               {"source":"animeskip","payload":{...}}]}
 * Salida:
 *   {"ok":true,"count":2,"segments":[
 *      {"kind":"intro","label":"Saltar intro","startMs":2500,"endMs":58000,
 *       "source":"introdb","confidence":0.92}]}
 * Los payloads pueden venir como objeto o como texto JSON. Los tramos
 * imposibles se descartan en silencio.
 */
CFX_EXPORT const char* cfx_skip_segments(const char* request_json);

/*
 * Planifica filas de catálogo a partir de manifests de addons (Nuvio,
 * Cinemeta o cualquier manifest compatible).
 * Entrada:
 *   {"types":["movie","series"],"query":"","limit":12,"perSource":12,
 *    "sources":[{"baseUrl":"https://catalog.nuvio.tv","manifest":{...}}]}
 * Salida:
 *   {"ok":true,"count":2,"rows":[
 *      {"addonId":"...","addonName":"...","type":"movie","catalogId":"...",
 *       "name":"Trending Movies","url":"https://…/catalog/movie/….json",
 *       "search":false}]}
 * Con `query` solo se planifican catálogos que admiten búsqueda.
 */
CFX_EXPORT const char* cfx_plan_catalog(const char* request_json);

/* Libera una cadena devuelta por la API. */
CFX_EXPORT void cfx_free(char* pointer);

#ifdef __cplusplus
}
#endif

#endif /* CINEFLUX_CAPI_H */

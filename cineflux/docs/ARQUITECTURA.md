# Arquitectura de Cineflux

Cineflux es una app móvil con **tres capas** y una regla: toda la lógica de
plugins, perfiles, ranking y saltos vive en un **núcleo C++17** que comparten
Android e iOS. Las capas nativas solo hacen interfaz, red y reproducción.

La dirección visual combina **Apple TV** (héroe editorial, tipografía grande,
botones blancos, saltos con cristal) y **Netflix** (negro absoluto, carruseles,
rojo de acento). No existe modo demo: sin fuentes de catálogo la app pide configuración.

```
┌──────────────────────────────┐   ┌──────────────────────────────┐
│ Android · Kotlin + Compose   │   │ iOS · Swift + SwiftUI        │
│  UI · Media3 · OkHttp        │   │  UI · AVPlayer · URLSession  │
└──────────────┬───────────────┘   └──────────────┬───────────────┘
               │ JNI                              │ API C + SwiftPM
               └───────────────┬──────────────────┘
                               ▼
                 ┌────────────────────────────┐
                 │ Núcleo C++17 (core/)       │
                 │  manifest · ids · plan     │
                 │  perfil · ranking · JSON   │
                 └────────────────────────────┘
                               ▲
                               │ HTTP
                 ┌─────────────┴──────────────┐
                 │ Plugins (manifests)        │
                 │ Nuvio · Cinemeta · OMDb       │
                 └────────────────────────────┘
```

## Capas

| Capa | Android | iOS | Responsabilidad |
|---|---|---|---|
| UI | `ui/` (Compose) | `Views/` (SwiftUI) | Pantallas, navegación, ajustes |
| Datos | `data/` | `Data/` | Persistencia, HTTP, repositorios |
| Catálogo | `data/catalog/` | `Catalog/` | Nuvio/Cinemeta, RT y caché |
| Saltos | `data/SkipTimesRepository.kt` | `Data/SkipTimes.swift` | TheIntroDB, IntroDB, AniSkip, Anime Skip |
| Reproductor | `player/` | `Player/` | AVPlayer / Media3, failover |
| Núcleo | `core/` + JNI | `core/` + SwiftPM | Manifest, plan, ranking, perfiles |

## API del núcleo

En `core/include/cineflux/capi.h`. Todas las funciones devuelven JSON UTF-8
asignado con `malloc`; el llamante lo libera con `cfx_free`.

| Función | Entrada | Salida |
|---|---|---|
| `cfx_version` | — | cadena de versión |
| `cfx_default_profiles` | — | `{"ok":true,"profiles":[…]}` |
| `cfx_parse_manifest` | manifest JSON o URL + base opcional | `{"ok":true,"manifest":{…},"warnings":[…]}` |
| `cfx_plan_requests` | `{type,imdbId,tmdbId,season,episode,addons:[…]}` | `{"ok":true,"addons":[{id,name,ids,urls}]}` |
| `cfx_rank_streams` | `{profile,addons:[payload],options}` | `{"ok":true,"candidates":[…],"playable":n}` |
| `cfx_skip_segments` | `{durationMs,priority,sources:[{source,payload}]}` | `{"ok":true,"count":n,"segments":[…]}` |
| `cfx_plan_catalog` | `{types,query,limit,perSource,sources:[{baseUrl,manifest}]}` | `{"ok":true,"count":n,"rows":[{url,name,…}]}` |

Android entra por `app.cineflux.core.NativeCore` (`external fun`), iOS por
`CinefluxCore` (módulo SwiftPM con `module.modulemap` en
`include/cineflux`). Las firmas nativas son idénticas:

```kotlin
external fun version(): String
external fun defaultProfiles(): String
external fun parseManifest(input: String, baseUrl: String): String
external fun planRequests(requestJson: String): String
external fun rankStreams(requestJson: String): String
external fun skipSegments(requestJson: String): String
external fun planCatalog(requestJson: String): String
```

## Flujo de reproducción

1. La ficha entrega un `MediaItem` con el ID del addon (`tt…` o `tmdb:…`), que se
   traduce a IMDb/TMDB antes de pedir streams.
2. `plan` construye las URLs de cada plugin activo con recurso `stream` y sus
   prefijos de ID (`tt…` o `tmdb…`).
3. La app descarga los payloads **en paralelo** (`withTaskGroup` en iOS,
   `coroutineScope`/`async` en Android) con un timeout corto (≈12 s).
4. `rank` filtra y puntúa; devuelve hasta 12 candidatos, el primero con
   `role:"primary"` y el resto `"backup"`.
5. El reproductor reproduce el primero con una URL HTTP(S) directa.
6. **Failover silencioso**: si el player falla (`onPlayerError`, `AVPlayerItem.status ==
   .failed`, error de red) o lleva **más de 13 s** en buffering, se pasa al
   siguiente candidato. Al agotarse, mensaje humano ("No encontramos una opción
   compatible…") y botones de reintentar o cambiar de perfil.
7. Cada 10 s, con más de 15 s vistos, se guarda el progreso local.

Regla dura: **nunca** se muestran códigos (`403`, `404`, `CORS`, `ECONN`, etc.).
`Messages.scrub` los elimina incluso de mensajes que vengan del núcleo o de un
plugin.

## Ranking

`core/src/stream.cpp` aplica filtros duros y una suma de pesos:

- **Filtros**: sin URL, torrent (si el perfil no lo permite), CAM (si no se
  permite), idioma estricto sin coincidencia, calidad por encima del techo.
- **Pesos principales**: idioma +42 (penalización por orden), calidad +32 con
  distancia al objetivo, ahorro de datos, HDR, HEVC, subtítulos, entrega directa
  (+10) frente a páginas intersticiales (−14) y prioridad del plugin (−4 por
  puesto).
- Si nada encaja, hace **una pasada permisiva** subiendo el techo de calidad y
  aplicando −6, para que haya algo reproducible antes que una pantalla vacía.

El detalle completo, con valores y ejemplos, está en [PERFILES.md](PERFILES.md).

## Catálogo

El catálogo es una lista de **addons de catálogo** (manifests), igual que en un
cliente de addons. Por defecto:

| Fuente | Manifest | Aporta |
|---|---|---|
| **Nuvio** | `https://catalog.nuvio.tv/manifest.json` | filas TMDB (`trending-*`, `popular-*`, Netflix, Disney+…), ficha y episodios |
| **Cinemeta** | `https://v3-cinemeta.strem.io/manifest.json` | populares/mejor valoradas, ficha y episodios |

- El manifest se descarga y se valida con `cfx_parse_manifest`. Sus `catalogs`
  definen las filas; `cfx_plan_catalog` decide cuáles pedir, con qué URL y cuáles
  ignorar (extras obligatorios como `genre` o `lastVideosIds`).
- Cada fila se pide a `{base}/catalog/{type}/{id}.json`; la búsqueda usa
  `…/{id}/search={texto}.json` solo en catálogos que declaran el extra `search`.
- La ficha sale de `{base}/meta/{type}/{id}.json`: Nuvio primero (incluye `logo`,
  reparto, notas TMDB/IMDb y `videos`), Cinemeta como respaldo.
- **Rotten Tomatoes**: `RtClient`/`RtApi` combinan OMDb por ID de IMDb con la
  búsqueda pública de RT, normalizando títulos y eligiendo el año más próximo.
- **Caché**: home 10 min en memoria, manifests 30 min, detalle por sesión.
- **Sin fuentes**: no se inventan títulos. El inicio se convierte en una pantalla
  de configuración (héroe + CTA) hasta que haya datos reales.

Los endpoints, el mapeo de campos y la solución de problemas están en
[CATALOGO.md](CATALOGO.md).

## Persistencia

| Dato | Android | iOS |
|---|---|---|
| Ajustes | `SharedPreferences("cineflux.store", "settings.v1")` | `UserDefaults "cineflux.settings.v1"` |
| Plugins | `plugins.v1` | `cineflux.plugins.v1` |
| Perfiles | `profiles.v1` | `cineflux.profiles.v1` |
| Lista/progreso | `library.v1` | `cineflux.library.v1` |

La copia de seguridad es el mismo JSON en las dos plataformas:

```json
{"app":"cineflux","version":1,"settings":{…},"plugins":[…],"profiles":[…],"library":[…]}
```

## Red y seguridad

- HTTPS por defecto; Android permite `usesCleartextTraffic` y iOS declara
  `NSAllowsArbitraryLoads` porque muchos plugins sirven HLS por HTTP.
- Proxy opcional solo si la URL contiene `{url}` y solo tras un fallo directo.
- `User-Agent` configurable; Nuvio y Cinemeta no lo necesitan.
- Timeouts: 15 s conexión, 25 s total.

## Saltos de intro, recap y créditos

`core/src/skip.cpp` entiende los cuatro formatos y los unifica:

| Fuente | Consulta | Notas |
|---|---|---|
| TheIntroDB | `GET api.theintrodb.org/v4/media?imdb_id=…&season=&episode=` | arreglos `intro/recap/credits` con `start_ms/end_ms` |
| IntroDB | `GET api.introdb.app/segments?imdb_id=…&season=&episode=` | objetos `intro/recap/outro`; sin clave |
| AniSkip | `GET api.aniskip.com/v2/skip-times/{mal}/{ep}` | anime; MAL vía Jikan |
| Anime Skip | `POST api.anime-skip.com/graphql` | opcional, `X-Client-ID`; AniList vía GraphQL |

Reglas: se descartan tramos invertidos, menores de 5 s o fuera de la duración;
se recorta el final a la duración real; se elige **una fuente por tipo** según
`priority` (por defecto TheIntroDB → IntroDB → AniSkip → Anime Skip); Anime Skip
calcula el final con el siguiente marcador y descarta tramos de más de 10 min.
El resultado se cachea 7 días por título/temporada/episodio. La reproducción
muestra «Saltar intro/recap/créditos» y, si el automatismo está activo, salta sin
preguntar. Detalles en [SALTOS.md](SALTOS.md).

## Pruebas

```bash
cd core && make test   # 271 comprobaciones: manifest, ids, catálogos, plan, ranking y saltos
cd core && make probe  # plan, ranking y saltos legibles por consola
```

Los tests cubren manifests válidos/rotos, IDs IMDb/TMDB, estacionalidad,
puntuación por perfil, pasada permisiva, limpieza de mensajes, el planificador
de catálogos y los cuatro adaptadores de saltos.

# Peerflix Static · Gestor de Watchlist

Panel de administración **single-file** (`index.html`) para gestionar el archivo `watchlist.txt`
de **Peerflix Static** directamente desde el navegador, usando la **GitHub REST API** — sin backend
intermedio, sin build y sin dependencias de runtime (HTML + Tailwind CDN + JavaScript vanilla).

Al guardar, el archivo se actualiza en el repositorio vía `PUT /repos/{owner}/{repo}/contents/{path}`
y se dispara la GitHub Action que genera los sitios y feeds estáticos.

---

## Puesta en marcha

1. Abre `index.html` (local o desde cualquier hosting estático).
2. Rellena el panel **Ajustes**:
   | Campo | Ejemplo | Notas |
   |---|---|---|
   | Owner | `noe359866-code` | usuario u organización |
   | Repo | `SSS` | repositorio donde vive el watchlist |
   | Branch | `main` | rama a leer/escribir |
   | Path | `watchlist.txt` | se crea si no existe |
   | Token PAT | `github_pat_…` | *fine-grained* con **Contents: Read and write** |
3. Pulsa **Probar conexión** para validar repositorio, token y **las once fuentes** de metadatos.
4. Busca títulos, añádelos y pulsa **Guardar en GitHub** (o `Ctrl/⌘ + S`).

Las claves de las APIs de metadatos son **opcionales**: sin configurar nada ya funcionan
IMDb Suggest, imdbapi.dev, Cinemeta, TVMaze, Wikidata, AniList e IMDbOT.

## Formato de `watchlist.txt`

| Línea | Significado |
|---|---|
| `# comentario` | comentario: se conserva tal cual |
| `tt0111161 The Shawshank Redemption (1994)` | película / serie completa |
| `tt0944947:s3 Game of Thrones Temporada 3` | temporada completa |
| `tt0903747:s2:e5 Breaking Bad S02E05` | episodio concreto |
| `tt0133093` | solo ID IMDb (sin texto) |

La edición es **fiel al original**: se preservan comentarios, líneas en blanco, el orden y el texto
exacto de cada línea (incluidos acentos y `ñ`), y las líneas que no encajan en el formato se marcan
en rojo en lugar de borrarse.

### El `watchlist.txt` real del proyecto (solo IDs)

El archivo de producción es la plantilla de Peerflix Static: una cabecera de comentarios con la
documentación del formato y, debajo, **solo IDs** (`tt6933238`, `tt22526100`…, de 7 u 8 dígitos).

- Al cargarlo, la app resuelve **título, año, póster y tipo** con las fuentes activas para que la
  lista sea legible, pero **no toca el archivo**: las líneas que solo traían un ID se mantienen
  exactamente como estaban (`pinned`).
- El botón **«Escribir títulos»** (se activa con contador cuando hay líneas solo-ID) propone
  `tt6933238 La Última Frontera (2026)`, muestra el antes/después y solo las reescribe si lo
  confirmas. Los comentarios y el resto de líneas quedan intactos.
- Las líneas de temporada/episodio (`:s3`, `:s3:e4`) avisan si **no** hay clave de TMDB: según la
  documentación del propio archivo, la Action necesita `TMDB_API_KEY` en los *secrets* para
  expandirlas y, sin ella, las ignora con un aviso.

## Fuentes de metadatos

IMDb **no tiene API oficial**, así que el buscador combina endpoints públicos de IMDb con servicios
que exponen datos de IMDb y bases alternativas. La opción por defecto, **★ Todas las fuentes**,
consulta las que estén activas **en paralelo**, fusiona los duplicados, prioriza las coincidencias
confirmadas por varias fuentes y muestra el estado de cada una.

### Sin clave

| Fuente | Qué aporta |
|---|---|
| **IMDb Suggest** | autocompletado público de `imdb.com` (host `v3` con respaldo en `v2`) |
| **imdbapi.dev** | API REST/GraphQL libre sobre datos de IMDb: búsqueda y ficha |
| **Cinemeta (Stremio)** | metadatos, pósteres y **lista de episodios** por temporada |
| **TVMaze** | series y episodios (`/lookup/shows?imdb=`) |
| **Wikidata** | resuelve *título → ID de IMDb* mediante la propiedad P345 |
| **AniList** | anime (GraphQL), con enlaces externos a IMDb |
| **IMDbOT** | buscador comunitario de IMDb (experimental: puede caer sin aviso) |

### Con clave gratuita

| Fuente | Clave | Dónde obtenerla |
|---|---|---|
| **TMDB v3** | API key | <https://www.themoviedb.org/settings/api> |
| **OMDb** | API key | <https://www.omdbapi.com/apikey.aspx> |
| **Trakt** | `client_id` | <https://trakt.tv/oauth/applications> |
| **Simkl** | `client_id` | <https://simkl.com/settings/developer/> |

Cada fuente se puede **probar** y **desactivar** individualmente desde el panel «Fuentes de datos».

## Funciones

- **Buscador multifuente** con búsqueda simultánea, fusión por ID de IMDb (y por título+año cuando
  una fuente no trae ID), chips de estado por fuente, ranking por relevancia y filtro por tipo.
- **Resolución de IDs**: si una fuente no da el `tt…` (TMDB, Wikidata, AniList), se prueba
  TMDB `external_ids` → Wikidata → imdbapi.dev → Cinemeta → TVMaze → OMDb. TMDB devuelve a menudo
  títulos localizados, así que se ignoran acentos y signos antes de comparar.
- **Modal de temporada/episodio**: serie completa, temporada `:sN` o rango de episodios `:sN:eM`,
  con **nº de temporadas y de episodios por temporada** (Cinemeta/TVMaze/TMDB/OMDb), aviso si el
  rango supera lo disponible, vista previa exacta de las líneas y detección de duplicados.
- **Lista interactiva**: tarjeta por línea con póster, tipo, la línea literal que se escribirá,
  edición y borrado individual.
- **Vista previa en tiempo real** con numeración, resaltado de sintaxis y marcas de altas/bajas
  frente al último contenido leído de GitHub.
- **Alta manual** de IDs, líneas completas, URLs de IMDb/TMDB y comentarios, con enriquecimiento
  automático opcional.
- **Importar / descargar / copiar** el archivo y descartar cambios locales.
- **Errores explícitos** para 401 (token/clave), 403 (permisos o límite), 404 (ruta), 409 (sha
  desfasado), 422 (validación), fallos de red/CORS y *timeouts*, con avisos por *toast*.

## Seguridad

- El PAT se guarda en `localStorage` **solo si marcas «Recordar el token»**; en caso contrario queda
  únicamente en `sessionStorage` (se pierde al cerrar la pestaña).
- Todas las peticiones van directas del navegador a `api.github.com` y a las APIs de metadatos:
  no hay proxy, servidor propio ni analytics.
- Usa tokens *fine-grained* limitados al repositorio y al permiso **Contents: Read and write**.
- El botón **Borrar token guardado** elimina el token de inmediato.
- `window.PFX_STATE()` permite inspeccionar el estado desde la consola **sin exponer el token**.

## Atajos

| Atajo | Acción |
|---|---|
| `Ctrl/⌘ + S` | Guardar en GitHub |
| `/` | Enfocar el buscador |
| `Ctrl/⌘ + Enter` (en el cuadro manual) | Añadir las líneas |
| `Esc` | Cerrar el modal abierto |

## Pruebas

```bash
npm install     # instala jsdom (única dependencia, de desarrollo)
npm test        # 55 pruebas de núcleo + 54 de interfaz
```

- `tests/core.test.mjs` extrae el script de `index.html` y valida el parser/serializador
  (*round-trip* exacto, CRLF, Base64 UTF-8 con acentos, `ñ`, emoji y CJK), el diff, las
  estadísticas, la **fusión/ordenación de resultados multifuente** y el **archivo real del
  proyecto** (22 comentarios + 5 IDs pelados, byte a byte).
- `tests/ui.test.mjs` monta la página en jsdom con `fetch` simulado (GitHub y las **11 fuentes**) y
  recorre los flujos completos: configuración, carga del watchlist, búsqueda múltiple con fusión y
  ranking, fuente caída que no rompe la búsqueda, resolución de IDs sin `tt…`, modal
  temporada/episodio con episodios por temporada, duplicados, alta manual, guardado (verificando el
  Base64 y el `sha` enviados), conflictos 404/409, persistencia local y el **flujo completo sobre
  el watchlist real**: cargarlo sin modificar ni un byte, resolver sus títulos, escribir los nombres
  con confirmación previa y comprobar que el PUT conserva los 22 comentarios.

## Notas técnicas

- **Base64 UTF-8**: se codifica con `TextEncoder` + `btoa` por bloques (nunca `btoa(str)` directo) y
  se decodifica con `TextDecoder`, para no romper acentos ni `ñ` en la Contents API.
- **`sha` y conflictos**: se guarda el `sha` de la última lectura y se envía en el `PUT`; si GitHub
  responde `409`, la app pide recargar antes de reintentar. Si el archivo no existe, el `PUT` va sin
  `sha` y GitHub lo crea.
- **Archivos grandes**: si la Contents API no devuelve `content` (>1 MB), se usa la Blob API y, como
  último recurso, `raw.githubusercontent.com`.
- **CORS**: GitHub, TMDB, OMDb, Trakt, Simkl, TVMaze, Wikidata, AniList e imdbapi.dev permiten
  peticiones desde el navegador. IMDb Suggest (`v3.sg.media-imdb.com`) es un endpoint no oficial y
  Cinemeta/IMDbOT son servicios comunitarios: si un bloqueador o la política del navegador los corta,
  la app lo detecta, lo explica por *toast* y sigue funcionando con el resto de fuentes.
- **Sin Tailwind** (CDN caído) la app sigue siendo funcional: los componentes críticos están en CSS
  propio dentro del propio `index.html`.

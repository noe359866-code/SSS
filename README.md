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
3. Pulsa **Probar conexión** para validar repositorio, token y fuentes de metadatos.
4. Busca títulos, añádelos y pulsa **Guardar en GitHub** (o `Ctrl/⌘ + S`).

Consejo: puedes servirlo en local con `npm start` (`python3 -m http.server 8000`) o con cualquier
servidor estático. El token solo se envía a `api.github.com`; no hay proxy ni servidor propio.

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

## Funciones

- **Buscador multifuente** con 3 proveedores seleccionables:
  - **IMDb Suggest** — endpoint público de autocompletado, sin API key (con reintento en host alternativo).
  - **TMDB v3** — `search/multi`, conversión id TMDB → IMDb vía `external_ids` y pósters `image.tmdb.org`.
  - **OMDb** — búsqueda por título (`s=`) y ficha por código IMDb (`i=`), incluye nº de temporadas.
  - Filtro por tipo (todo / películas / series) y detección de resultados que ya están en la lista.
- **Modal de temporada/episodio**: serie completa, temporada `:sN` o rango de episodios `:sN:eM`
  (con vista previa exacta de las líneas que se crearán y detección de duplicados).
- **Lista interactiva**: tarjeta por línea con póster, tipo, la línea literal que se escribirá,
  edición y borrado individual.
- **Vista previa en tiempo real** con numeración de línea, resaltado de sintaxis y marcas de
  altas/bajas frente al último contenido leído de GitHub.
- **Alta manual** de IDs, líneas completas, URLs de IMDb/TMDB y comentarios.
- **Importar / descargar / copiar** el archivo, y descarte de cambios locales.
- **Errores explícitos** para 401 (token), 403 (permisos o límite), 404 (ruta), 409 (sha desfasado),
  422 (validación), fallos de red/CORS y *timeouts*, con avisos por *toast*.

## Seguridad

- El PAT se guarda en `localStorage` **solo si marcas «Recordar el token»**; en caso contrario queda
  únicamente en `sessionStorage` (se pierde al cerrar la pestaña).
- Todas las peticiones van directas del navegador a `api.github.com` (y a las APIs de metadatos).
  No hay analytics ni terceros.
- Usa tokens *fine-grained* limitados al repositorio y al permiso **Contents: Read and write**.
- Evita usar la página en equipos compartidos; cualquiera con acceso al perfil del navegador puede
  leer el token. El botón **Borrar token guardado** lo elimina de inmediato.

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
npm test        # 37 pruebas de núcleo + 39 de interfaz
```

- `tests/core.test.mjs` extrae el script de `index.html` y valida el parser/serializador: formato de
  la especificación, *round-trip* exacto, CRLF, Base64 UTF-8 (acentos, `ñ`, emoji, CJK), diff y
  estadísticas.
- `tests/ui.test.mjs` monta la página en jsdom con `fetch` simulado (GitHub, IMDb, TMDB y OMDb) y
  recorre los flujos completos: configuración, carga del watchlist, búsqueda, alta desde resultado,
  modal temporada/episodio, duplicados, alta manual, guardado con verificación del Base64 enviado y
  del `sha`, conflictos 404/409 y persistencia local.

## Notas técnicas

- **Base64 UTF-8**: se codifica con `TextEncoder` + `btoa` por bloques (nunca `btoa(str)` directo),
  y se decodifica con `TextDecoder`, para no romper acentos ni `ñ` en la Contents API.
- **`sha` y conflictos**: se guarda el `sha` de la última lectura y se envía en el `PUT`; si GitHub
  responde `409`, la app pide recargar antes de reintentar. Si el archivo no existe, el `PUT` se hace
  sin `sha` y GitHub lo crea.
- **Archivos grandes**: si la Contents API no devuelve `content` (>1 MB), se usa la Blob API y, como
  último recurso, `raw.githubusercontent.com`.
- **CORS**: GitHub, TMDB, OMDb e IMDb Suggest permiten peticiones desde el navegador. Si un
  bloqueador o la política del navegador impide alguna, la app lo detecta y lo explica en pantalla
  (el único punto realmente sensible es `v3.sg.media-imdb.com`).
- **Sin Tailwind** (CDN caído) la app sigue siendo funcional: los componentes críticos están en CSS
  propio dentro del propio `index.html`.

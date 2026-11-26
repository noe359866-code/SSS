# Catálogo (Nuvio · Cinemeta)

Cineflux no usa una API propietaria de catálogo: descarga **manifests de addons**
y les pide filas, fichas y episodios. Vienen dos fuentes preinstaladas y puedes
añadir, quitar o restaurar otras en *Ajustes → Catálogo*.

| Fuente | Manifest | Recursos |
|---|---|---|
| **Nuvio** | `https://catalog.nuvio.tv/manifest.json` | `catalog` + `meta` (IDs `tt…` y `tmdb:…`) |
| **Cinemeta** | `https://v3-cinemeta.strem.io/manifest.json` | `catalog` + `meta` (IDs `tt…`) |

No hace falta clave. Las carátulas vienen de `image.tmdb.org`, `images.metahub.space`
o `episodes.metahub.space` según la fuente.

## De manifest a filas

El núcleo valida el manifest (`cfx_parse_manifest`) y después planifica
(`cfx_plan_catalog`) con una petición como esta:

```json
{
  "types": ["movie", "series"],
  "query": "",
  "limit": 10,
  "perSource": 5,
  "sources": [
    {"baseUrl": "https://catalog.nuvio.tv", "manifest": { "id": "com.nuvio.tmdb.catalogs", "catalogs": [] }}
  ]
}
```

Reglas del planificador:

1. Solo entran catálogos de los `types` pedidos.
2. Se descartan los que tienen **extras obligatorios** (`extraRequired` o
   `extra[].isRequired`): por ejemplo el catálogo «New» de Cinemeta exige un año.
3. Se descartan `last-videos`, `calendar-videos` y los «by-year» / «by-language»,
   que sin un extra devuelven resultados arbitrarios.
4. **Búsqueda**: si `query` no está vacío, solo se planifican catálogos que
   declaran `search` (Nuvio `popular-movies`/`popular-series`, Cinemeta `top`).
5. `limit` es el total de filas y `perSource` el máximo por addon (máx. 40/12).

Salida (recortada):

```json
{"ok":true,"count":4,"rows":[
  {"addonId":"com.nuvio.tmdb.catalogs","type":"movie","catalogId":"trending-movies",
   "name":"Trending Movies","url":"https://catalog.nuvio.tv/catalog/movie/trending-movies.json",
   "search":false}
]}
```

`addonName` y `name` los usa la interfaz; Cineflux traduce las filas conocidas
(«Trending Movies» → «Tendencias en películas») y respeta el nombre original del
resto.

## Peticiones que hace la app

```
GET {base}/catalog/{type}/{id}.json
GET {base}/catalog/{type}/{id}/search={texto}.json
GET {base}/meta/{type}/{id}.json
```

- **Filas**: `metas[]` se mapea a títulos. `id` puede ser `tt…` o `tmdb:…`; de ahí
  salen `imdbId`/`tmdbId` para los plugins y para los saltos.
- **Ficha**: Nuvio primero y Cinemeta como respaldo. De `meta` se toman
  `name`, `poster`, `background`, `logo`, `description`, `releaseInfo`, `genres`,
  `cast`, `imdbRating`, `app_extras.ratings.*` y `trailers`. Nuvio resuelve
  `tmdb:…` a `tt…`, así que la ficha y la reproducción usan el ID de IMDb.
- **Episodios**: `meta.videos[]` con `season` + `episode`/`number`, que se agrupan
  por temporada; la temporada 0 (extras) se descarta. Nuvio trae `overview` y
  `thumbnail`; Cinemeta también. Las duraciones (`1h2min`, `79m`, `57 min`) se
  normalizan a minutos.
- **Búsqueda**: se lanzan en paralelo los catálogos con `search`, se deduplican
  por `key` y se ordenan por nota.

## Caché y estado vacío

- Manifests: 30 minutos en memoria.
- Portada: 10 minutos; se invalida si cambias la lista de fuentes.
- Fichas: por sesión (temporadas incluidas).
- Si no hay fuentes o ninguna responde, la portada muestra un estado vacío con
  «Reintentar» y acceso a *Ajustes → Catálogo*. Nunca se inventan títulos.

## Rotten Tomatoes

OMDb es opcional. Con clave se consulta por `i=tt…` (más fiable) y se lee
`Ratings[Source=Rotten Tomatoes]`. Sin clave, Cineflux intenta la búsqueda
pública de RT y, si tu red la bloquea, puedes configurar un proxy en *Ajustes → Red*.

## Añadir otra fuente

Pega un manifest con recurso `catalog`. Requisitos mínimos:

```json
{"id":"org.ejemplo.catalogo","name":"Ejemplo","resources":["catalog","meta"],
 "types":["movie","series"],"idPrefixes":["tt"],
 "catalogs":[{"type":"movie","id":"popular","name":"Popular",
              "extra":[{"name":"search","isRequired":false}]}]}
```

El planificador usará sus filas en el siguiente refresco. Si el manifest no
declara `catalog`, el núcleo lo infiere a partir de `catalogs` y avisa en
`warnings` al instalarlo como plugin.

## Problemas frecuentes

| Síntoma | Causa | Solución |
|---|---|---|
| Portada vacía | Sin fuentes o manifests caídos | *Ajustes → Catálogo* → Restaurar Nuvio + Cinemeta |
| Faltan filas | Catálogos con extras obligatorios | Es intencionado: se evitan filas sin contexto |
| Una fila repite títulos | Dos addons con el mismo contenido | Quita una fuente o reduce filas |
| Carátulas lentas | `image.tmdb.org` / `metahub` | Se cachean en disco por el sistema de imágenes |
| No hay notas RT | Sin clave OMDb y RT bloqueado | Añade OMDb o un proxy en Red |

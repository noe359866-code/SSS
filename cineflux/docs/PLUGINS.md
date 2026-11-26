# Plugins y manifests

Cineflux **no scrapea**. Solo habla con plugins descritos por un manifest JSON.
Se instalan por URL; la app descarga y valida el manifest por ti.

## Manifest mínimo

```json
{
  "id": "mi.plugin.streams",
  "version": "1.0.0",
  "name": "Mi plugin",
  "description": "Streams en español.",
  "types": ["movie", "series"],
  "resources": [
    {"name": "stream", "types": ["movie", "series"], "idPrefixes": ["tt"]}
  ],
  "idPrefixes": ["tt"],
  "transportUrl": "https://mi-servidor.example/manifest.json"
}
```

Recursos reconocidos: `stream`, `catalog`, `meta`, `subtitles`. Para catálogo:

```json
"resources": ["catalog", "meta"],
"catalogs": [{"type": "movie", "id": "top", "name": "Mejor valoradas"}]
```

## Cómo se llama al plugin

Para cada recurso `stream`, el núcleo construye:

```
{transportUrl}/stream/{type}/{id}.json
```

donde `id` respeta los prefijos declarados (`tt123…` para IMDb o `tmdb:123` si el
plugin usa IDs TMDB). Las series añaden `/{season}/{episode}` al ID cuando el
manifest lo espera.

La respuesta se interpreta como un addon estándar: `{"streams":[{url,title,
behaviorHints,subtitles,…}]}`. El núcleo extrae calidad, idioma, tamaño, HDR,
CAM, HEVC y torrent del texto y de las pistas.

## Instalación

1. *Ajustes → Plugins*.
2. Pega el enlace del plugin (`https://…/manifest.json`).
3. Pulsa **Instalar**; la app valida el manifest antes de añadirlo.
4. Ordena con las flechas: la prioridad afecta al ranking (−4 puntos por puesto).

Solo se aceptan plugins que aporten reproducción. Los addons que únicamente
traen catálogo se añaden como **fuente** en *Ajustes → Catálogo*, no aquí. Puedes
**probar** cada plugin y **actualizar** si el autor cambió la URL.

## Buenas prácticas

- Un plugin = un manifest. No incrustes HTML ni scripts.
- Declara `idPrefixes` reales para no recibir peticiones que no puedas resolver.
- Devuelve siempre `url` directa (MP4/HLS). Las páginas intermedias se penalizan.
- Etiqueta idioma y calidad en el `title` si tu servidor no da metadatos: el
  ranking los usa.
- Si tu CDN rota tokens, no pasa nada: el failover probará la siguiente opción.

## Solución de problemas

| Síntoma | Causa probable | Qué hacer |
|---|---|---|
| "Ese enlace no es válido o está incompleto" | Manifest roto o sin `transportUrl` | Revisa el enlace con el autor del plugin |
| El plugin no se instala | Solo aporta catálogo | Añádelo en *Ajustes → Catálogo* |
| El plugin aparece pero no reproduce | Sin recurso `stream` o prefijos incorrectos | Añade `{"name":"stream",…}` |
| Siempre gana otro plugin | Prioridad baja | Súbelo con la flecha |
| La app pide configurar catálogos | No hay fuentes | *Ajustes → Catálogo* |

Nunca verás el código de estado HTTP de un plugin: la app lo convierte en
"no hay opción" y sigue con el resto.

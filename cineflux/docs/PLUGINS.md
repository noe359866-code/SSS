# Plugins y manifests

Cineflux **no scrapea**. Solo habla con plugins descritos por un manifest JSON.
Puedes instalarlos por URL o pegando el JSON en crudo con su `transportUrl`.

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
2. Pega la URL del manifest o el JSON completo.
3. Si el manifest no trae `transportUrl`, escribe la base en el campo inferior.
4. Pulsa **Instalar**; verás advertencias si falta algún recurso.
5. Ordena con las flechas: la prioridad afecta al ranking (−4 puntos por puesto).

Los plugins de catálogo añaden filas al inicio; los de stream habilitan la
reproducción. Puedes **probar** cada uno (pide su manifest) y **actualizar** si el
autor cambió la URL.

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
| "Ese manifest no es válido o está incompleto" | JSON roto o sin `transportUrl` | Revisa comas/llaves y el campo base |
| El plugin aparece pero no reproduce | Sin recurso `stream` o prefijos incorrectos | Añade `{"name":"stream",…}` |
| Siempre gana otro plugin | Prioridad baja | Súbelo con la flecha |
| La app pide configurar catálogos | No hay fuentes | *Ajustes → Catálogo* |

Nunca verás el código de estado HTTP de un plugin: la app lo convierte en
"no hay opción" y sigue con el resto.

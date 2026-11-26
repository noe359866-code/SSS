# Saltos de intro, recap y créditos

Cineflux pregunta a cuatro bases de datos comunitarias por los tiempos de cada
episodio y los combina en el núcleo C++. Si alguna falla, se ignora en silencio.
No hay modo demo: si no hay datos, simplemente no aparece el botón.

| Fuente | Qué aporta | Clave | Identificador |
|---|---|---|---|
| **TheIntroDB** | intro, recap, créditos y preview | opcional (Bearer) | IMDb, TMDB o TVDB |
| **IntroDB** | intro, recap, outro, post-créditos | no | IMDb |
| **AniSkip** | OP, ED, recap y variantes mixtas | no | MyAnimeList (resuelto con Jikan) |
| **Anime Skip** | intro, recap, créditos, preview | client ID | AniList (resuelto con GraphQL) |

## Consultas

```
# TheIntroDB (v4)
GET https://api.theintrodb.org/v4/media?imdb_id=tt0903747&season=1&episode=1&duration_ms=2820000

# IntroDB
GET https://api.introdb.app/segments?imdb_id=tt0903747&season=1&episode=1
GET https://api.introdb.app/segments?imdb_id=tt0371746&is_movie=true

# AniSkip
GET https://api.aniskip.com/v2/skip-times/5114/1?types[]=op&types[]=ed&episodeLength=1440

# Anime Skip (GraphQL, dos pasos + AniList)
POST https://api.anime-skip.com/graphql     X-Client-ID: <tu id>
```

Las peticiones salen en paralelo con timeout corto. Cualquier respuesta vacía,
404 o error de red se traduce a «esta fuente no tiene datos».

## Formato interno

La app entrega al núcleo:

```json
{
  "durationMs": 1440000,
  "sources": [
    {"source": "theintrodb", "payload": { "intro": [{"start_ms": 12000, "end_ms": 102000}] }},
    {"source": "introdb", "payload": { "intro": {"start_ms": 12500, "end_ms": 101000} }},
    {"source": "aniskip", "payload": {"results": [{"interval": {"startTime": 12, "endTime": 102}, "skipType": "op"}]}},
    {"source": "animeskip", "payload": {"timestamps": [{"at": 12, "type": {"name": "Intro"}}]}}
  ]
}
```

El núcleo devuelve:

```json
{
  "ok": true,
  "count": 2,
  "segments": [
    {"kind": "intro", "label": "Saltar intro", "startMs": 12000, "endMs": 102000, "source": "theintrodb"},
    {"kind": "credits", "label": "Saltar créditos", "startMs": 1320000, "endMs": 1440000, "source": "introdb"}
  ]
}
```

## Reglas de combinación

1. **Validación**: se descartan tramos invertidos, de menos de 5 s, que empiezan
   después de la duración o que terminan fuera de ella (se recorta el final).
2. **Una fuente por tipo**: `intro`, `recap` y `credits`. El orden por defecto es
   TheIntroDB → IntroDB → AniSkip → Anime Skip; puedes cambiarlo con `priority`.
3. **Anime Skip** solo marca inicios: el fin es el siguiente marcador (máximo
   10 minutos; si no, se descarta).
4. **Deduplicación**: aunque cuatro fuentes describan la misma intro, solo se
   muestra una.

## En la app

- **Botón**: aparece en la esquina inferior derecha con el estilo de Apple TV
  («Saltar intro» + chevron), independiente de los controles.
- **Automatismos** (*Ajustes → Saltos*): intro (off), recap (on), créditos (off)
  por defecto. Cada uno salta una sola vez por episodio.
- **Caché**: 7 días por título/temporada/episodio en el almacenamiento local.

## Depuración

```bash
cd core
make probe   # incluye un ejemplo de fusión de saltos
make test    # 271 comprobaciones, incluidas las de skip.cpp
```

Para comprobar una respuesta concreta, pega su JSON en una petición
`cfx_skip_segments` con la duración real. Los tramos descartados nunca generan
mensajes: la interfaz solo muestra los que sobreviven.

# Perfiles de reproducción

Un perfil es la **única elección** del usuario. No hay lista de webs ni de
servidores: el perfil describe qué quieres ver y el núcleo puntúa todos los
streams disponibles para elegir el primero.

## Campos

| Campo | Tipo | Por defecto | Qué hace |
|---|---|---|---|
| `id` | string | derivado del nombre | Identificador estable |
| `name` | string | — | Nombre visible (máx. 40) |
| `description` | string | `""` | Texto corto en la lista |
| `accent` | `#RRGGBB` | `#22d3ee` | Color del punto del perfil |
| `languages` | string[] | `[]` | Orden de preferencia (`latino`, `castellano`, `es`, `dual`, `en`, `sub`…) |
| `targetQuality` | int | `1080` | Calidad ideal |
| `maxQuality` | int | `2160` | Techo permitido (nunca menor que el objetivo) |
| `allowCam` | bool | `false` | Aceptar grabaciones CAM |
| `allowTorrent` | bool | `false` | Aceptar candidatos torrent (la app no trae motor) |
| `preferHdr` | bool | `false` | Empujar HDR arriba |
| `preferSubtitles` | bool | `false` | Favorecer VO/subtítulos |
| `preferDual` | bool | `true` | Bonus para audio dual |
| `strictLanguage` | bool | `false` | Descartar lo que no case de idioma |
| `dataSaver` | bool | `false` | Penalizar 4K y archivos enormes |

Calidades admitidas: `360, 480, 720, 1080, 1440, 2160`. El núcleo normaliza
cualquier otro valor al más cercano.

## Perfiles recomendados (los seis de fábrica)

| id | Nombre | Idiomas | Objetivo / techo | Notas |
|---|---|---|---|---|
| `latino-1080` | Latino 1080p | latino, dual, es | 1080 / 1080 | Equilibrado |
| `latino-4k` | Latino 4K | latino, dual, es | 2160 / 2160 | HDR preferido |
| `castellano` | Castellano | castellano, es, dual | 1080 / 1080 | España |
| `subtitulado` | Subtitulado | en, original | 2160 / 2160 | HDR + subtítulos |
| `movil` | Móvil / datos | latino, dual, es, castellano | 720 / 1080 | Ahorro de datos |
| `automatico` | Automático | cualquiera | 1080 / 2160 | Sin filtro de idioma |

En Android: *Ajustes → Perfiles*. En iOS: el selector del reproductor o
*Ajustes → Perfiles*. Crear, editar, duplicar por color y eliminar (siempre queda
al menos uno).

## Cómo puntúa el núcleo

Punto de partida `100`. Después:

| Concepto | Ajuste |
|---|---|
| Idioma sin preferencias | +10 |
| Idioma desconocido en el stream | −8 |
| Coincidencia de idioma | +42, −8 por cada puesto de retraso (máx. 3) |
| Idioma distinto | −30 |
| Dual con `preferDual` | +5 |
| `preferSubtitles` + etiqueta sub | +14; con pistas de subtítulos +6 |
| Etiqueta sub sin preferencia | −6 |
| Calidad sin dato | −12 |
| Calidad = objetivo | +32 |
| Cada paso de distancia | −9 |
| `dataSaver`: 2160 / ≥1080 / 720 | −32 / −12 / +8 |
| `dataSaver`: >3 GB | −20 |
| Sin ahorro: <350 MB / >8 GB | −6 / −4 |
| HDR | +7 con `preferHdr`, +1 sin |
| REMUX | +3 (−10 con ahorro) |
| HEVC | +2 |
| 3D | −8 |
| URL directa de vídeo | +10 |
| Página intersticial | −14 |
| Prioridad del plugin | −4 por puesto; +3 al primero |

Filtros duros: sin URL, torrent no permitido, CAM no permitido, idioma estricto
sin coincidencia y calidad por encima del techo.

### Pasada permisiva

Si tras los filtros no queda nada y el techo no es 2160, el núcleo repite el
ranking con un techo más alto y aplica −6 a esos candidatos. Es la red de
seguridad para que el reproductor tenga siempre una opción antes que una
pantalla vacía.

## Ejemplo: crear un perfil "Noche, poco ruido"

```json
{
  "id": "noche-sub",
  "name": "Noche subtitulada",
  "description": "VO con subtítulos y volumen bajo.",
  "accent": "#a78bfa",
  "languages": ["en"],
  "targetQuality": 1080,
  "maxQuality": 1080,
  "preferSubtitles": true,
  "preferDual": false,
  "strictLanguage": true
}
```

Se puede importar dentro de una copia de seguridad (`profiles`) o pegar como
perfil nuevo desde la app.

## Preguntas rápidas

**¿Por qué no veo servidores?** Porque no existen como concepto en la app. Si un
plugin devuelve diez opciones, el ranking elige una y las demás quedan como
respaldo invisible.

**¿Qué pasa si la mejor opción falla?** Se salta a la siguiente en menos de un
segundo; si era un buffering eterno, el watchdog corta a los 13 s.

**¿Y si ningún plugin responde?** Mensaje humano con dos salidas: reintentar o
cambiar de perfil. Nunca un código de error.

**¿El perfil afecta al catálogo?** No. Las filas salen de Nuvio y Cinemeta
(*Ajustes → Catálogo*) y el perfil solo decide qué stream se reproduce.

**¿Los saltos dependen del perfil?** No. Son globales (*Ajustes → Saltos*): un
botón visible y tres automatismos independientes para intro, recap y créditos.
El perfil solo decide qué stream se reproduce.

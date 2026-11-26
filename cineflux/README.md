# Cineflux

Cliente móvil **nativo** de catálogo y reproducción con una interfaz inspirada en
**Apple TV + Netflix**. No incluye scrapers: toda la reproducción llega de
**plugins con manifest** (JSON pegado o por URL), el catálogo sale de addons
como **Nuvio** y **Cinemeta**, las notas de **Rotten Tomatoes** llegan por OMDb y
los saltos de intro, recap y créditos de **TheIntroDB, IntroDB, AnimeSkip y AniSkip**.

**Nada de demos:** sin fuentes de catálogo no hay títulos de relleno; la app pide
configuración y espera datos reales.

- **Android**: Kotlin + Jetpack Compose + Media3.
- **iOS**: Swift + SwiftUI + AVPlayer.
- **Núcleo compartido**: C++17 (`core/`), expuesto por una API C a ambas apps.
- **Sin selector de webs**: eliges un **perfil**; el núcleo puntúa los streams y el
  reproductor arranca el mejor. Si falla, salta al siguiente **sin mostrar errores**.

## Principios de diseño

1. **Cero scrapers dentro de la app.** Solo manifests de plugins. La app no conoce
   ninguna web de streaming.
2. **Nunca verás un 403 ni una "fuente caída".** Los errores técnicos se silencian:
   el motor cambia de opción y, si se agotan, muestra un mensaje humano.
3. **Sin lista de servidores.** Un perfil (idioma, calidad, preferencias) decide todo.
4. **Catálogo por addons**: Nuvio (`catalog.nuvio.tv`) y Cinemeta
   (`v3-cinemeta.strem.io`) aportan filas, fichas y episodios; Rotten Tomatoes
   añade crítica y audiencia (vía OMDb o su búsqueda pública).
5. **Sin demo**: solo contenido real. Sin fuentes de catálogo la app guía a añadirlas.
6. **Saltos de comunidad**: intro, recap y créditos con botón estilo Apple TV y
   automatismos opcionales.
7. **Móvil primero**: negro absoluto, héroe editorial y carruseles tipo Netflix.

## Estructura

```
cineflux/
├── core/            Núcleo C++17 + API C + paquete SwiftPM + tests
├── android/         App Android (Kotlin/Compose)
├── ios/             App iOS (SwiftUI) + project.yml (XcodeGen)
├── docs/            Arquitectura, catálogo, perfiles, plugins y saltos
└── README.md
```

## Compilar y probar

### Núcleo (donde vive toda la lógica)

```bash
cd core
make test     # 271 comprobaciones
make probe    # plan, ranking y saltos por consola
make lib      # libcineflux.a / .dylib / .so
```

También hay `CMakeLists.txt` y `Package.swift` para integrarlo en Xcode.

### Android

Requiere JDK 17, Android SDK y NDK (para el núcleo).

Abre `android/` en Android Studio (Ladybug o superior, JDK 17) y sincroniza, o
genera el wrapper una vez con un Gradle instalado:

```bash
cd android
gradle wrapper --gradle-version 8.9   # crea gradlew si no existe
./gradlew assembleDebug
```

La app usa Compose BOM, Media3 y OkHttp. El JNI (`src/main/cpp/jni_bridge.cpp`)
compila `core/src/*.cpp` como `libcineflux.so`.

### iOS

Requiere macOS con Xcode 15+ y [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```bash
cd ios
xcodegen generate
open Cineflux.xcodeproj
```

El proyecto enlaza el paquete local `../core` (producto `CinefluxCore`) y pide
`ENABLE_USER_SCRIPT_SANDBOXING=NO` para el build del paquete C++.

## Puesta en marcha

1. **Catálogos**: vienen preinstalados dos addons:
   `https://catalog.nuvio.tv/manifest.json` (Nuvio, catálogo TMDB) y
   `https://v3-cinemeta.strem.io/manifest.json` (Cinemeta). Puedes añadir, quitar
   o restaurar fuentes en *Ajustes → Catálogo*. Sin fuentes no se muestra ningún
   título: el inicio explica cómo añadirlas.
2. **Rotten Tomatoes**: añade opcionalmente una clave de OMDb para recibir el
   Tomatometer por IMDb sin proxies. Sin OMDb se consulta la búsqueda pública de
   RT y, si tu red la bloquea, puedes configurar un proxy en *Ajustes → Red*.
3. **Saltos**: *Ajustes → Saltos*. Botón visible por defecto; los automatismos
   de intro/recap/créditos se activan por separado. Anime Skip acepta un client ID.
4. **Plugins**: *Ajustes → Plugins*. Pega la URL de un manifest o el JSON en crudo
   (con `transportUrl`). Puedes probar cada plugin y ordenar su prioridad.
5. **Perfiles**: *Ajustes → Perfiles*. Elige idioma, calidad y preferencias. El
   perfil activo se ve en la cabecera y en el reproductor.

## Cómo se reproduce

```
ficha → perfil activo → plan de peticiones (C++) → plugins en paralelo
      → ranking (C++) → AVPlayer / Media3 → failover silencioso
                     ↘ TheIntroDB · IntroDB · AniSkip · AnimeSkip
                       → cfx_skip_segments (C++) → «Saltar intro/recap/créditos»
```

No hay pasos manuales ni elección de servidor. El detalle está en
[docs/ARQUITECTURA.md](docs/ARQUITECTURA.md), el catálogo en
[docs/CATALOGO.md](docs/CATALOGO.md), las reglas de puntuación en
[docs/PERFILES.md](docs/PERFILES.md) y los saltos en
[docs/SALTOS.md](docs/SALTOS.md).

## Estado de verificación

- Núcleo C++: `make test` → **271/271** y `make probe` correcto.
- Android/iOS: código completo en el repositorio; no compilados en este entorno
  (sin JDK/NDK/Xcode). Las instrucciones de build están arriba.

## Aviso

Cineflux no aloja ni distribuye contenido. Los plugins son componentes de terceros
que tú decides instalar; úsalos con contenido al que tengas derecho de acceso.

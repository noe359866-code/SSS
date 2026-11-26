package app.cineflux.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cineflux.AppGraph
import app.cineflux.data.DEFAULT_CATALOG_SOURCES
import app.cineflux.core.Messages
import app.cineflux.core.NativeCore
import app.cineflux.ui.Chip
import app.cineflux.ui.Field
import app.cineflux.ui.SectionHeader
import app.cineflux.ui.SettingsCard
import app.cineflux.ui.ValueRow
import app.cineflux.ui.theme.Golden
import app.cineflux.ui.theme.Muted
import app.cineflux.ui.theme.Panel
import app.cineflux.ui.theme.Rose
import app.cineflux.ui.theme.Stroke
import app.cineflux.ui.theme.Text
import kotlinx.coroutines.launch

@Composable
fun SettingsHeader(title: String, subtitle: String?, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Rounded.ArrowBack, "Volver", tint = Text)
        }
        Column {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
fun SettingsScreen(graph: AppGraph, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val plugins by graph.plugins.plugins.collectAsStateWithLifecycle()
    val profiles by graph.profiles.profiles.collectAsStateWithLifecycle()
    val selectedId by graph.profiles.selectedId.collectAsStateWithLifecycle()
    val active = profiles.firstOrNull { it.id == selectedId }

    Column(Modifier.fillMaxSize()) {
        SettingsHeader("Ajustes", "Todo se guarda en este dispositivo", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
            SettingsCard {
                Text("Reproducción", style = MaterialTheme.typography.titleMedium, color = Muted)
                ValueRow(
                    "Perfiles de reproducción",
                    (active?.let { "Activo: ${it.name}" } ?: "") + " · ${profiles.size} perfiles",
                    onClick = { onNavigate("settings/profiles") }
                )
                HorizontalDivider(color = Stroke, thickness = 0.5.dp)
                ValueRow(
                    "Saltos de intro, recap y créditos",
                    "TheIntroDB · IntroDB · AnimeSkip · AniSkip",
                    onClick = { onNavigate("settings/skips") }
                )
                HorizontalDivider(color = Stroke, thickness = 0.5.dp)
                ValueRow(
                    "Plugins",
                    if (plugins.isEmpty()) "Sin plugins instalados"
                    else "${plugins.size} instalados · ${plugins.count { it.enabled }} activos",
                    onClick = { onNavigate("settings/plugins") }
                )
            }

            SettingsCard {
                Text("Catálogo", style = MaterialTheme.typography.titleMedium, color = Muted)
                ValueRow(
                    "Fuentes de catálogo",
                    when (settings.catalogSources.size) {
                        0 -> "Sin fuentes · añade una fuente"
                        1 -> "1 fuente de catálogo"
                        else -> "${settings.catalogSources.size} fuentes de catálogo"
                    },
                    onClick = { onNavigate("settings/catalog") }
                )
                HorizontalDivider(color = Stroke, thickness = 0.5.dp)
                ValueRow(
                    "Rotten Tomatoes",
                    if (settings.hasOmdb) "OMDb conectado" else "OMDb opcional · notas de crítica",
                    onClick = { onNavigate("settings/catalog") }
                )
                HorizontalDivider(color = Stroke, thickness = 0.5.dp)
                ValueRow(
                    "Red",
                    if (settings.proxyTemplate.isBlank()) "Conexión directa" else "Proxy configurado",
                    onClick = { onNavigate("settings/network") }
                )
            }

            SettingsCard {
                Text("Datos", style = MaterialTheme.typography.titleMedium, color = Muted)
                ValueRow("Copia de seguridad", "Exporta o importa ajustes, plugins, perfiles y lista", onClick = { onNavigate("settings/data") })
                HorizontalDivider(color = Stroke, thickness = 0.5.dp)
                ValueRow("Acerca de Cineflux", "Núcleo ${NativeCore.coreVersion()} · Kotlin + Swift + C++", onClick = { onNavigate("settings/about") })
            }

            Text(
                "Cineflux no incluye scrapers ni aloja contenido. Los plugins que instales solo " +
                    "añaden fuentes de reproducción.",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
fun CatalogSettingsScreen(graph: AppGraph, onBack: () -> Unit) {
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var newSource by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        SettingsHeader("Catálogo", "Tus fuentes de catálogo", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
            SettingsCard {
                Text("Fuentes de catálogo", style = MaterialTheme.typography.titleMedium, color = Muted)
                Spacer(Modifier.height(6.dp))
                Text(
                    "De aquí salen las filas y las fichas de la app. Puedes añadir una fuente " +
                        "o restaurar las oficiales. Sin fuentes no hay títulos.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted
                )
                Spacer(Modifier.height(12.dp))
                settings.catalogSources.forEachIndexed { index, url ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = Stroke,
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Fuente ${index + 1}",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1
                            )
                            Text(
                                if (index == 0) "Catálogo principal" else "Catálogo adicional",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1
                            )
                        }
                        TextButton(onClick = {
                            graph.settings.update { current ->
                                current.copy(catalogSources = current.catalogSources - url)
                            }
                            message = "Fuente eliminada."
                        }) {
                            Text("Quitar", color = Muted)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Field(
                    label = "Añadir fuente",
                    value = newSource,
                    onChange = { newSource = it },
                    placeholder = "Dirección de la fuente",
                    helper = "Se validará al recargar el catálogo."
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val url = newSource.trim()
                            if (url.startsWith("http")) {
                                graph.settings.update { current ->
                                    if (current.catalogSources.contains(url)) current
                                    else current.copy(catalogSources = current.catalogSources + url)
                                }
                                newSource = ""
                                message = "Fuente añadida. Recarga el catálogo."
                            } else {
                                message = "Pega una URL que empiece por https://"
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Rose, contentColor = Color.White)
                    ) { Text("Añadir") }
                    TextButton(onClick = {
                        graph.settings.update { it.copy(catalogSources = DEFAULT_CATALOG_SOURCES) }
                        message = "Fuentes oficiales restauradas."
                    }) {
                        Text("Restaurar catálogo oficial", color = Muted)
                    }
                }
                if (message.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(message, style = MaterialTheme.typography.labelMedium, color = Golden)
                }
            }

            SettingsCard {
                Text("Rotten Tomatoes", style = MaterialTheme.typography.titleMedium, color = Muted)
                Spacer(Modifier.height(8.dp))
                Field(
                    label = "Clave de OMDb (opcional)",
                    value = settings.omdbKey,
                    onChange = { value -> graph.settings.update { it.copy(omdbKey = value.trim()) } },
                    placeholder = "8 caracteres",
                    helper = "OMDb entrega el Tomatometer usando el ID de IMDb. Plan gratuito: 1.000 consultas/día."
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                message = ""
                                val snapshot = graph.catalog.home(force = true)
                                message = if (snapshot.rows.isNotEmpty()) {
                                    "Catálogo actualizado · ${snapshot.rows.size} filas."
                                } else {
                                    snapshot.message.ifBlank { "No hemos podido cargar el catálogo." }
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Text)
                    ) { Text("Recargar catálogo") }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Las notas de crítica y audiencia se muestran en las fichas y en las filas. " +
                        "Sin OMDb se consulta la búsqueda pública de RT y, si tu red la bloquea, " +
                        "puedes configurar un proxy en Red.",
                    style = MaterialTheme.typography.labelMedium
                )
            }

            SettingsCard {
                Text("Idioma y región", style = MaterialTheme.typography.titleMedium, color = Muted)
                Spacer(Modifier.height(10.dp))
                Text("Idioma de la interfaz y de OMDb", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("es-ES" to "Español", "es-MX" to "Latino", "en-US" to "English").forEach { (code, label) ->
                        Chip(label, settings.language == code) {
                            graph.settings.update { it.copy(language = code) }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Región de estrenos", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ES", "MX", "AR", "US").forEach { code ->
                        Chip(code, settings.region == code) {
                            graph.settings.update { it.copy(region = code) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NetworkSettingsScreen(graph: AppGraph, onBack: () -> Unit) {
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        SettingsHeader("Red y reproducción", "Proxies opcionales", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
            SettingsCard {
                Text("Proxy de red", style = MaterialTheme.typography.titleMedium, color = Muted)
                Spacer(Modifier.height(8.dp))
                Field(
                    label = "Plantilla de proxy (opcional)",
                    value = settings.proxyTemplate,
                    onChange = { value -> graph.settings.update { it.copy(proxyTemplate = value.trim()) } },
                    placeholder = "https://tu-proxy.example/?url={url}",
                    helper = "Solo se usa si la petición directa falla. Debe incluir {url}."
                )
                Spacer(Modifier.height(12.dp))
                Field(
                    label = "User-Agent",
                    value = settings.userAgent,
                    onChange = { value -> graph.settings.update { it.copy(userAgent = value) } },
                    helper = "Algunos plugins piden un agente concreto."
                )
            }

            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Incluir candidatos torrent", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Por defecto se ignoran: Cineflux reproduce HTTP/HLS y no incluye motor torrent.",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Switch(
                        checked = settings.allowTorrents,
                        onCheckedChange = { value -> graph.settings.update { it.copy(allowTorrents = value) } },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Rose)
                    )
                }
                HorizontalDivider(color = Stroke, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 10.dp))
                ValueRow(
                    "Borrar progreso de reproducción",
                    "Mantiene tu lista y tus plugins",
                    onClick = { graph.library.clearProgress() }
                )
            }

            Text(
                "Consejo: en el móvil casi todo funciona sin proxy. Si un plugin concreto no responde, " +
                    "prueba a actualizar el plugin o ponlo por debajo de otro más rápido en la lista.",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
fun DataSettingsScreen(graph: AppGraph, onBack: () -> Unit) {
    val context = LocalContext.current
    var importText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var exportText by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        SettingsHeader("Datos y copia", "Ajustes, plugins, perfiles y lista", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
            SettingsCard {
                Text("Exportar", style = MaterialTheme.typography.titleMedium, color = Muted)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Copia el JSON con toda tu configuración para pasarla a otro dispositivo.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        exportText = graph.exportBackup()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Cineflux", exportText))
                        message = "Copia lista en el portapapeles."
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Rose, contentColor = Color.White)
                ) { Text("Copiar copia de seguridad") }
            }

            SettingsCard {
                Text("Importar", style = MaterialTheme.typography.titleMedium, color = Muted)
                Spacer(Modifier.height(8.dp))
                Field(
                    label = "Pega aquí la copia",
                    value = importText,
                    onChange = { importText = it },
                    placeholder = "{ \"app\": \"cineflux\", … }",
                    singleLine = false,
                    minLines = 4
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        val error = graph.importBackup(importText)
                        message = error ?: "Copia importada correctamente."
                        if (error == null) importText = ""
                    },
                    enabled = importText.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Text)
                ) { Text("Importar copia") }
                if (message.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(message, style = MaterialTheme.typography.labelMedium, color = Golden)
                }
            }

            SettingsCard {
                Text("Zona delicada", style = MaterialTheme.typography.titleMedium, color = Muted)
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { graph.library.clearAll(); message = "Lista y progreso borrados." }) {
                    Text("Borrar mi lista y el progreso", color = Rose)
                }
                HorizontalDivider(color = Stroke, thickness = 0.5.dp)
                TextButton(onClick = { graph.clearEverything(); message = "Todo restablecido." }) {
                    Text("Restablecer la app", color = Rose)
                }
            }
        }
    }
}

@Composable
fun AboutScreen(graph: AppGraph, onBack: () -> Unit) {
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        SettingsHeader("Acerca de", "Cineflux 1.0.0", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)
        ) {
            SettingsCard {
                Text("Cineflux", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Cliente de catálogo y reproducción con plugins. " +
                        "Nada de scrapers dentro de la app.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(10.dp))
                Text("Núcleo compartido: ${NativeCore.coreVersion()} (C++17)", style = MaterialTheme.typography.labelMedium)
                Text("Android: Kotlin + Jetpack Compose + Media3", style = MaterialTheme.typography.labelMedium)
                Text("iOS: Swift + SwiftUI + AVPlayer", style = MaterialTheme.typography.labelMedium)
                Text(
                    "Catálogo y puntuaciones de Rotten Tomatoes",
                    style = MaterialTheme.typography.labelMedium
                )
            }

            SettingsCard {
                Text("Cómo funcionan los plugins", style = MaterialTheme.typography.titleMedium, color = Muted)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Cada plugin aporta fuentes de reproducción para películas y series. La app lo valida, " +
                        "deduce los identificadores IMDb/TMDB y le pide opciones. Los plugins se instalan " +
                        "desde su enlace.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Los perfiles deciden qué stream se reproduce: idioma, calidad, HDR, tamaño y prioridad " +
                        "de plugins. El reproductor prueba la mejor opción y, si falla, cambia a la siguiente " +
                        "sin mostrar errores de fuentes.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            SettingsCard {
                Text("Aviso", style = MaterialTheme.typography.titleMedium, color = Muted)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Cineflux no aloja ni distribuye contenido. Los plugins son componentes de terceros que " +
                        "tú decides instalar; asegúrate de usarlos con contenido al que tengas derecho de acceso.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
fun SkipSettingsScreen(graph: AppGraph, onBack: () -> Unit) {
    val settings by graph.settings.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        SettingsHeader("Saltos", "Intro, recap y créditos", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Mostrar botón de salto", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Aparece durante la intro, el recap y los créditos. Sin avisos ni errores.",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Switch(
                        checked = settings.skipSegmentsEnabled,
                        onCheckedChange = { value ->
                            graph.settings.update { it.copy(skipSegmentsEnabled = value) }
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Rose)
                    )
                }
                HorizontalDivider(color = Stroke, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 10.dp))
                SkipToggle(
                    title = "Saltar intros automáticamente",
                    subtitle = "Salta el opening sin preguntar.",
                    checked = settings.autoSkipIntro
                ) { value -> graph.settings.update { it.copy(autoSkipIntro = value) } }
                HorizontalDivider(color = Stroke, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 10.dp))
                SkipToggle(
                    title = "Saltar recaps automáticamente",
                    subtitle = "Ideal para series con resumen al principio.",
                    checked = settings.autoSkipRecap
                ) { value -> graph.settings.update { it.copy(autoSkipRecap = value) } }
                HorizontalDivider(color = Stroke, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 10.dp))
                SkipToggle(
                    title = "Saltar créditos automáticamente",
                    subtitle = "Al terminar el episodio.",
                    checked = settings.autoSkipCredits
                ) { value -> graph.settings.update { it.copy(autoSkipCredits = value) } }
            }

            SettingsCard {
                Text("Fuentes", style = MaterialTheme.typography.titleMedium, color = Muted)
                Text(
                    "TheIntroDB (tv, películas y anime) e IntroDB se consultan siempre. " +
                        "AniSkip se usa en anime con el ID de MyAnimeList, y Anime Skip con tu " +
                        "client ID para enriquecer los tiempos. Todo se combina en el núcleo C++.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(10.dp))
                Field(
                    label = "Anime Skip · client ID (opcional)",
                    value = settings.animeSkipClientId,
                    onChange = { value ->
                        graph.settings.update { it.copy(animeSkipClientId = value.trim()) }
                    },
                    placeholder = "Client ID de api.anime-skip.com",
                    helper = "Sin client ID se usa AniSkip (MAL) y las demás fuentes."
                )
            }

            Text(
                "Los tiempos son de la comunidad y pueden variar entre versiones del mismo " +
                    "episodio. Si un salto cae mal, desactiva su automatismo: el botón seguirá " +
                    "disponible.",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun SkipToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.labelMedium)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Rose)
        )
    }
}

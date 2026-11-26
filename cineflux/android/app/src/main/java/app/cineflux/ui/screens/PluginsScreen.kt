package app.cineflux.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Plug
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cineflux.AppGraph
import app.cineflux.core.Messages
import app.cineflux.core.Plugin
import app.cineflux.ui.Chip
import app.cineflux.ui.EmptyState
import app.cineflux.ui.Field
import app.cineflux.ui.theme.Golden
import app.cineflux.ui.theme.Muted
import app.cineflux.ui.theme.Panel
import app.cineflux.ui.theme.PanelHigh
import app.cineflux.ui.theme.Rose
import app.cineflux.ui.theme.Stroke
import app.cineflux.ui.theme.Text
import kotlinx.coroutines.launch

@Composable
fun PluginsScreen(graph: AppGraph, onBack: () -> Unit) {
    val plugins by graph.plugins.plugins.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    var installing by remember { mutableStateOf(false) }
    var installMessage by remember { mutableStateOf("") }
    var installOk by remember { mutableStateOf(false) }
    val feedback = remember { mutableStateMapOf<String, String>() }
    var pendingDelete by remember { mutableStateOf<Plugin?>(null) }

    Column(Modifier.fillMaxSize()) {
        SettingsHeader(
            "Plugins",
            if (plugins.isEmpty()) "Sin plugins instalados" else "${plugins.size} instalados · ${plugins.count { it.enabled }} activos",
            onBack
        )

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 28.dp)
        ) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Panel)
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Plug, null, tint = Rose, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Añadir plugin", style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Pega el enlace del plugin. Cineflux lo valida y lo añade a tu lista.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Field(
                        label = "Enlace del plugin",
                        value = input,
                        onChange = { input = it },
                        placeholder = "https://…",
                        helper = "Se comprobará antes de instalarlo."
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    installing = true
                                    installMessage = ""
                                    val result = graph.plugins.install(input, "")
                                    installOk = result.ok
                                    installMessage = if (result.ok) result.message else result.message.ifBlank { Messages.manifest }
                                    if (result.ok) {
                                        input = ""
                                    }
                                    installing = false
                                }
                            },
                            enabled = input.isNotBlank() && !installing,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Rose, contentColor = Color.White)
                        ) {
                            if (installing) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("Instalar")
                        }
                    }
                    if (installMessage.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            installMessage,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (installOk) Color(0xFF6EE7B7) else Golden
                        )
                    }
                }
            }

            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(PanelHigh)
                        .padding(14.dp)
                ) {
                    Text("Cómo funcionan los plugins", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Cineflux consulta todos los plugins activos a la vez, ordena las opciones con el perfil " +
                            "activo y reproduce la primera que funcione. Si una opción falla, prueba la siguiente " +
                            "sin preguntarte nada.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (plugins.isEmpty()) {
                item {
                    EmptyState(
                        title = "Todavía no hay plugins",
                        message = "Solo necesitas un plugin de reproducción. Las fuentes de catálogo se añaden " +
                            "en Ajustes → Catálogo."
                    )
                }
            } else {
                item {
                    Text(
                        "Instalados",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                    )
                }
                items(plugins, key = { it.key }) { plugin ->
                    PluginCard(
                        plugin = plugin,
                        feedback = feedback[plugin.key],
                        onToggle = { graph.plugins.setEnabled(plugin.key, it) },
                        onMove = { delta -> graph.plugins.move(plugin.key, delta) },
                        onDelete = { pendingDelete = plugin },
                        onTest = {
                            scope.launch {
                                feedback[plugin.key] = "Comprobando…"
                                val result = graph.plugins.test(plugin)
                                feedback[plugin.key] = if (result.ok) result.message else result.message.ifBlank { Messages.network }
                            }
                        },
                        onRefresh = {
                            scope.launch {
                                feedback[plugin.key] = "Actualizando…"
                                val result = graph.plugins.refresh(plugin)
                                feedback[plugin.key] = if (result.ok) result.message else result.message.ifBlank { Messages.network }
                            }
                        }
                    )
                }
            }
        }
    }

    pendingDelete?.let { plugin ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = Panel,
            title = { Text("Quitar ${plugin.manifest.name}") },
            text = { Text("Se eliminará de este dispositivo. Puedes volver a instalarlo cuando quieras.", color = Muted) },
            confirmButton = {
                TextButton(onClick = {
                    graph.plugins.remove(plugin.key)
                    pendingDelete = null
                }) { Text("Quitar", color = Rose) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancelar", color = Muted) }
            }
        )
    }
}

@Composable
private fun PluginCard(
    plugin: Plugin,
    feedback: String?,
    onToggle: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Panel)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(PanelHigh),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    plugin.manifest.name.take(1).uppercase(),
                    color = Rose,
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    plugin.manifest.name.ifBlank { "Plugin" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "Reproducción",
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Switch(
                checked = plugin.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Rose)
            )
        }

        if (plugin.manifest.description.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                plugin.manifest.description,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (plugin.manifest.baseUrl.isBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Este plugin no está disponible.",
                style = MaterialTheme.typography.labelMedium,
                color = Golden
            )
        }

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Prioridad ${plugin.priority + 1}",
                style = MaterialTheme.typography.labelMedium,
                color = Muted
            )
            IconButton(onClick = { onMove(-1) }) {
                Icon(Icons.Rounded.KeyboardArrowUp, "Subir prioridad", tint = Muted)
            }
            IconButton(onClick = { onMove(1) }) {
                Icon(Icons.Rounded.KeyboardArrowDown, "Bajar prioridad", tint = Muted)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onTest) { Text("Probar", color = Muted, fontSize = 12.sp) }
            TextButton(onClick = onRefresh) { Text("Actualizar", color = Muted, fontSize = 12.sp) }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, "Quitar", tint = Rose.copy(alpha = 0.8f))
            }
        }

        if (!feedback.isNullOrBlank()) {
            Text(
                feedback,
                style = MaterialTheme.typography.labelMedium,
                color = Golden
            )
        }
    }
}

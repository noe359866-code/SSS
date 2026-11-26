package app.cineflux.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Plus
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cineflux.AppGraph
import app.cineflux.core.Profile
import app.cineflux.ui.Chip
import app.cineflux.ui.Field
import app.cineflux.ui.SectionHeader
import app.cineflux.ui.colorFromHex
import app.cineflux.ui.theme.Muted
import app.cineflux.ui.theme.Panel
import app.cineflux.ui.theme.PanelHigh
import app.cineflux.ui.theme.Rose
import app.cineflux.ui.theme.Stroke
import app.cineflux.ui.theme.Text

private val ACCENTS = listOf("#22d3ee", "#f43f5e", "#f59e0b", "#a78bfa", "#34d399", "#94a3b8")
private val LANGUAGES = listOf(
    "latino" to "Latino",
    "castellano" to "Castellano",
    "es" to "Español",
    "dual" to "Dual",
    "en" to "Original",
    "sub" to "Subtítulos"
)
private val QUALITIES = listOf(480, 720, 1080, 1440, 2160)

@Composable
fun ProfilesScreen(graph: AppGraph, onBack: () -> Unit) {
    val profiles by graph.profiles.profiles.collectAsStateWithLifecycle()
    val selectedId by graph.profiles.selectedId.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Profile?>(null) }
    var pendingDelete by remember { mutableStateOf<Profile?>(null) }

    Column(Modifier.fillMaxSize()) {
        SettingsHeader("Perfiles", "El reproductor elige el stream por ti", onBack)
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
                    Text("Sin selector de webs", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "No eliges servidores ni fuentes: eliges un perfil. Con él, el núcleo puntúa todos " +
                            "los streams disponibles (idioma, calidad, HDR, tamaño y prioridad de plugins) y el " +
                            "reproductor arranca la mejor opción. Si falla, salta a la siguiente sin mostrar errores.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            editing = Profile(
                                id = "custom-" + System.currentTimeMillis(),
                                name = "",
                                description = "",
                                accent = ACCENTS.random(),
                                languages = listOf("latino"),
                                targetQuality = 1080,
                                maxQuality = 1080
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Rose, contentColor = Color.White)
                    ) {
                        Icon(Icons.Rounded.Plus, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Crear perfil")
                    }
                }
            }

            item { SectionHeader("Tus perfiles", "${profiles.size} disponibles") }

            items(profiles, key = { it.id }) { profile ->
                val active = profile.id == selectedId
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 5.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (active) PanelHigh else Panel)
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(12.dp).clip(CircleShape)
                                .background(colorFromHex(profile.accent))
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(profile.name, style = MaterialTheme.typography.titleMedium)
                            Text(profile.summary, style = MaterialTheme.typography.labelMedium)
                        }
                        if (active) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Rose.copy(alpha = 0.18f))
                                    .padding(horizontal = 9.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Rounded.Check, null, tint = Rose, modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Activo", color = Rose, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    if (profile.description.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(profile.description, style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!active) {
                            TextButton(onClick = { graph.profiles.select(profile.id) }) {
                                Text("Usar este perfil", color = Rose, fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { editing = profile }) {
                            Icon(Icons.Rounded.Edit, "Editar", tint = Muted, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { pendingDelete = profile }) {
                            Icon(Icons.Rounded.Delete, "Eliminar", tint = Rose.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            item {
                TextButton(
                    onClick = { graph.profiles.resetToDefaults() },
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                ) { Text("Restaurar perfiles recomendados", color = Muted) }
            }
        }
    }

    editing?.let { draft ->
        ProfileEditorSheet(
            initial = draft,
            onDismiss = { editing = null },
            onSave = { updated ->
                val error = graph.profiles.save(updated)
                if (error == null) {
                    editing = null
                    if (graph.profiles.profiles.value.none { it.id == graph.profiles.selectedId.value }) {
                        graph.profiles.select(updated.id)
                    }
                }
                error
            }
        )
    }

    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = Panel,
            title = { Text("Eliminar ${profile.name}") },
            text = { Text("El perfil se quitará de este dispositivo.", color = Muted) },
            confirmButton = {
                TextButton(onClick = {
                    graph.profiles.delete(profile.id)
                    pendingDelete = null
                }) { Text("Eliminar", color = Rose) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancelar", color = Muted) }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditorSheet(
    initial: Profile,
    onDismiss: () -> Unit,
    onSave: (Profile) -> String?
) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var error by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        contentColor = Text
    ) {
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(bottom = 26.dp)
        ) {
            item {
                Text(
                    if (initial.name.isBlank()) "Nuevo perfil" else "Editar perfil",
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.height(10.dp))
            }

            item {
                Field(
                    label = "Nombre",
                    value = draft.name,
                    onChange = { draft = draft.copy(name = it) },
                    placeholder = "Latino 1080p"
                )
                Spacer(Modifier.height(8.dp))
                Field(
                    label = "Descripción",
                    value = draft.description,
                    onChange = { draft = draft.copy(description = it) },
                    placeholder = "Audio latino, Full HD"
                )
                Spacer(Modifier.height(12.dp))
            }

            item {
                Text("Color", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ACCENTS.forEach { hex ->
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(colorFromHex(hex))
                                .then(
                                    if (draft.accent == hex) Modifier.border(2.dp, Color.White, CircleShape)
                                    else Modifier
                                )
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            item {
                Text("Idioma (en orden de preferencia)", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    LANGUAGES.take(3).forEach { (tag, label) ->
                        Chip(label, draft.languages.contains(tag)) {
                            draft = draft.copy(
                                languages = if (draft.languages.contains(tag)) draft.languages - tag
                                else draft.languages + tag
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    LANGUAGES.drop(3).forEach { (tag, label) ->
                        Chip(label, draft.languages.contains(tag)) {
                            draft = draft.copy(
                                languages = if (draft.languages.contains(tag)) draft.languages - tag
                                else draft.languages + tag
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            item {
                Text("Calidad objetivo", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    QUALITIES.forEach { quality ->
                        Chip("${quality}p", draft.targetQuality == quality) {
                            draft = draft.copy(
                                targetQuality = quality,
                                maxQuality = if (quality > draft.maxQuality) quality else draft.maxQuality
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Techo de calidad", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    QUALITIES.filter { it >= draft.targetQuality }.forEach { quality ->
                        Chip("${quality}p", draft.maxQuality == quality) {
                            draft = draft.copy(maxQuality = quality)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            item {
                ToggleRow("Permitir CAM", "Grabaciones de cine en sala", draft.allowCam) {
                    draft = draft.copy(allowCam = it)
                }
                ToggleRow("Preferir HDR", "Dolby Vision / HDR10 si existe", draft.preferHdr) {
                    draft = draft.copy(preferHdr = it)
                }
                ToggleRow("Preferir subtítulos", "Voz original con subtítulos", draft.preferSubtitles) {
                    draft = draft.copy(preferSubtitles = it)
                }
                ToggleRow("Preferir dual", "Dar prioridad al audio dual", draft.preferDual) {
                    draft = draft.copy(preferDual = it)
                }
                ToggleRow("Idioma estricto", "No reproducir si no coincide el idioma", draft.strictLanguage) {
                    draft = draft.copy(strictLanguage = it)
                }
                ToggleRow("Ahorro de datos", "Preferir archivos más ligeros", draft.dataSaver) {
                    draft = draft.copy(dataSaver = it)
                }
            }

            item {
                if (error.isNotBlank()) {
                    Text(error, color = Color(0xFFFF8A75), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            val message = onSave(draft)
                            error = message.orEmpty()
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Rose, contentColor = Color.White)
                    ) { Text("Guardar perfil") }
                    TextButton(onClick = onDismiss) { Text("Cancelar", color = Muted) }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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

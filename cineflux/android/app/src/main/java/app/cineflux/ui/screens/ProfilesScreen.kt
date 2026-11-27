package app.cineflux.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cineflux.AppGraph
import app.cineflux.core.Profile
import app.cineflux.ui.Field
import app.cineflux.ui.colorFromHex
import app.cineflux.ui.theme.Ink
import app.cineflux.ui.theme.Muted
import app.cineflux.ui.theme.Panel
import app.cineflux.ui.theme.PanelHigh
import app.cineflux.ui.theme.Rose
import app.cineflux.ui.theme.Stroke
import app.cineflux.ui.theme.Text

private val NETFLIX_AVATAR_COLORS = listOf(
    "#E50914", // Rojo Netflix
    "#00A8E8", // Azul clásico
    "#F5C518", // Amarillo clásico
    "#10B981", // Verde esmeralda
    "#8B5CF6", // Morado
    "#EC4899"  // Rosa
)

private val LANGUAGES = listOf(
    "latino" to "Latino",
    "castellano" to "Castellano",
    "es" to "Español",
    "dual" to "Dual",
    "en" to "Original",
    "sub" to "Subtítulos"
)

private val QUALITIES = listOf(480, 720, 1080, 1440, 2160)

/**
 * Avatar cuadrado estilo Netflix (sonrisa minimalista sobre degradado vibrante).
 */
@Composable
fun NetflixProfileAvatar(
    accentHex: String,
    size: Dp = 112.dp,
    selected: Boolean = false,
    editOverlay: Boolean = false,
    modifier: Modifier = Modifier
) {
    val base = colorFromHex(accentHex)
    val dark = Color(
        red = (base.red * 0.58f).coerceIn(0f, 1f),
        green = (base.green * 0.58f).coerceIn(0f, 1f),
        blue = (base.blue * 0.58f).coerceIn(0f, 1f),
        alpha = 1f
    )

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(listOf(base, dark)))
            .then(
                if (selected) Modifier.border(2.5.dp, Color.White, RoundedCornerShape(10.dp))
                else Modifier.border(1.dp, Stroke.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height
            val eyeRadius = w * 0.068f
            val eyeY = h * 0.37f
            // Ojos
            drawCircle(Color.White, radius = eyeRadius, center = Offset(w * 0.30f, eyeY))
            drawCircle(Color.White, radius = eyeRadius, center = Offset(w * 0.70f, eyeY))
            // Sonrisa estilo Netflix
            drawArc(
                color = Color.White,
                startAngle = 15f,
                sweepAngle = 150f,
                useCenter = false,
                topLeft = Offset(w * 0.22f, h * 0.40f),
                size = Size(w * 0.56f, h * 0.34f),
                style = DrawStroke(width = w * 0.075f, cap = StrokeCap.Round)
            )
        }

        if (editOverlay) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.52f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Edit,
                    contentDescription = "Editar perfil",
                    tint = Color.White,
                    modifier = Modifier.size(size * 0.30f)
                )
            }
        }
    }
}

@Composable
fun ProfilesScreen(graph: AppGraph, onBack: () -> Unit) {
    val profiles by graph.profiles.profiles.collectAsStateWithLifecycle()
    val selectedId by graph.profiles.selectedId.collectAsStateWithLifecycle()
    var manageMode by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Profile?>(null) }
    var pendingDelete by remember { mutableStateOf<Profile?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
    ) {
        // Barra superior estilo Netflix: Volver · ¿Quién está viendo? · Administrar
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, "Volver", tint = Text)
            }
            Text(
                if (manageMode) "Administrar perfiles" else "¿Quién está viendo?",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { manageMode = !manageMode }) {
                Text(
                    if (manageMode) "Listo" else "Editar",
                    color = Text,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            items(profiles, key = { it.id }) { profile ->
                val isSelected = profile.id == selectedId
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            if (manageMode) {
                                editing = profile
                            } else {
                                graph.profiles.select(profile.id)
                                onBack()
                            }
                        }
                        .padding(4.dp)
                ) {
                    NetflixProfileAvatar(
                        accentHex = profile.accent,
                        size = 112.dp,
                        selected = isSelected && !manageMode,
                        editOverlay = manageMode
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isSelected) Color.White else Muted,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        profile.summary,
                        style = MaterialTheme.typography.labelMedium,
                        color = Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Tarjeta "+ Añadir perfil" estilo Netflix
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            editing = Profile(
                                id = "custom-" + System.currentTimeMillis(),
                                name = "",
                                description = "",
                                accent = NETFLIX_AVATAR_COLORS.random(),
                                languages = listOf("latino"),
                                targetQuality = 1080,
                                maxQuality = 1080
                            )
                        }
                        .padding(4.dp)
                ) {
                    Box(
                        Modifier
                            .size(112.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Panel)
                            .border(1.5.dp, Stroke, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = "Añadir perfil",
                            tint = Text,
                            modifier = Modifier.size(44.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Añadir perfil",
                        style = MaterialTheme.typography.titleMedium,
                        color = Muted
                    )
                }
            }
        }

        // Pie discreto para restaurar perfiles por defecto
        Box(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            TextButton(onClick = { graph.profiles.resetToDefaults() }) {
                Text("Restaurar perfiles recomendados", color = Muted, fontSize = 12.sp)
            }
        }
    }

    editing?.let { initial ->
        val isExisting = profiles.any { it.id == initial.id }
        NetflixProfileEditorSheet(
            initial = initial,
            canDelete = isExisting && profiles.size > 1,
            onDismiss = { editing = null },
            onSave = { updated ->
                graph.profiles.save(updated)
                editing = null
            },
            onDelete = {
                editing = null
                pendingDelete = initial
            }
        )
    }

    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = Panel,
            title = { Text("Eliminar ${profile.name}") },
            text = { Text("Se eliminará este perfil de tu dispositivo.", color = Muted) },
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
private fun NetflixProfileEditorSheet(
    initial: Profile,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onSave: (Profile) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var accent by remember { mutableStateOf(initial.accent) }
    var languages by remember { mutableStateOf(initial.languages.toSet()) }
    var targetQuality by remember { mutableStateOf(initial.targetQuality) }
    var preferHdr by remember { mutableStateOf(initial.preferHdr) }
    var preferDual by remember { mutableStateOf(initial.preferDual) }
    var preferSubtitles by remember { mutableStateOf(initial.preferSubtitles) }
    var strictLanguage by remember { mutableStateOf(initial.strictLanguage) }
    var dataSaver by remember { mutableStateOf(initial.dataSaver) }
    var allowCam by remember { mutableStateOf(initial.allowCam) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Panel
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Avatar grande centrado + selector de color estilo Netflix
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                NetflixProfileAvatar(accentHex = accent, size = 96.dp, selected = true)
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
            ) {
                NETFLIX_AVATAR_COLORS.forEach { hex ->
                    val chosen = hex.equals(accent, ignoreCase = true)
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colorFromHex(hex))
                            .then(
                                if (chosen) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp))
                                else Modifier
                            )
                            .clickable { accent = hex },
                        contentAlignment = Alignment.Center
                    ) {
                        if (chosen) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Field(
                label = "Nombre del perfil",
                value = name,
                onChange = { name = it },
                placeholder = "Ej. Casa, Niños, 4K HDR…"
            )

            Spacer(Modifier.height(14.dp))
            Text("Idiomas preferidos", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LANGUAGES.forEach { (code, label) ->
                    val active = code in languages
                    TextButton(
                        onClick = {
                            languages = if (active) languages - code else languages + code
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) Rose else PanelHigh)
                    ) {
                        Text(label, color = if (active) Color.White else Muted, fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text("Calidad máxima", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                QUALITIES.forEach { q ->
                    val active = q == targetQuality
                    val label = if (q == 2160) "4K" else "${q}p"
                    TextButton(
                        onClick = { targetQuality = q },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) Rose else PanelHigh)
                    ) {
                        Text(label, color = if (active) Color.White else Muted, fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = Stroke, thickness = 0.5.dp)
            EditorToggle("Preferir HDR / Dolby Vision", preferHdr) { preferHdr = it }
            EditorToggle("Preferir audio Dual", preferDual) { preferDual = it }
            EditorToggle("Preferir subtítulos", preferSubtitles) { preferSubtitles = it }
            EditorToggle("Exigir idioma seleccionado", strictLanguage) { strictLanguage = it }
            EditorToggle("Ahorro de datos", dataSaver) { dataSaver = it }
            EditorToggle("Permitir copias CAM", allowCam) { allowCam = it }

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (canDelete) {
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Rounded.Delete, null, tint = Rose, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Eliminar", color = Rose)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancelar", color = Muted) }
                Button(
                    onClick = {
                        val finalName = name.trim().ifBlank { "Perfil" }
                        onSave(
                            initial.copy(
                                name = finalName,
                                accent = accent,
                                languages = languages.toList(),
                                targetQuality = targetQuality,
                                maxQuality = targetQuality,
                                preferHdr = preferHdr,
                                preferDual = preferDual,
                                preferSubtitles = preferSubtitles,
                                strictLanguage = strictLanguage,
                                dataSaver = dataSaver,
                                allowCam = allowCam
                            )
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Rose, contentColor = Color.White)
                ) {
                    Text("Guardar")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun EditorToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Rose)
        )
    }
}

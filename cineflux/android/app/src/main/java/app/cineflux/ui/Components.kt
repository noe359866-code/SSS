package app.cineflux.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cineflux.core.Profile
import app.cineflux.data.LibraryEntry
import app.cineflux.data.catalog.HomeRow
import app.cineflux.data.catalog.MediaItem
import app.cineflux.ui.theme.Golden
import app.cineflux.ui.theme.Muted
import app.cineflux.ui.theme.Panel
import app.cineflux.ui.theme.PanelHigh
import app.cineflux.ui.theme.Rose
import app.cineflux.ui.theme.Stroke
import app.cineflux.ui.theme.Text
import coil.compose.AsyncImage
import java.util.Locale
import kotlin.math.abs

fun colorFromHex(hex: String, fallback: Color = Rose): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)

fun formatRuntime(minutes: Int): String {
    if (minutes <= 0) return ""
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours <= 0 -> "${rest}min"
        rest == 0 -> "${hours}h"
        else -> "${hours}h ${rest}min"
    }
}

fun formatTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0) / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}

/** Póster con degradado generado a partir del título (también sirve de respaldo). */
@Composable
fun PosterGradient(seed: String, modifier: Modifier = Modifier, title: String? = null) {
    val palettes = listOf(
        listOf(Color(0xFF2B1055), Color(0xFF7597DE)),
        listOf(Color(0xFF42275A), Color(0xFF734B6D)),
        listOf(Color(0xFF0F2027), Color(0xFF2C5364)),
        listOf(Color(0xFF3A1C71), Color(0xFFD76D77)),
        listOf(Color(0xFF1F4037), Color(0xFF99F2C8)),
        listOf(Color(0xFF41295A), Color(0xFF2F0743))
    )
    val palette = palettes[abs(seed.hashCode()) % palettes.size]
    Box(
        modifier.background(
            Brush.linearGradient(listOf(palette[0].copy(alpha = 0.95f), palette[1].copy(alpha = 0.55f)))
        )
    ) {
        if (title != null) {
            Text(
                text = title,
                color = Color.White.copy(alpha = 0.9f),
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
                lineHeight = 20.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(14.dp)
            )
        }
    }
}

@Composable
fun ScoreBadges(item: MediaItem, modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item.tomato?.let { score ->
            ScorePill(text = "🍅 $score%", background = Color(0x33FF4D4D), content = Color(0xFFFFB3A8))
        }
        item.audience?.let { score ->
            ScorePill(text = "🍿 $score%", background = Color(0x33FFB05C), content = Color(0xFFFFD8A8))
        }
        if (item.scoreLabel.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0x66000000))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Icon(
                    Icons.Rounded.Star, contentDescription = null,
                    tint = Golden, modifier = Modifier.size(11.dp)
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    item.scoreLabel,
                    color = Text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold
                )
            }
        }
        if (!compact) {
            item.genres.firstOrNull()?.let { genre ->
                ScorePill(text = genre, background = Color(0x55000000), content = Muted)
            }
        }
    }
}

@Composable
private fun ScorePill(text: String, background: Color, content: Color) {
    Text(
        text = text,
        color = content,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    )
}

@Composable
fun PosterCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true
) {
    Column(modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(PanelHigh)
        ) {
            PosterGradient(
                seed = item.key,
                title = if (item.poster.isBlank()) item.title else null,
                modifier = Modifier.fillMaxSize()
            )
            if (item.poster.isNotBlank()) {
                AsyncImage(
                    model = item.poster,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.5f to Color.Transparent,
                            1f to Color(0xCC05060A)
                        )
                    )
            )
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(7.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item.tomato?.let {
                    ScorePill("🍅 $it%", Color(0xCC2A0B08), Color(0xFFFFB3A8))
                }
                if (item.tomato == null && item.scoreLabel.isNotBlank()) {
                    ScorePill(
                        "★ " + item.scoreLabel,
                        Color(0xCC1A1408), Golden
                    )
                }
            }
        }
        if (showTitle) {
            Spacer(Modifier.height(7.dp))
            Text(
                item.title,
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                listOfNotNull(
                    item.year.takeIf { it > 0 }?.toString(),
                    if (item.isSeries) "Serie" else "Película"
                ).joinToString(" · "),
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
fun MediaRow(row: HomeRow, onItemClick: (MediaItem) -> Unit, modifier: Modifier = Modifier) {
    if (row.items.isEmpty()) return
    Column(modifier.fillMaxWidth().padding(bottom = 22.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.title,
                        style = androidx.compose.material3.MaterialTheme.typography.headlineSmall
                    )
                }
                if (row.subtitle.isNotBlank()) {
                    Text(row.subtitle, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                }
            }
            if (row.badge.isNotBlank()) {
                Text(
                    row.badge,
                    color = if (row.badge == "RT") Color(0xFFFF8A75) else Muted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(PanelHigh)
                        .padding(horizontal = 7.dp, vertical = 4.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = Muted,
                modifier = Modifier.size(18.dp)
            )
        }
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(row.items, key = { it.key }) { item ->
                PosterCard(item = item, onClick = { onItemClick(item) })
            }
        }
    }
}

@Composable
fun ProfilePill(profile: Profile, onClick: () -> Unit) {
    val accent = colorFromHex(profile.accent)
    Row(
        Modifier
            .clip(CircleShape)
            .background(PanelHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
        Spacer(Modifier.width(6.dp))
        Text(profile.name, color = Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(2.dp))
        Icon(Icons.Rounded.KeyboardArrowRight, null, tint = Muted, modifier = Modifier.size(14.dp))
    }
}

@Composable
fun Hero(
    item: MediaItem,
    profile: Profile,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    onProfileClick: () -> Unit
) {
    Box(Modifier.fillMaxWidth().height(470.dp)) {
        PosterGradient(item.key, Modifier.fillMaxSize())
        if (item.backdrop.isNotBlank()) {
            AsyncImage(
                model = item.backdrop,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color(0x1A000000),
                    0.42f to Color(0x99000000),
                    1f to Color(0xFF000000)
                )
            )
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 16.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScorePill(
                    if (item.isSeries) "SERIE" else "PELÍCULA",
                    Color(0xCCFF4D67), Color.White
                )
                item.year.takeIf { it > 0 }?.let {
                    ScorePill(it.toString(), Color(0x99000000), Text)
                }
                if (item.tomato != null || item.audience != null) {
                    ScorePill("Rotten Tomatoes", Color(0xCC2A0B08), Color(0xFFFFB3A8))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                item.title,
                style = androidx.compose.material3.MaterialTheme.typography.displayLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            ScoreBadges(item)
            if (item.overview.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    item.overview,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = Text.copy(alpha = 0.82f)
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onPlay,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF0B0D12)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(44.dp)
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Reproducir", fontWeight = FontWeight.Bold)
                }
                Surface(
                    color = Color(0x66000000),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(44.dp).clickable(onClick = onDetails)
                ) {
                    Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Ver ficha", color = Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            ProfilePill(profile, onProfileClick)
        }
    }
}

@Composable
fun ContinueCard(entry: LibraryEntry, onClick: () -> Unit) {
    Column(
        Modifier.width(236.dp).clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(133.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(PanelHigh)
        ) {
            if (entry.item.backdrop.isNotBlank()) {
                AsyncImage(
                    model = entry.item.backdrop,
                    contentDescription = entry.item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                PosterGradient(entry.item.key, Modifier.fillMaxSize(), entry.item.title)
            }
            Box(Modifier.fillMaxSize().background(Color(0x44000000)))
            Text(
                if (entry.item.isSeries) "T${entry.season} E${entry.episode}" else "Película",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xAA000000))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            )
            LinearProgressIndicator(
                progress = { entry.progress },
                color = Rose,
                trackColor = Color(0x55FFFFFF),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            entry.item.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium
        )
        Text(
            "${(entry.progress * 100).toInt()}% visto",
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSheet(
    profiles: List<Profile>,
    selectedId: String,
    onSelect: (Profile) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        contentColor = Text
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 26.dp)) {
            Text(
                "Perfil de reproducción",
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "El reproductor elige el stream automáticamente según el perfil. " +
                    "No hay que escoger webs ni servidores.",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(14.dp))
            profiles.forEach { profile ->
                val selected = profile.id == selectedId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (selected) PanelHigh else Color.Transparent)
                        .clickable { onSelect(profile) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(colorFromHex(profile.accent)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(profile.name, fontWeight = FontWeight.SemiBold)
                        Text(profile.summary, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                    }
                    if (selected) Icon(Icons.Rounded.Check, null, tint = Rose)
                }
                HorizontalDivider(color = Stroke, thickness = 0.5.dp)
            }
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onManage, modifier = Modifier.align(Alignment.End)) {
                Text("Gestionar perfiles", color = Rose)
            }
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(PanelHigh),
            contentAlignment = Alignment.Center
        ) {
            Text("✦", color = Rose, fontSize = 24.sp)
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onAction,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Rose, contentColor = Color.White)
            ) { Text(actionLabel) }
        }
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) Rose else PanelHigh,
        contentColor = if (selected) Color.White else Muted,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    helper: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            placeholder = { if (placeholder.isNotBlank()) Text(placeholder, color = Muted) },
            singleLine = singleLine,
            minLines = minLines,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Rose,
                unfocusedBorderColor = Stroke,
                focusedLabelColor = Rose,
                unfocusedLabelColor = Muted,
                focusedTextColor = Text,
                unfocusedTextColor = Text,
                cursorColor = Rose,
                focusedContainerColor = PanelHigh,
                unfocusedContainerColor = PanelHigh
            ),
            modifier = Modifier.fillMaxWidth()
        )
        if (helper != null) {
            Text(
                helper,
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }
    }
}

@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Panel)
            .padding(16.dp),
        content = content
    )
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null) {
    Column(Modifier.padding(horizontal = 22.dp, vertical = 10.dp)) {
        Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
        if (subtitle != null) {
            Text(subtitle, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun ValueRow(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(subtitle, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
            }
        }
        if (onClick != null) Icon(Icons.Rounded.KeyboardArrowRight, null, tint = Muted)
    }
}

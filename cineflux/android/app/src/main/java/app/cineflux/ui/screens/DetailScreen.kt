package app.cineflux.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cineflux.AppGraph
import app.cineflux.core.Messages
import app.cineflux.core.Profile
import app.cineflux.data.catalog.DetailData
import app.cineflux.data.catalog.EpisodeInfo
import app.cineflux.data.catalog.MediaItem
import app.cineflux.ui.Chip
import app.cineflux.ui.EmptyState
import app.cineflux.ui.MediaRow
import app.cineflux.ui.PosterGradient
import app.cineflux.ui.ProfilePill
import app.cineflux.ui.ScoreBadges
import app.cineflux.ui.theme.Golden
import app.cineflux.ui.theme.Muted
import app.cineflux.ui.theme.Panel
import app.cineflux.ui.theme.PanelHigh
import app.cineflux.ui.theme.Rose
import app.cineflux.ui.theme.Text
import coil.compose.AsyncImage
import java.util.Locale

@Composable
fun DetailScreen(
    graph: AppGraph,
    mediaKey: String,
    mediaType: String,
    profile: Profile?,
    onBack: () -> Unit,
    onPlay: (MediaItem, Int, Int) -> Unit,
    onProfile: () -> Unit,
    onItem: (MediaItem) -> Unit
) {
    var detail by remember { mutableStateOf<DetailData?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var selectedSeason by remember { mutableIntStateOf(0) }
    var episodes by remember { mutableStateOf<List<EpisodeInfo>>(emptyList()) }
    var loadingEpisodes by remember { mutableStateOf(false) }

    val library by graph.library.entries.collectAsStateWithLifecycle()
    val activeProfile = profile ?: graph.profiles.selected
    val saved = library.any { it.item.key == mediaKey }

    LaunchedEffect(mediaKey, reload) {
        loading = true
        error = ""
        runCatching { graph.catalog.detail(mediaKey, mediaType) }
            .onSuccess {
                detail = it
                selectedSeason = it.seasons.firstOrNull()?.number ?: 0
            }
            .onFailure {
                error = Messages.scrub(it.message.orEmpty()).ifEmpty { Messages.generic }
            }
        loading = false
    }

    LaunchedEffect(detail?.item?.key, selectedSeason) {
        val item = detail?.item ?: return@LaunchedEffect
        if (!item.isSeries || selectedSeason <= 0) return@LaunchedEffect
        loadingEpisodes = true
        episodes = graph.catalog.seasonEpisodes(item, selectedSeason)
        loadingEpisodes = false
    }

    Box(Modifier.fillMaxSize()) {
        when {
            loading && detail == null -> FullScreenLoader()
            detail == null -> EmptyState(
                title = "No pudimos abrir la ficha",
                message = error.ifEmpty { Messages.notFound },
                actionLabel = "Reintentar",
                onAction = { reload++ }
            )
            else -> {
                val data = detail!!
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
                    item { DetailHeader(data.item) }

                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    onPlay(
                                        data.item,
                                        if (data.item.isSeries) selectedSeason else 0,
                                        if (data.item.isSeries) (episodes.firstOrNull()?.number ?: 1) else 0
                                    )
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White,
                                    contentColor = Color(0xFF0B0D12)
                                ),
                                modifier = Modifier.height(46.dp)
                            ) {
                                Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (data.item.isSeries) "Ver T$selectedSeason" else "Reproducir",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Surface(
                                color = if (saved) Rose.copy(alpha = 0.2f) else PanelHigh,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.height(46.dp).clickable { graph.library.toggle(data.item) }
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (saved) Icons.Rounded.Check else Icons.Rounded.Add,
                                        contentDescription = null,
                                        tint = if (saved) Rose else Text,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        if (saved) "En tu lista" else "Mi lista",
                                        color = Text,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Reproducción automática",
                                style = MaterialTheme.typography.labelMedium,
                                color = Muted
                            )
                            Spacer(Modifier.width(8.dp))
                            ProfilePill(activeProfile, onProfile)
                        }
                    }

                    item { ScoreTiles(data.item) }

                    if (data.item.overview.isNotBlank()) {
                        item {
                            Text(
                                data.item.overview,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                            )
                        }
                    }

                    item { MetaChips(data.item) }

                    if (data.item.cast.isNotEmpty()) {
                        item {
                            Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                                Text("Reparto", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    data.item.cast.joinToString(" · "),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }

                    if (data.item.isSeries && data.seasons.isNotEmpty()) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                                Text(
                                    "Temporadas",
                                    style = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                                )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 18.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(data.seasons, key = { it.number }) { season ->
                                        Chip(
                                            text = season.name,
                                            selected = season.number == selectedSeason,
                                            onClick = { selectedSeason = season.number }
                                        )
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                if (loadingEpisodes) {
                                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = Rose, modifier = Modifier.size(22.dp))
                                    }
                                } else {
                                    episodes.forEach { episode ->
                                        EpisodeRow(episode) {
                                            onPlay(data.item, selectedSeason, episode.number)
                                        }
                                    }
                                    if (episodes.isEmpty()) {
                                        Text(
                                            "Los episodios de esta temporada aparecerán aquí.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (data.similar.isNotEmpty()) {
                        item { Spacer(Modifier.height(16.dp)) }
                        item {
                            MediaRow(
                                row = app.cineflux.data.catalog.HomeRow(
                                    title = "Títulos parecidos",
                                    subtitle = "Según el catálogo",
                                    items = data.similar
                                ),
                                onItemClick = onItem
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(Color(0x99000000))
                ) {
                    Icon(Icons.Rounded.ArrowBack, "Volver", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun DetailHeader(item: MediaItem) {
    Box(Modifier.fillMaxWidth().height(360.dp)) {
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
                    0f to Color(0x44000000),
                    0.5f to Color(0xAA000000),
                    1f to Color(0xFF07080B)
                )
            )
        )
        Row(
            Modifier.align(Alignment.BottomStart).padding(horizontal = 18.dp).padding(bottom = 10.dp)
        ) {
            Box(
                Modifier
                    .width(96.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(PanelHigh)
            ) {
                PosterGradient(item.key, Modifier.fillMaxSize())
                if (item.poster.isNotBlank()) {
                    AsyncImage(
                        model = item.poster,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.headlineLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    listOfNotNull(
                        item.year.takeIf { it > 0 }?.toString(),
                        if (item.isSeries) {
                            "${item.seasons} temporada${if (item.seasons == 1) "" else "s"}"
                        } else {
                            app.cineflux.ui.formatRuntime(item.runtime)
                        },
                        item.originalTitle.takeIf { it.isNotBlank() && it != item.title }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = Muted
                )
                Spacer(Modifier.height(8.dp))
                ScoreBadges(item)
            }
        }
    }
}

@Composable
private fun ScoreTiles(item: MediaItem) {
    if (item.tomato == null && item.audience == null) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item.tomato?.let { score ->
            ScoreTile(
                value = "$score%",
                label = "Tomatometer",
                emoji = "🍅",
                tint = Color(0xFFFF8A75),
                modifier = Modifier.weight(1f)
            )
        }
        item.audience?.let { score ->
            ScoreTile(
                value = "$score%",
                label = "Audiencia",
                emoji = "🍿",
                tint = Golden,
                modifier = Modifier.weight(1f)
            )
        }
        if (item.scoreLabel.isNotBlank()) {
            ScoreTile(
                value = item.scoreLabel,
                label = "IMDb",
                emoji = "★",
                tint = Golden,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ScoreTile(
    value: String,
    label: String,
    emoji: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Panel)
            .padding(12.dp)
    ) {
        Text("$emoji $value", color = tint, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun MetaChips(item: MediaItem) {
    if (item.genres.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item.genres.take(4).forEach { genre ->
            Text(
                genre,
                style = MaterialTheme.typography.labelMedium,
                color = Muted,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(PanelHigh)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
private fun EpisodeRow(episode: EpisodeInfo, onPlay: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(96.dp)
                .height(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(PanelHigh)
        ) {
            if (episode.still.isNotBlank()) {
                AsyncImage(
                    model = episode.still,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                Modifier.align(Alignment.Center).size(26.dp).clip(CircleShape)
                    .background(Color(0x99000000)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${episode.number}. ${episode.title.ifBlank { "Episodio ${episode.number}" }}",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (episode.overview.isNotBlank()) {
                Text(
                    episode.overview,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (episode.runtime > 0) {
            Text(
                "${episode.runtime} min",
                style = MaterialTheme.typography.labelMedium,
                color = Muted
            )
        }
    }
}


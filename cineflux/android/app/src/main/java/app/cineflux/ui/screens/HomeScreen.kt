package app.cineflux.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cineflux.AppGraph
import app.cineflux.core.Messages
import app.cineflux.core.Profile
import app.cineflux.data.catalog.CatalogSnapshot
import app.cineflux.data.catalog.MediaItem
import app.cineflux.ui.ContinueCard
import app.cineflux.ui.EmptyState
import app.cineflux.ui.Hero
import app.cineflux.ui.MediaRow
import app.cineflux.ui.theme.Ink
import app.cineflux.ui.theme.Muted
import app.cineflux.ui.theme.PanelHigh
import app.cineflux.ui.theme.Rose
import app.cineflux.ui.theme.Stroke
import app.cineflux.ui.theme.Text
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    graph: AppGraph,
    profile: Profile?,
    onItem: (MediaItem) -> Unit,
    onPlay: (MediaItem, Int, Int) -> Unit,
    onProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlugins: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<CatalogSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }

    val continueWatching by graph.library.continueWatching.collectAsStateWithLifecycle()
    val plugins by graph.plugins.plugins.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val activeProfile = profile ?: graph.profiles.selected

    fun load(force: Boolean = false) {
        scope.launch {
            loading = true
            val result = graph.catalog.home(force = force)
            snapshot = result
            message = result.message
            loading = false
        }
    }

    LaunchedEffect(settings.catalogSources) { load() }

    if (!settings.hasCatalogSources) {
        SetupHome(
            onOpenSettings = onOpenSettings,
            onOpenPlugins = onOpenPlugins
        )
        return
    }

    LazyColumn(
        Modifier.fillMaxSize().background(Ink),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        if (loading && snapshot == null) {
            item { LoadingHero() }
            item { LoadingRow() }
            item { LoadingRow() }
        } else {
            val hero = snapshot?.rows?.firstOrNull()?.items?.firstOrNull()
            if (hero != null) {
                item {
                    Hero(
                        item = hero,
                        profile = activeProfile,
                        onPlay = {
                            if (hero.isSeries) onPlay(hero, 1, 1) else onPlay(hero, 0, 0)
                        },
                        onDetails = { onItem(hero) },
                        onProfileClick = onProfile
                    )
                }
            }

            if (continueWatching.isNotEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 24.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Continuar viendo",
                                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "En este dispositivo",
                                style = androidx.compose.material3.MaterialTheme.typography.labelMedium
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(continueWatching, key = { it.item.key }) { entry ->
                                ContinueCard(entry) {
                                    onPlay(entry.item, entry.season, entry.episode)
                                }
                            }
                        }
                    }
                }
            }

            snapshot?.rows?.drop(1)?.forEach { row ->
                item { MediaRow(row, onItem) }
            }

            if (snapshot?.rows.isNullOrEmpty()) {
                item {
                    EmptyState(
                        title = "No hemos podido cargar el catálogo",
                        message = message.ifBlank { Messages.network },
                        actionLabel = "Reintentar",
                        onAction = { load(force = true) }
                    )
                }
            }

            if (plugins.isEmpty()) {
                item { FirstPluginCard(onOpenPlugins) }
            }

            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp)) {
                    Text(
                        "Catálogo actualizado · Saltos de intro y créditos",
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium
                    )
                    Text(
                        "Cineflux no incluye scrapers. Cada plugin lo instalas tú.",
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        color = Muted.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupHome(onOpenSettings: () -> Unit, onOpenPlugins: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1A0004), Ink, Ink)
                )
            )
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(48.dp))
            Text(
                "Tu cine.\nSin elegir servidores.",
                style = androidx.compose.material3.MaterialTheme.typography.displayLarge,
                lineHeight = 46.sp
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "Añade tus fuentes de catálogo y un plugin de reproducción. El reproductor " +
                    "elegirá la mejor opción por ti y saltará intros y créditos.",
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                color = Muted
            )
            Spacer(Modifier.height(26.dp))
            Button(
                onClick = onOpenSettings,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF0B0B0D)
                ),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("Añadir catálogo", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(PanelHigh)
                    .clickable(onClick = onOpenPlugins),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text("Instalar un plugin", color = Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            Spacer(Modifier.height(30.dp))
            SetupFeature("Catálogo real", "Tendencias, populares y fichas completas, sin demos.")
            SetupFeature("Reproducción automática", "Sin listas de webs ni fuentes: un perfil decide todo.")
            SetupFeature("Saltos inteligentes", "TheIntroDB, IntroDB y AnimeSkip detectan intro, recap y créditos.")
            Spacer(Modifier.weight(1f))
            Text(
                "Sin demos. Lo que ves es tu catálogo.",
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(bottom = 22.dp)
            )
        }
    }
}

@Composable
private fun SetupFeature(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(Rose)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun FirstPluginCard(onOpenPlugins: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1C0A0D), Color(0xFF0E0E10))))
            .padding(18.dp)
    ) {
        Text("Sin plugins de reproducción", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "Instala un plugin con su enlace y el reproductor empezará a resolver opciones por ti.",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onOpenPlugins,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Rose, contentColor = Color.White)
        ) { Text("Instalar el primero") }
    }
}

@Composable
private fun LoadingHero() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(470.dp)
            .background(Brush.verticalGradient(listOf(PanelHigh, Ink)))
    ) {
        Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
            Box(Modifier.width(240.dp).height(30.dp).clip(RoundedCornerShape(6.dp)).background(Stroke))
            Spacer(Modifier.height(10.dp))
            Box(Modifier.width(150.dp).height(14.dp).clip(RoundedCornerShape(6.dp)).background(PanelHigh))
            Spacer(Modifier.height(16.dp))
            Box(Modifier.width(140.dp).height(46.dp).clip(RoundedCornerShape(10.dp)).background(Stroke))
        }
    }
}

@Composable
private fun LoadingRow() {
    Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) {
            Box(
                Modifier
                    .width(128.dp)
                    .height(192.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(PanelHigh)
            )
        }
    }
}

@Composable
fun FullScreenLoader() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Rose)
    }
}

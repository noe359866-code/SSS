package app.cineflux.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cineflux.AppGraph
import app.cineflux.data.catalog.MediaItem
import app.cineflux.ui.ContinueCard
import app.cineflux.ui.EmptyState
import app.cineflux.ui.PosterCard
import app.cineflux.ui.theme.Rose

@Composable
fun LibraryScreen(
    graph: AppGraph,
    onItem: (MediaItem) -> Unit,
    onPlay: (MediaItem, Int, Int) -> Unit
) {
    val entries by graph.library.savedItems.collectAsStateWithLifecycle()
    val continueWatching by graph.library.continueWatching.collectAsStateWithLifecycle()

    if (entries.isEmpty()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                "Mi lista",
                style = androidx.compose.material3.MaterialTheme.typography.displayLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
            )
            EmptyState(
                title = "Tu lista está vacía",
                message = "Añade títulos desde su ficha para tenerlos a mano. El progreso se guarda solo en este dispositivo."
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(horizontal = 6.dp, vertical = 10.dp)) {
                Text("Mi lista", style = androidx.compose.material3.MaterialTheme.typography.displayLarge)
                Text(
                    "${entries.size} títulos guardados",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                )
            }
        }

        if (continueWatching.isNotEmpty()) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(
                        "Continuar viendo",
                        style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp)
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(continueWatching, key = { it.item.key }) { entry ->
                            ContinueCard(entry) { onPlay(entry.item, entry.season, entry.episode) }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "Guardados",
                        style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp)
                    )
                }
            }
        }

        items(entries, key = { it.item.key }) { entry ->
            Column {
                PosterCard(
                    item = entry.item,
                    onClick = { onItem(entry.item) },
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = { graph.library.remove(entry.item.key) }) {
                    Text("Quitar", color = Rose, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

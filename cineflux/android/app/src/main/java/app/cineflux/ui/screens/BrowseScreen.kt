package app.cineflux.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cineflux.AppGraph
import app.cineflux.core.Messages
import app.cineflux.data.catalog.HomeRow
import app.cineflux.data.catalog.MediaItem
import app.cineflux.ui.EmptyState
import app.cineflux.ui.MediaRow
import app.cineflux.ui.PosterCard
import app.cineflux.ui.theme.Muted
import app.cineflux.ui.theme.PanelHigh
import app.cineflux.ui.theme.Rose
import app.cineflux.ui.theme.Stroke
import app.cineflux.ui.theme.Text
import kotlinx.coroutines.delay

@Composable
fun BrowseScreen(
    graph: AppGraph,
    type: String,
    onItem: (MediaItem) -> Unit
) {
    var rows by remember { mutableStateOf<List<HomeRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(type) {
        loading = true
        rows = runCatching { graph.catalog.browse(type) }.getOrDefault(emptyList())
        loading = false
    }

    if (loading) {
        FullScreenLoader()
        return
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text(
                    if (type == "series") "Series" else "Películas",
                    style = androidx.compose.material3.MaterialTheme.typography.displayLarge
                )
                Text(
                    "Puntuaciones de IMDb y Rotten Tomatoes",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                )
            }
        }
        if (rows.isEmpty()) {
            item {
                EmptyState(
                    title = "No hay títulos ahora mismo",
                    message = Messages.network
                )
            }
        }
        rows.forEach { row -> item { MediaRow(row, onItem) } }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
fun SearchScreen(
    graph: AppGraph,
    onItem: (MediaItem) -> Unit,
    onBack: () -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    val settings by graph.settings.state.collectAsStateWithLifecycle()

    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
            searched = false
            return@LaunchedEffect
        }
        delay(320)
        loading = true
        results = runCatching { graph.catalog.search(query) }.getOrDefault(emptyList())
        loading = false
        searched = true
    }

    Column(Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, "Volver", tint = Text)
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Busca películas y series", color = Muted) },
                singleLine = true,
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Rounded.Close, "Borrar", tint = Muted)
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Rose,
                    unfocusedBorderColor = Stroke,
                    focusedTextColor = Text,
                    unfocusedTextColor = Text,
                    cursorColor = Rose,
                    focusedContainerColor = PanelHigh,
                    unfocusedContainerColor = PanelHigh
                ),
                modifier = Modifier.weight(1f)
            )
        }


        when {
            loading -> FullScreenLoader()
            query.isBlank() -> EmptyState(
                title = "¿Qué quieres ver?",
                message = "Busca por título, saga o género. Los resultados se abren con tu perfil activo."
            )
            searched && results.isEmpty() -> EmptyState(
                title = "Sin resultados",
                message = "No encontramos «$query». Prueba con otro título."
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(results, key = { it.key }) { item ->
                    PosterCard(item = item, onClick = { onItem(item) }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

package app.cineflux.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.cineflux.AppGraph
import app.cineflux.core.AppJson
import app.cineflux.data.catalog.MediaItem
import app.cineflux.player.PlayerActivity
import app.cineflux.ui.screens.AboutScreen
import app.cineflux.ui.screens.CatalogSettingsScreen
import app.cineflux.ui.screens.DataSettingsScreen
import app.cineflux.ui.screens.DetailScreen
import app.cineflux.ui.screens.HomeScreen
import app.cineflux.ui.screens.LibraryScreen
import app.cineflux.ui.screens.NetworkSettingsScreen
import app.cineflux.ui.screens.PluginsScreen
import app.cineflux.ui.screens.ProfilesScreen
import app.cineflux.ui.screens.SearchScreen
import app.cineflux.ui.screens.SettingsScreen
import app.cineflux.ui.screens.SkipSettingsScreen
import app.cineflux.ui.screens.BrowseScreen
import app.cineflux.ui.theme.Ink
import app.cineflux.ui.theme.Muted
import app.cineflux.ui.theme.PanelHigh
import app.cineflux.ui.theme.Rose
import app.cineflux.ui.theme.Text

@Composable
fun CinefluxRoot(graph: AppGraph = AppGraph.shared) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val profiles by graph.profiles.profiles.collectAsStateWithLifecycle()
    val selectedId by graph.profiles.selectedId.collectAsStateWithLifecycle()
    val selectedProfile = profiles.firstOrNull { it.id == selectedId } ?: profiles.firstOrNull()
    var profileSheet by remember { mutableStateOf(false) }

    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "home"
    val showBars = route in setOf("home", "movies", "series", "list")

    fun play(item: MediaItem, season: Int = 0, episode: Int = 0) {
        val intent = Intent(context, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_ITEM, AppJson.encodeToString(MediaItem.serializer(), item))
            .putExtra(PlayerActivity.EXTRA_SEASON, season)
            .putExtra(PlayerActivity.EXTRA_EPISODE, episode)
        context.startActivity(intent)
    }

    Scaffold(
        containerColor = Ink,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (showBars) {
                AppTopBar(
                    profileName = selectedProfile?.name ?: "Perfil",
                    accent = selectedProfile?.accent ?: "#22d3ee",
                    onProfile = { profileSheet = true },
                    onSearch = { nav.navigate("search") { launchSingleTop = true } },
                    onSettings = { nav.navigate("settings") { launchSingleTop = true } }
                )
            }
        },
        bottomBar = {
            if (showBars) {
                AppBottomBar(
                    route = route,
                    onNavigate = { destination ->
                        nav.navigate(destination) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(navController = nav, startDestination = "home") {
                composable("home") {
                    HomeScreen(
                        graph = graph,
                        profile = selectedProfile,
                        onItem = { item -> nav.navigate("detail/${item.type}/${encode(item.key)}") },
                        onPlay = { item, season, episode -> play(item, season, episode) },
                        onProfile = { profileSheet = true },
                        onOpenSettings = { nav.navigate("settings") },
                        onOpenPlugins = { nav.navigate("settings/plugins") }
                    )
                }
                composable("movies") {
                    BrowseScreen(
                        graph = graph,
                        type = "movie",
                        onItem = { item -> nav.navigate("detail/${item.type}/${encode(item.key)}") }
                    )
                }
                composable("series") {
                    BrowseScreen(
                        graph = graph,
                        type = "series",
                        onItem = { item -> nav.navigate("detail/${item.type}/${encode(item.key)}") }
                    )
                }
                composable("list") {
                    LibraryScreen(
                        graph = graph,
                        onItem = { item -> nav.navigate("detail/${item.type}/${encode(item.key)}") },
                        onPlay = { item, season, episode -> play(item, season, episode) }
                    )
                }
                composable("search") {
                    SearchScreen(
                        graph = graph,
                        onItem = { item -> nav.navigate("detail/${item.type}/${encode(item.key)}") },
                        onBack = { nav.popBackStack() }
                    )
                }
                composable("detail/{type}/{key}") { entry ->
                    val type = entry.arguments?.getString("type") ?: "movie"
                    val key = decode(entry.arguments?.getString("key") ?: "")
                    DetailScreen(
                        graph = graph,
                        mediaKey = key,
                        mediaType = type,
                        profile = selectedProfile,
                        onBack = { nav.popBackStack() },
                        onPlay = { item, season, episode -> play(item, season, episode) },
                        onProfile = { profileSheet = true },
                        onItem = { item -> nav.navigate("detail/${item.type}/${encode(item.key)}") }
                    )
                }
                composable("settings") {
                    SettingsScreen(
                        graph = graph,
                        onBack = { nav.popBackStack() },
                        onNavigate = { nav.navigate(it) }
                    )
                }
                composable("settings/plugins") {
                    PluginsScreen(graph = graph, onBack = { nav.popBackStack() })
                }
                composable("settings/profiles") {
                    ProfilesScreen(graph = graph, onBack = { nav.popBackStack() })
                }
                composable("settings/skips") {
                    SkipSettingsScreen(graph = graph, onBack = { nav.popBackStack() })
                }
                composable("settings/catalog") {
                    CatalogSettingsScreen(graph = graph, onBack = { nav.popBackStack() })
                }
                composable("settings/network") {
                    NetworkSettingsScreen(graph = graph, onBack = { nav.popBackStack() })
                }
                composable("settings/data") {
                    DataSettingsScreen(graph = graph, onBack = { nav.popBackStack() })
                }
                composable("settings/about") {
                    AboutScreen(graph = graph, onBack = { nav.popBackStack() })
                }
            }
        }
    }

    if (profileSheet && selectedProfile != null) {
        ProfileSheet(
            profiles = profiles,
            selectedId = selectedProfile.id,
            onSelect = {
                graph.profiles.select(it.id)
                profileSheet = false
            },
            onManage = {
                profileSheet = false
                nav.navigate("settings/profiles")
            },
            onDismiss = { profileSheet = false }
        )
    }
}

@Composable
private fun AppTopBar(
    profileName: String,
    accent: String,
    onProfile: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Ink)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Rose))
        Spacer(Modifier.width(8.dp))
        Text(
            "CINEFLUX",
            color = Text,
            fontWeight = FontWeight.Black,
            fontSize = 16.sp,
            letterSpacing = 3.sp
        )
        Spacer(Modifier.width(10.dp))
        Row(
            Modifier
                .clip(CircleShape)
                .background(PanelHigh)
                .padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(colorFromHex(accent)))
            Spacer(Modifier.width(6.dp))
            Text(profileName, color = Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onSearch) {
            Icon(Icons.Rounded.Search, "Buscar", tint = Text)
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Rounded.Settings, "Ajustes", tint = Muted)
        }
    }
}

@Composable
private fun AppBottomBar(route: String, onNavigate: (String) -> Unit) {
    val items = listOf(
        Triple("home", "Inicio", Icons.Rounded.Home),
        Triple("movies", "Películas", Icons.Rounded.Movie),
        Triple("series", "Series", Icons.Rounded.Tv),
        Triple("list", "Mi lista", Icons.Rounded.Bookmark)
    )
    Box(Modifier.background(Ink)) {
        NavigationBar(
            containerColor = Color(0xFF0B0B0D),
            modifier = Modifier.navigationBarsPadding()
        ) {
            items.forEach { (destination, label, icon) ->
                NavigationBarItem(
                    selected = route == destination,
                    onClick = { onNavigate(destination) },
                    icon = { Icon(icon, label, modifier = Modifier.size(22.dp)) },
                    label = { Text(label, fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = Color.White,
                        indicatorColor = Color(0x24FFFFFF),
                        unselectedIconColor = Muted,
                        unselectedTextColor = Muted
                    )
                )
            }
        }
    }
}

private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

private fun decode(value: String): String = java.net.URLDecoder.decode(value, "UTF-8")

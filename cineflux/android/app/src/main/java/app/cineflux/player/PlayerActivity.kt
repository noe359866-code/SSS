package app.cineflux.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.cineflux.AppGraph
import app.cineflux.core.AppJson
import app.cineflux.data.catalog.MediaItem
import app.cineflux.ui.ProfileSheet
import app.cineflux.ui.formatTime
import app.cineflux.ui.theme.Ink
import app.cineflux.ui.theme.Muted
import app.cineflux.ui.theme.PanelHigh
import app.cineflux.ui.theme.Rose
import app.cineflux.ui.theme.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import kotlinx.coroutines.delay
import kotlinx.serialization.decodeFromString

class PlayerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        val raw = intent.getStringExtra(EXTRA_ITEM)
        val item = raw?.let { runCatching { AppJson.decodeFromString<MediaItem>(it) }.getOrNull() }
        if (item == null) {
            finish()
            return
        }
        val season = intent.getIntExtra(EXTRA_SEASON, 0)
        val episode = intent.getIntExtra(EXTRA_EPISODE, 0)

        setContent {
            app.cineflux.ui.theme.CinefluxTheme {
                PlayerRoute(
                    item = item,
                    season = season,
                    episode = episode,
                    onClose = { finish() }
                )
            }
        }
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        const val EXTRA_ITEM = "app.cineflux.extra.ITEM"
        const val EXTRA_SEASON = "app.cineflux.extra.SEASON"
        const val EXTRA_EPISODE = "app.cineflux.extra.EPISODE"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerRoute(
    item: MediaItem,
    season: Int,
    episode: Int,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val engine = remember { PlaybackEngine(context, AppGraph.shared, item, season, episode) }
    val profiles by AppGraph.shared.profiles.profiles.collectAsStateWithLifecycle()

    var controlsVisible by remember { mutableStateOf(true) }
    var showProfiles by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(Unit) { engine.start(scope) }

    DisposableEffect(Unit) {
        onDispose { engine.release() }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                engine.player.pause()
                engine.save()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(engine.isPlaying, controlsVisible, engine.phase) {
        if (engine.isPlaying && controlsVisible && engine.phase == PlaybackPhase.Playing) {
            delay(4200)
            controlsVisible = false
        }
    }

    BackHandler {
        when {
            showProfiles -> showProfiles = false
            landscape -> activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else -> onClose()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = engine.player
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(AndroidColor.BLACK)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Capa para mostrar/ocultar controles.
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { controlsVisible = !controlsVisible }
        )

        when (engine.phase) {
            PlaybackPhase.Searching -> SearchingOverlay(engine)
            PlaybackPhase.Empty -> EmptyOverlay(
                engine = engine,
                onChangeProfile = { showProfiles = true },
                onRetry = { engine.retry() },
                onClose = onClose
            )
            PlaybackPhase.Playing -> {
                AnimatedVisibility(
                    visible = controlsVisible,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Column(Modifier.fillMaxSize()) {
                        PlayerTopBar(
                            engine = engine,
                            season = season,
                            episode = episode,
                            onBack = onClose,
                            onProfile = { showProfiles = true }
                        )
                        Spacer(Modifier.weight(1f))
                        if (engine.buffering) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                            }
                            Spacer(Modifier.weight(1f))
                        } else if (!engine.isPlaying) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                IconButton(
                                    onClick = { engine.togglePlay() },
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .background(Color(0x99000000))
                                ) {
                                    Icon(
                                        Icons.Rounded.PlayArrow, "Reproducir",
                                        tint = Color.White, modifier = Modifier.size(38.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.weight(1f))
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        PlayerControls(
                            engine = engine,
                            dragging = dragging,
                            dragValue = dragValue,
                            onDrag = { dragging = true; dragValue = it },
                            onDragFinished = {
                                engine.seekTo(dragValue.toLong())
                                dragging = false
                            },
                            landscape = landscape,
                            onToggleFullscreen = {
                                activity?.requestedOrientation = if (landscape) {
                                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                } else {
                                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                }
                            }
                        )
                    }
                }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .padding(end = 18.dp, bottom = 92.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
            val segment = engine.activeSkip
            if (engine.phase == PlaybackPhase.Playing && segment != null) {
                SkipChip(segment = segment, onClick = { engine.skipActive() })
            }
        }
    }

    if (showProfiles) {
        ProfileSheet(
            profiles = profiles,
            selectedId = engine.profile.id,
            onSelect = {
                engine.changeProfile(it)
                showProfiles = false
                controlsVisible = true
            },
            onManage = { showProfiles = false },
            onDismiss = { showProfiles = false }
        )
    }
}

@Composable
private fun SkipChip(segment: SkipSegment, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xF2F2F2F7))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            segment.label,
            color = Color(0xFF0B0B0D),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = Color(0xFF0B0B0D),
            modifier = Modifier.size(17.dp)
        )
    }
}

@Composable
private fun SearchingOverlay(engine: PlaybackEngine) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xE6000000), Color(0xF2000000))))
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = Rose, strokeWidth = 3.dp)
        Spacer(Modifier.height(18.dp))
        Text(
            "Preparando tu perfil",
            style = MaterialTheme.typography.headlineSmall,
            color = Text
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Buscando la mejor opción para ${engine.profile.name}…",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "No tienes que elegir fuente: el reproductor lo hace por ti.",
            style = MaterialTheme.typography.labelMedium,
            color = Muted.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun EmptyOverlay(
    engine: PlaybackEngine,
    onChangeProfile: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xF707080B))
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(58.dp).clip(CircleShape).background(PanelHigh),
            contentAlignment = Alignment.Center
        ) { Text("◎", color = Rose, fontSize = 26.sp) }
        Spacer(Modifier.height(16.dp))
        Text("Sin opción para este perfil", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            engine.message,
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Rose, contentColor = Color.White)
            ) { Text("Reintentar") }
            TextButton(onClick = onChangeProfile) { Text("Cambiar perfil", color = Text) }
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onClose) { Text("Volver", color = Muted) }
    }
}

@Composable
private fun PlayerTopBar(
    engine: PlaybackEngine,
    season: Int,
    episode: Int,
    onBack: () -> Unit,
    onProfile: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xE6000000), Color.Transparent)))
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Rounded.ArrowBack, "Volver", tint = Color.White)
        }
        Column(Modifier.weight(1f)) {
            Text(
                engine.mediaItem.title,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    if (season > 0) append("T$season")
                    if (episode > 0) append(" · E$episode")
                    if (season > 0) append(" · ")
                    append(engine.profile.name)
                    engine.current?.label?.takeIf { it.isNotBlank() }?.let {
                        append(" · ")
                        append(it)
                    }
                },
                color = Muted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            Modifier
                .clip(CircleShape)
                .background(Color(0x66000000))
                .clickable(onClick = onProfile)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Perfil", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(4.dp))
            Text(engine.profile.name, color = Rose, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PlayerControls(
    engine: PlaybackEngine,
    dragging: Boolean,
    dragValue: Float,
    onDrag: (Float) -> Unit,
    onDragFinished: () -> Unit,
    landscape: Boolean,
    onToggleFullscreen: () -> Unit
) {
    val duration = engine.durationMs
    val max = if (duration > 1000) duration.toFloat() else 1f
    val position = if (dragging) dragValue else engine.positionMs.toFloat().coerceIn(0f, max)

    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        if (duration > 1000) {
            Slider(
                value = position,
                onValueChange = onDrag,
                onValueChangeFinished = onDragFinished,
                valueRange = 0f..max,
                colors = SliderDefaults.colors(
                    thumbColor = Rose,
                    activeTrackColor = Rose,
                    inactiveTrackColor = Color(0x44FFFFFF)
                ),
                modifier = Modifier.fillMaxWidth().height(24.dp)
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatTime(position.toLong()), color = Color.White, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text(formatTime(duration), color = Muted, fontSize = 11.sp)
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { engine.seekBy(-10_000) }) {
                Icon(Icons.Rounded.Replay10, "Retroceder 10 s", tint = Color.White)
            }
            IconButton(
                onClick = { engine.togglePlay() },
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color(0x33FFFFFF))
            ) {
                Icon(
                    if (engine.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    "Reproducir o pausar",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp)
                )
            }
            IconButton(onClick = { engine.seekBy(10_000) }) {
                Icon(Icons.Rounded.Forward10, "Avanzar 10 s", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { engine.toggleSubtitles() }) {
                Icon(
                    Icons.Rounded.ClosedCaption,
                    "Subtítulos",
                    tint = if (engine.subtitlesEnabled) Rose else Muted
                )
            }
            IconButton(onClick = onToggleFullscreen) {
                Icon(
                    if (landscape) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                    "Pantalla completa",
                    tint = Color.White
                )
            }
        }
    }
}

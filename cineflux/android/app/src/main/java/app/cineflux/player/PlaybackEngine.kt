package app.cineflux.player

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.cineflux.AppGraph
import app.cineflux.core.Messages
import app.cineflux.core.Profile
import app.cineflux.core.SkipSegment
import app.cineflux.core.StreamChoice
import app.cineflux.data.catalog.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class PlaybackPhase { Searching, Playing, Empty }

/**
 * Motor de reproducción: pide streams a los plugins, deja que el núcleo C++
 * elija la cola y reproduce el primero. Si una opción falla, salta a la
 * siguiente en silencio; al usuario solo se le muestra el perfil, la calidad
 * y un mensaje humano si no queda nada.
 */
class PlaybackEngine(
    context: Context,
    private val graph: AppGraph,
    initialItem: MediaItem,
    private val season: Int,
    private val episode: Int
) {
    var mediaItem by mutableStateOf(initialItem)
        private set
    val player: ExoPlayer = ExoPlayer.Builder(context).build()

    var phase by mutableStateOf(PlaybackPhase.Searching)
        private set
    var message by mutableStateOf("")
        private set
    var profile by mutableStateOf(graph.profiles.selected)
        private set
    var candidates by mutableStateOf<List<StreamChoice>>(emptyList())
        private set
    var current by mutableStateOf<StreamChoice?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(false)
        private set
    var subtitlesEnabled by mutableStateOf(true)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var attempt by mutableIntStateOf(0)
        private set
    var skipSegments by mutableStateOf<List<SkipSegment>>(emptyList())
        private set
    var activeSkip by mutableStateOf<SkipSegment?>(null)
        private set

    private var scope: CoroutineScope? = null
    private var watchdog: Job? = null
    private var bufferingSince = 0L
    private var skipLoaded = false
    private var skipToken = 0
    private val consumedSkips = mutableSetOf<String>()
    private var lastSavedAt = 0L
    private var released = false

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
        }

        override fun onPlaybackStateChanged(state: Int) {
            buffering = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY) {
                bufferingSince = 0
                if (phase == PlaybackPhase.Searching) phase = PlaybackPhase.Playing
            }
            if (state == Player.STATE_ENDED) {
                save()
                isPlaying = false
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // Silencio absoluto: se prueba la siguiente opción de la cola.
            failover()
        }
    }

    init {
        player.addListener(listener)
        player.playWhenReady = true
    }

    fun start(scope: CoroutineScope) {
        this.scope = scope
        if (watchdog == null) startWatchdog(scope)
        load()
    }

    fun load() {
        val scope = scope ?: return
        phase = PlaybackPhase.Searching
        message = ""
        current = null
        buffering = true
        bufferingSince = System.currentTimeMillis()
        attempt = 0
        candidates = emptyList()
        skipSegments = emptyList()
        activeSkip = null
        skipLoaded = false
        skipToken += 1
        consumedSkips.clear()

        scope.launch {
            try {
                val enriched = graph.catalog.identifiers(mediaItem)
                mediaItem = enriched
                val result = graph.plugins.resolve(
                    type = enriched.type,
                    imdbId = enriched.imdbId,
                    tmdbId = enriched.tmdbId,
                    season = season,
                    episode = episode,
                    profile = profile
                )
                if (released) return@launch
                candidates = result.candidates
                if (candidates.isEmpty()) {
                    showEmpty(result.message)
                } else {
                    playAt(0)
                }
            } catch (t: Throwable) {
                if (!released) showEmpty(Messages.generic)
            }
        }
    }

    fun retry() = load()

    fun changeProfile(next: Profile) {
        profile = next
        graph.profiles.select(next.id)
        load()
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekTo(ms: Long) {
        val target = ms.coerceAtLeast(0)
        player.seekTo(target)
        positionMs = target
    }

    fun seekBy(deltaMs: Long) {
        val max = if (durationMs > 0) durationMs else positionMs + deltaMs
        seekTo((positionMs + deltaMs).coerceIn(0, max))
    }

    fun toggleSubtitles() {
        subtitlesEnabled = !subtitlesEnabled
        applySubtitlePreference()
    }

    /** El usuario pulsó «Saltar intro/recap/créditos». */
    fun skipActive() {
        val segment = activeSkip ?: return
        consumedSkips.add("${segment.kind}|${segment.startMs}")
        seekTo(segment.endMs + 250)
        activeSkip = null
    }

    fun save() {
        val position = runCatching { player.currentPosition }.getOrDefault(0L)
        val duration = runCatching { player.duration }.getOrDefault(0L)
        if (position > 5_000 && duration > 0) {
            graph.library.recordProgress(mediaItem, position, duration, season, episode)
            lastSavedAt = System.currentTimeMillis()
        }
    }

    fun release() {
        if (released) return
        released = true
        watchdog?.cancel()
        save()
        player.removeListener(listener)
        player.release()
    }

    private fun playAt(index: Int) {
        val choice = candidates.getOrNull(index)
        if (choice == null) {
            save()
            showEmpty(Messages.playbackExhausted)
            return
        }
        attempt = index + 1
        current = choice
        phase = PlaybackPhase.Playing
        buffering = true
        bufferingSince = System.currentTimeMillis()
        runCatching {
            player.setMediaItem(choice.toExoMediaItem())
            player.prepare()
            player.playWhenReady = true
            applySubtitlePreference()
        }.onFailure { failover() }
    }

    private fun failover() {
        if (released) return
        bufferingSince = 0
        val next = attempt
        if (candidates.getOrNull(next) == null) {
            save()
            showEmpty(Messages.playbackExhausted)
        } else {
            playAt(next)
        }
    }

    private fun showEmpty(text: String) {
        phase = PlaybackPhase.Empty
        message = Messages.scrub(text.ifBlank { Messages.noOptions }).ifBlank { Messages.noOptions }
        buffering = false
        isPlaying = false
        bufferingSince = 0
    }

    private fun startWatchdog(scope: CoroutineScope) {
        watchdog = scope.launch {
            while (!released) {
                delay(800)
                if (released) break
                val state = runCatching { player.playbackState }.getOrDefault(Player.STATE_IDLE)
                buffering = state == Player.STATE_BUFFERING
                isPlaying = runCatching { player.isPlaying }.getOrDefault(false)
                positionMs = runCatching { player.currentPosition }.getOrDefault(0L).coerceAtLeast(0)
                durationMs = runCatching { player.duration }.getOrDefault(0L).coerceAtLeast(0)

                if (buffering && bufferingSince > 0 &&
                    System.currentTimeMillis() - bufferingSince > 13_000
                ) {
                    bufferingSince = 0
                    failover()
                }
                if (current != null && positionMs > 15_000 &&
                    System.currentTimeMillis() - lastSavedAt > 10_000
                ) {
                    save()
                }

                if (current != null && durationMs > 60_000 && !skipLoaded) {
                    skipLoaded = true
                    val knownDuration = durationMs
                    val token = skipToken
                    scope.launch {
                        val found = runCatching {
                            graph.skipTimes.segments(mediaItem, season, episode, knownDuration)
                        }.getOrDefault(emptyList())
                        if (!released && token == skipToken) skipSegments = found
                    }
                }
                updateSkipState()
            }
        }
    }

    private fun updateSkipState() {
        if (skipSegments.isEmpty()) {
            if (activeSkip != null) activeSkip = null
            return
        }
        val segment = skipSegments.firstOrNull { it.contains(positionMs) }
        if (segment == null) {
            if (activeSkip != null) activeSkip = null
            return
        }
        val config = graph.settings.value
        val auto = when (segment.kind) {
            "intro" -> config.autoSkipIntro
            "recap" -> config.autoSkipRecap
            "credits" -> config.autoSkipCredits
            else -> false
        }
        if (auto) {
            val key = "${segment.kind}|${segment.startMs}"
            if (consumedSkips.add(key)) {
                seekTo(segment.endMs + 250)
                activeSkip = null
                return
            }
        }
        activeSkip = segment
    }

    private fun applySubtitlePreference() {
        runCatching {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled)
                .setPreferredTextLanguage("es")
                .build()
        }
    }

    private fun StreamChoice.toExoMediaItem(): ExoMediaItem {
        val builder = ExoMediaItem.Builder().setUri(Uri.parse(url))
        val tracks = subtitles.filter { track ->
            track.url.startsWith("http") && track.url.substringBefore('?').endsWith(".vtt", ignoreCase = true)
        }
        if (tracks.isNotEmpty()) {
            builder.setSubtitleConfigurations(
                tracks.map { track ->
                    ExoMediaItem.SubtitleConfiguration.Builder(Uri.parse(track.url))
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setLanguage(track.lang.ifBlank { "es" })
                        .setLabel(track.label.ifBlank { "Español" })
                        .build()
                }
            )
        }
        return builder.build()
    }
}

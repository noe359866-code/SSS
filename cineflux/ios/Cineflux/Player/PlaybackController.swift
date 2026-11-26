import AVFoundation
import Foundation

/// Motor de reproducción iOS: pide streams a los plugins, ordena con el núcleo
/// C++ y reproduce el primero. Si falla, prueba el siguiente sin mostrar errores.
@MainActor
final class PlaybackController: ObservableObject {
    enum Phase { case searching, playing, empty }

    @Published var phase: Phase = .searching
    @Published var message = ""
    @Published var profile: Profile
    @Published var current: StreamChoice?
    @Published var isPlaying = false
    @Published var buffering = true
    @Published var position: Double = 0
    @Published var duration: Double = 0
    @Published var subtitlesEnabled = true
    @Published var activeSkip: SkipSegment?

    let player = AVPlayer()

    private let model: AppModel
    private var media: MediaItem
    private let season: Int
    private let episode: Int

    private var candidates: [StreamChoice] = []
    private var index = 0
    private var timeObserver: Any?
    private var itemObservation: NSKeyValueObservation?
    private var endObserver: NSObjectProtocol?
    private var bufferingSince = Date()
    private var lastSaved = Date.distantPast
    private var released = false
    private var skipSegments: [SkipSegment] = []
    private var skipLoaded = false
    private var skipToken = 0
    private var consumedSkips = Set<String>()

    init(model: AppModel, item: MediaItem, season: Int, episode: Int) {
        self.model = model
        self.media = item
        self.season = season
        self.episode = episode
        self.profile = model.profiles.selected
        player.actionAtItemEnd = .pause
        player.allowsExternalPlayback = true
        installObservers()
    }

    func start() async {
        await resolve()
    }

    func resolve() async {
        phase = .searching
        message = ""
        current = nil
        candidates = []
        index = 0
        buffering = true
        bufferingSince = Date()
        skipSegments = []
        activeSkip = nil
        skipLoaded = false
        skipToken += 1
        consumedSkips.removeAll()

        let enriched = await model.catalog.identifiers(media)
        guard !released else { return }
        media = enriched

        let result = await model.plugins.resolve(
            type: enriched.type,
            imdbId: enriched.imdbId,
            tmdbId: enriched.tmdbId,
            season: season,
            episode: episode,
            profile: profile
        )
        guard !released else { return }
        candidates = result.candidates
        if candidates.isEmpty {
            showEmpty(result.message)
        } else {
            play(at: 0)
        }
    }

    func retry() async {
        await resolve()
    }

    func changeProfile(_ next: Profile) {
        profile = next
        model.profiles.select(next.id)
        Task { await resolve() }
    }

    func togglePlay() {
        if player.timeControlStatus == .playing {
            player.pause()
        } else {
            player.play()
        }
    }

    func seek(to seconds: Double) {
        let clamped = max(0, min(seconds, duration > 0 ? duration : seconds))
        player.seek(to: CMTime(seconds: clamped, preferredTimescale: 600))
        position = clamped
    }

    func skip(by seconds: Double) {
        seek(to: position + seconds)
    }

    /// El usuario pulsó «Saltar intro/recap/créditos».
    func skipActive() {
        guard let segment = activeSkip else { return }
        consumedSkips.insert("\(segment.kind)|\(segment.startMs)")
        seek(to: segment.endMs / 1000 + 0.25)
        activeSkip = nil
    }

    func toggleSubtitles() {
        subtitlesEnabled.toggle()
        guard let item = player.currentItem,
              let group = item.asset.mediaSelectionGroup(forMediaCharacteristic: .legible) else {
            return
        }
        if subtitlesEnabled {
            let preferred = group.options.first { $0.extendedLanguageTag?.hasPrefix("es") == true }
                ?? group.options.first
            item.select(preferred, in: group)
        } else {
            item.select(nil, in: group)
        }
    }

    func saveProgress() {
        guard position > 5, duration > 0 else { return }
        model.library.recordProgress(
            media,
            positionMs: position * 1000,
            durationMs: duration * 1000,
            season: season,
            episode: episode
        )
        lastSaved = Date()
    }

    func stop() {
        guard !released else { return }
        released = true
        saveProgress()
        if let timeObserver {
            player.removeTimeObserver(timeObserver)
            self.timeObserver = nil
        }
        itemObservation?.invalidate()
        if let endObserver {
            NotificationCenter.default.removeObserver(endObserver)
        }
        player.pause()
        player.replaceCurrentItem(with: nil)
    }

    // MARK: Interno

    private func installObservers() {
        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.5, preferredTimescale: 600),
            queue: .main
        ) { [weak self] time in
            Task { @MainActor in
                self?.tick(time)
            }
        }
    }

    private func tick(_ time: CMTime) {
        guard !released else { return }
        if time.seconds.isFinite {
            position = max(0, time.seconds)
        }
        if let itemDuration = player.currentItem?.duration.seconds, itemDuration.isFinite, itemDuration > 0 {
            duration = itemDuration
        }
        isPlaying = player.timeControlStatus == .playing
        let waiting = player.timeControlStatus == .waitingToPlayAtSpecifiedRate
        if waiting && !buffering {
            bufferingSince = Date()
        }
        buffering = waiting && current != nil

        if buffering, Date().timeIntervalSince(bufferingSince) > 13 {
            bufferingSince = Date()
            failover()
        }
        if current != nil, position > 15,
           Date().timeIntervalSince(lastSaved) > 10 {
            saveProgress()
        }

        if current != nil, duration > 60, !skipLoaded {
            skipLoaded = true
            let item = media
            let seasonNumber = season
            let episodeNumber = episode
            let durationMs = duration * 1000
            let token = skipToken
            Task { @MainActor in
                let found = await self.model.skipTimes.segments(
                    item: item,
                    season: seasonNumber,
                    episode: episodeNumber,
                    durationMs: durationMs
                )
                if !self.released, token == self.skipToken {
                    self.skipSegments = found
                }
            }
        }
        updateSkipState()
    }

    private func updateSkipState() {
        guard !skipSegments.isEmpty else {
            activeSkip = nil
            return
        }
        let positionMs = position * 1000
        guard let segment = skipSegments.first(where: { $0.contains(positionMs) }) else {
            activeSkip = nil
            return
        }
        let config = model.settings.value
        let auto: Bool
        switch segment.kind {
        case "intro": auto = config.autoSkipIntro
        case "recap": auto = config.autoSkipRecap
        case "credits": auto = config.autoSkipCredits
        default: auto = false
        }
        if auto {
            let key = "\(segment.kind)|\(segment.startMs)"
            if consumedSkips.insert(key).inserted {
                seek(to: segment.endMs / 1000 + 0.25)
                activeSkip = nil
                return
            }
        }
        activeSkip = segment
    }

    private func play(at position: Int) {
        guard let choice = candidates[safe: position] else {
            saveProgress()
            showEmpty(Messages.playbackExhausted)
            return
        }
        index = position + 1
        guard let url = URL(string: choice.url) else {
            failover()
            return
        }
        current = choice
        phase = .playing
        buffering = true
        bufferingSince = Date()
        subtitlesEnabled = true

        itemObservation?.invalidate()
        let playerItem = AVPlayerItem(url: url)
        playerItem.preferredForwardBufferDuration = 6
        itemObservation = playerItem.observe(\.status, options: [.new]) { [weak self] item, _ in
            guard item.status == .failed else { return }
            Task { @MainActor in self?.failover() }
        }
        player.replaceCurrentItem(with: playerItem)
        player.play()
        player.appliesMediaSelectionCriteriaAutomatically = true
    }

    private func failover() {
        guard !released else { return }
        if index >= candidates.count {
            saveProgress()
            showEmpty(Messages.playbackExhausted)
        } else {
            play(at: index)
        }
    }

    private func showEmpty(_ text: String) {
        phase = .empty
        let clean = Messages.scrub(text)
        message = clean.isEmpty ? Messages.noOptions : clean
        buffering = false
        isPlaying = false
        player.pause()
    }
}

private extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}

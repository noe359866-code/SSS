import AVFoundation
import SwiftUI

struct PlayerScreen: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var profiles: ProfileStore

    let model: AppModel
    private let item: MediaItem
    private let season: Int
    private let episode: Int

    @StateObject private var controller: PlaybackController
    @State private var showControls = true
    @State private var locked = false
    @State private var showProfiles = false

    init(model: AppModel, item: MediaItem, season: Int, episode: Int) {
        self.model = model
        self.item = item
        self.season = season
        self.episode = episode
        _controller = StateObject(
            wrappedValue: PlaybackController(model: model, item: item, season: season, episode: episode)
        )
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VideoSurface(player: controller.player)
                .ignoresSafeArea()
                .onTapGesture { withAnimation(.easeInOut(duration: 0.2)) { showControls.toggle() } }

            switch controller.phase {
            case .searching:
                searchingOverlay
            case .empty:
                emptyOverlay
            case .playing:
                if showControls {
                    controls
                        .transition(.opacity)
                }
                if let segment = controller.activeSkip {
                    VStack {
                        Spacer()
                        HStack {
                            Spacer()
                            Button {
                                controller.skipActive()
                            } label: {
                                HStack(spacing: 6) {
                                    Text(segment.label)
                                        .font(.system(size: 13, weight: .semibold))
                                    Image(systemName: "chevron.right")
                                        .font(.system(size: 11, weight: .bold))
                                }
                                .foregroundStyle(Color(hex: "#0B0B0D"))
                                .padding(.horizontal, 16)
                                .padding(.vertical, 10)
                                .background(Color(hex: "#F2F2F7"))
                                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.trailing, 18)
                    .padding(.bottom, 96)
                    .transition(.opacity)
                }
            }
        }
        .statusBarHidden(true)
        .preferredColorScheme(.dark)
        .task { await controller.start() }
        .onDisappear { controller.stop() }
        .task(id: controller.isPlaying) {
            guard controller.isPlaying, showControls else { return }
            try? await Task.sleep(nanoseconds: 4_200_000_000)
            withAnimation(.easeInOut(duration: 0.2)) { showControls = false }
        }
        .sheet(isPresented: $showProfiles) {
            ProfilePickerSheet(
                profiles: profiles.profiles,
                selectedId: controller.profile.id,
                onSelect: { controller.changeProfile($0) }
            )
        }
    }

    // MARK: Superposiciones

    private var searchingOverlay: some View {
        VStack(spacing: 14) {
            ProgressView().tint(Theme.rose).scaleEffect(1.2)
            Text("Preparando tu perfil")
                .font(.system(size: 18, weight: .bold))
                .foregroundStyle(Theme.text)
            Text("Buscando la mejor opción para \(controller.profile.name)…")
                .font(.system(size: 13))
                .foregroundStyle(Theme.muted)
                .multilineTextAlignment(.center)
            Text("No tienes que elegir fuente: el reproductor lo hace por ti.")
                .font(.system(size: 11))
                .foregroundStyle(Theme.muted.opacity(0.7))
                .multilineTextAlignment(.center)
        }
        .padding(28)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black.opacity(0.92))
    }

    private var emptyOverlay: some View {
        VStack(spacing: 12) {
            ZStack {
                Circle().fill(Theme.panelHigh).frame(width: 58, height: 58)
                Text("◎").font(.system(size: 24)).foregroundStyle(Theme.rose)
            }
            Text("Sin opción para este perfil")
                .font(.system(size: 18, weight: .bold))
                .foregroundStyle(Theme.text)
            Text(controller.message)
                .font(.system(size: 13))
                .foregroundStyle(Theme.muted)
                .multilineTextAlignment(.center)
            HStack(spacing: 10) {
                Button("Reintentar") {
                    Task { await controller.retry() }
                }
                .buttonStyle(PrimaryButtonStyle())

                Button("Cambiar perfil") { showProfiles = true }
                    .buttonStyle(GhostButtonStyle())
            }
            .padding(.top, 6)
            Button("Volver") { dismiss() }
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(Theme.muted)
                .padding(.top, 4)
        }
        .padding(28)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.ink.opacity(0.97))
    }

    // MARK: Controles

    private var controls: some View {
        VStack {
            if locked {
                Spacer()
                Button {
                    locked = false
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "lock.fill")
                        Text("Pantalla bloqueada · Toca para desbloquear")
                    }
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Color.black.opacity(0.7))
                    .clipShape(Capsule())
                }
                .padding(.bottom, 28)
            } else {
                topBar
                Spacer()
                if controller.buffering {
                    ProgressView().tint(.white).scaleEffect(1.1)
                } else {
                    // Controles centrales estilo Netflix (retroceder · play/pausa · avanzar)
                    HStack(spacing: 34) {
                        Button { controller.seekBackward() } label: {
                            Image(systemName: "gobackward.10")
                                .font(.system(size: 26))
                                .foregroundStyle(.white)
                                .frame(width: 52, height: 52)
                                .background(Color.black.opacity(0.45))
                                .clipShape(Circle())
                        }
                        Button { controller.togglePlay() } label: {
                            Image(systemName: controller.isPlaying ? "pause.fill" : "play.fill")
                                .font(.system(size: 32))
                                .foregroundStyle(.white)
                                .frame(width: 68, height: 68)
                                .background(Color.black.opacity(0.6))
                                .clipShape(Circle())
                        }
                        Button { controller.seekForward() } label: {
                            Image(systemName: "goforward.10")
                                .font(.system(size: 26))
                                .foregroundStyle(.white)
                                .frame(width: 52, height: 52)
                                .background(Color.black.opacity(0.45))
                                .clipShape(Circle())
                        }
                    }
                }
                Spacer()
                bottomBar
            }
        }
    }

    private var topBar: some View {
        HStack(alignment: .center, spacing: 10) {
            Button {
                dismiss()
            } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(.white)
                    .padding(10)
                    .background(Color.black.opacity(0.45))
                    .clipShape(Circle())
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(controller.profile.name)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.white)
                Text(subtitle)
                    .font(.system(size: 10))
                    .foregroundStyle(Theme.muted)
                    .lineLimit(1)
            }
            Spacer()
            Button {
                showProfiles = true
            } label: {
                HStack(spacing: 4) {
                    Text("Perfil")
                        .foregroundStyle(.white)
                    Text(controller.profile.name)
                        .foregroundStyle(Theme.rose)
                }
                .font(.system(size: 10, weight: .semibold))
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(Color.black.opacity(0.45))
                .clipShape(Capsule())
            }
        }
        .padding(.horizontal, 14)
        .padding(.top, 10)
        .padding(.bottom, 30)
        .background(
            LinearGradient(
                colors: [Color.black.opacity(0.9), .clear],
                startPoint: .top,
                endPoint: .bottom
            )
        )
    }

    private var subtitle: String {
        var parts: [String] = []
        if season > 0 { parts.append("T\(season)") }
        if episode > 0 { parts.append("E\(episode)") }
        if let current = controller.current, !current.label.isEmpty {
            parts.append(current.label)
        }
        return parts.joined(separator: " · ")
    }

    private var bottomBar: some View {
        VStack(spacing: 8) {
            HStack {
                Text(formatTime(controller.position))
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(.white)
                Slider(
                    value: Binding(
                        get: { controller.position },
                        set: { controller.seek(to: $0) }
                    ),
                    in: 0...max(controller.duration, 1)
                )
                .tint(Theme.rose)
                Text(formatTime(controller.duration))
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(Theme.muted)
            }

            HStack(spacing: 18) {
                Button {
                    let speeds = [0.75, 1.0, 1.25, 1.5, 2.0]
                    let idx = speeds.firstIndex { abs($0 - controller.playbackSpeed) < 0.05 } ?? 1
                    controller.setSpeed(speeds[(idx + 1) % speeds.count])
                } label: {
                    let label = controller.playbackSpeed == 1.0 ? "Velocidad (1×)" : String(format: "Velocidad (%.2g×)", controller.playbackSpeed)
                    Text(label)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.white)
                }
                Spacer()
                Button {
                    locked = true
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "lock.open")
                        Text("Bloquear")
                    }
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.white)
                }
                Spacer()
                Button { controller.toggleSubtitles() } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "captions.bubble")
                        Text("Subtítulos")
                    }
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(controller.subtitlesEnabled ? Theme.rose : Theme.muted)
                }
            }
            .padding(.horizontal, 6)
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 22)
        .padding(.top, 18)
        .background(
            LinearGradient(
                colors: [.clear, Color.black.opacity(0.9)],
                startPoint: .top,
                endPoint: .bottom
            )
        )
    }
}

struct VideoSurface: UIViewRepresentable {
    let player: AVPlayer

    func makeUIView(context: Context) -> PlayerUIView {
        let view = PlayerUIView()
        view.backgroundColor = .black
        view.playerLayer.videoGravity = .resizeAspect
        view.playerLayer.player = player
        return view
    }

    func updateUIView(_ uiView: PlayerUIView, context: Context) {
        uiView.playerLayer.player = player
    }
}

final class PlayerUIView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}

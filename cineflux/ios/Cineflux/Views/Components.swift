import SwiftUI

// MARK: - Pósters

struct SeedGradient: View {
    let seed: String
    var title: String?

    private var colors: [Color] {
        let palettes: [[Color]] = [
            [Color(hex: "#2B1055"), Color(hex: "#7597DE")],
            [Color(hex: "#42275A"), Color(hex: "#734B6D")],
            [Color(hex: "#0F2027"), Color(hex: "#2C5364")],
            [Color(hex: "#3A1C71"), Color(hex: "#D76D77")],
            [Color(hex: "#1F4037"), Color(hex: "#99F2C8")],
            [Color(hex: "#41295A"), Color(hex: "#2F0743")]
        ]
        var hash = 5381
        for scalar in seed.unicodeScalars {
            hash = (hash &* 33) &+ Int(scalar.value)
        }
        return palettes[Int(UInt(bitPattern: hash) % UInt(palettes.count))]
    }

    var body: some View {
        ZStack {
            LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing)
            if let title, !title.isEmpty {
                Text(title)
                    .font(.system(size: 16, weight: .black))
                    .foregroundStyle(.white.opacity(0.9))
                    .multilineTextAlignment(.center)
                    .lineLimit(4)
                    .padding(10)
            }
        }
    }
}

struct Artwork: View {
    let url: String
    let seed: String
    var title: String?
    var contentMode: ContentMode = .fill

    var body: some View {
        if let link = URL(string: url), !url.isEmpty {
            AsyncImage(url: link) { phase in
                switch phase {
                case .success(let image):
                    image.resizable().aspectRatio(contentMode: contentMode)
                default:
                    SeedGradient(seed: seed, title: nil)
                }
            }
        } else {
            SeedGradient(seed: seed, title: title)
        }
    }
}

struct ScoreBadges: View {
    let item: MediaItem
    var compact = false

    var body: some View {
        HStack(spacing: 6) {
            if let tomato = item.tomato {
                Badge(text: "🍅 \(tomato)%", foreground: Color(hex: "#FFB3A8"), background: Color(hex: "#2A0B08"))
            }
            if let audience = item.audience {
                Badge(text: "🍿 \(audience)%", foreground: Color(hex: "#FFD8A8"), background: Color(hex: "#2A1B08"))
            }
            if !item.scoreLabel.isEmpty {
                Badge(text: "★ \(item.scoreLabel)", foreground: Theme.golden, background: Color.black.opacity(0.45))
            }
            if !compact, let genre = item.genres.first {
                Badge(text: genre, foreground: Theme.muted, background: Color.black.opacity(0.35))
            }
        }
    }
}

struct Badge: View {
    let text: String
    var foreground: Color = Theme.text
    var background: Color = Color.black.opacity(0.45)

    var body: some View {
        Text(text)
            .font(.system(size: 10, weight: .semibold))
            .foregroundStyle(foreground)
            .padding(.horizontal, 6)
            .padding(.vertical, 3)
            .background(background)
            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
    }
}

struct PosterCard: View {
    let item: MediaItem
    var width: CGFloat = 124
    var showsTitle = true
    var showsRatings = true

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            ZStack(alignment: .bottomLeading) {
                Artwork(
                    url: item.poster,
                    seed: item.key,
                    title: item.poster.isEmpty ? item.title : nil
                )
                .frame(width: width, height: width * 1.5)
                .clipped()
                .overlay(
                    LinearGradient(
                        colors: [.clear, Color(hex: "#05060A").opacity(0.85)],
                        startPoint: .center,
                        endPoint: .bottom
                    )
                )
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .stroke(Theme.stroke.opacity(0.35), lineWidth: 1)
                )

                if showsRatings {
                    HStack(spacing: 4) {
                        if let tomato = item.tomato {
                            Badge(text: "🍅 \(tomato)%", foreground: Color(hex: "#FFB3A8"), background: Color(hex: "#2A0B08").opacity(0.9))
                        } else if !item.scoreLabel.isEmpty {
                            Badge(
                                text: "★ \(item.scoreLabel)",
                                foreground: Theme.golden,
                                background: Color(hex: "#1A1408").opacity(0.9)
                            )
                        }
                    }
                    .padding(7)
                }
            }

            if showsTitle {
                Text(item.title)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Theme.text)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                    .frame(width: width, alignment: .leading)
                Text([item.year > 0 ? "\(item.year)" : nil, item.isSeries ? "Serie" : "Película"]
                    .compactMap { $0 }.joined(separator: " · "))
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(Theme.muted)
            }
        }
        .frame(width: width, alignment: .leading)
    }
}

// MARK: - Filas

struct MediaRowView: View {
    let row: HomeRow

    var body: some View {
        if !row.items.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .firstTextBaseline) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(row.title)
                            .font(.system(size: 19, weight: .bold))
                            .foregroundStyle(Theme.text)
                        if !row.subtitle.isEmpty {
                            Text(row.subtitle)
                                .font(.system(size: 11, weight: .medium))
                                .foregroundStyle(Theme.muted)
                        }
                    }
                    Spacer()
                    if !row.badge.isEmpty {
                        Badge(
                            text: row.badge,
                            foreground: row.badge == "RT" ? Color(hex: "#FF8A75") : Theme.muted,
                            background: Theme.panelHigh
                        )
                    }
                    Image(systemName: "chevron.right")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(Theme.muted)
                }
                .padding(.horizontal, 18)

                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(spacing: 12) {
                        ForEach(row.items) { item in
                            NavigationLink(value: item) {
                                PosterCard(item: item)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 18)
                }
            }
            .padding(.bottom, 20)
        }
    }
}

// MARK: - Héroe

struct HeroView: View {
    let item: MediaItem
    let profile: Profile
    let onPlay: () -> Void
    let onProfile: () -> Void

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Artwork(url: item.backdrop, seed: item.key, title: nil)
                .frame(height: 470)
                .frame(maxWidth: .infinity)
                .clipped()
                .overlay(
                    LinearGradient(
                        stops: [
                            .init(color: .black.opacity(0.2), location: 0),
                            .init(color: .black.opacity(0.65), location: 0.5),
                            .init(color: Theme.ink, location: 1)
                        ],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )

            VStack(alignment: .leading, spacing: 9) {
                HStack(spacing: 8) {
                    Badge(
                        text: item.isSeries ? "SERIE" : "PELÍCULA",
                        foreground: .white,
                        background: Theme.rose.opacity(0.85)
                    )
                    if item.year > 0 { Badge(text: "\(item.year)") }
                    if item.tomato != nil || item.audience != nil {
                        Badge(text: "Rotten Tomatoes", foreground: Color(hex: "#FFB3A8"), background: Color(hex: "#2A0B08"))
                    }
                }
                Text(item.title)
                    .font(.system(size: 38, weight: .black))
                    .foregroundStyle(Theme.text)
                    .lineLimit(2)
                    .minimumScaleFactor(0.7)
                ScoreBadges(item)
                if !item.overview.isEmpty {
                    Text(item.overview)
                        .font(.system(size: 13, weight: .regular))
                        .foregroundStyle(Theme.text.opacity(0.82))
                        .lineLimit(3)
                }
                HStack(spacing: 10) {
                    Button(action: onPlay) {
                        Label("Reproducir", systemImage: "play.fill")
                            .font(.system(size: 14, weight: .bold))
                            .padding(.horizontal, 18)
                            .padding(.vertical, 12)
                            .background(.white)
                            .foregroundStyle(Color(hex: "#0B0D12"))
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                    NavigationLink(value: item) {
                        Text("Ver ficha")
                            .font(.system(size: 14, weight: .semibold))
                            .padding(.horizontal, 14)
                            .padding(.vertical, 11)
                            .background(Theme.panelHigh)
                            .foregroundStyle(Theme.text)
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }
                ProfilePill(profile: profile, action: onProfile)
                    .padding(.top, 2)
            }
            .padding(.horizontal, 18)
            .padding(.bottom, 16)
        }
        .frame(height: 470)
    }
}

struct ProfilePill: View {
    let profile: Profile
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Circle().fill(colorFromHex(profile.accent)).frame(width: 8, height: 8)
                Text(profile.name)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(Theme.text)
                Image(systemName: "chevron.right")
                    .font(.system(size: 9, weight: .bold))
                    .foregroundStyle(Theme.muted)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(Theme.panelHigh)
            .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Continuar viendo

struct ContinueCard: View {
    let entry: LibraryEntry
    var width: CGFloat = 236

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ZStack(alignment: .bottom) {
                Artwork(url: entry.item.backdrop, seed: entry.item.key, title: entry.item.title)
                    .frame(width: width, height: width * 0.5625)
                    .clipped()
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .overlay(Color.black.opacity(0.25))
                ProgressView(value: entry.progress)
                    .tint(Theme.rose)
                    .scaleEffect(x: 1, y: 1.6, anchor: .center)
            }
            Text(entry.item.title)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(Theme.text)
                .lineLimit(1)
            Text("\(Int(entry.progress * 100))% visto")
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(Theme.muted)
        }
        .frame(width: width, alignment: .leading)
    }
}

// MARK: - Varios

struct EmptyStateView: View {
    let title: String
    let message: String
    var actionLabel: String? = nil
    var action: (() -> Void)? = nil

    var body: some View {
        VStack(spacing: 12) {
            ZStack {
                Circle().fill(Theme.panelHigh).frame(width: 56, height: 56)
                Text("✦").font(.system(size: 22)).foregroundStyle(Theme.rose)
            }
            Text(title)
                .font(.system(size: 18, weight: .bold))
                .foregroundStyle(Theme.text)
                .multilineTextAlignment(.center)
            Text(message)
                .font(.system(size: 13))
                .foregroundStyle(Theme.muted)
                .multilineTextAlignment(.center)
            if let actionLabel, let action {
                Button(actionLabel, action: action)
                    .buttonStyle(PrimaryButtonStyle())
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 28)
        .padding(.vertical, 30)
    }
}

struct ScoreTile: View {
    let emoji: String
    let value: String
    let label: String
    let tint: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("\(emoji) \(value)")
                .font(.system(size: 17, weight: .bold))
                .foregroundStyle(tint)
            Text(label)
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(Theme.muted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(Theme.panel)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

struct ChipButton: View {
    let text: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(text)
                .font(.system(size: 12, weight: .semibold))
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .foregroundStyle(selected ? .white : Theme.muted)
                .background(selected ? Theme.rose : Theme.panelHigh)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Selector de perfiles

struct ProfilePickerSheet: View {
    private let profiles: [Profile]
    private let selectedId: String
    private let onSelect: (Profile) -> Void
    @Environment(\.dismiss) private var dismiss

    init(
        profiles: [Profile],
        selectedId: String,
        onSelect: @escaping (Profile) -> Void
    ) {
        self.profiles = profiles
        self.selectedId = selectedId
        self.onSelect = onSelect
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text("El reproductor elige el stream automáticamente según el perfil. No hay que escoger webs ni servidores.")
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.muted)
                    ForEach(profiles) { profile in
                        Button {
                            onSelect(profile)
                            dismiss()
                        } label: {
                            HStack(spacing: 12) {
                                Circle()
                                    .fill(colorFromHex(profile.accent))
                                    .frame(width: 10, height: 10)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(profile.name)
                                        .font(.system(size: 15, weight: .semibold))
                                        .foregroundStyle(Theme.text)
                                    Text(profile.summary)
                                        .font(.system(size: 11))
                                        .foregroundStyle(Theme.muted)
                                }
                                Spacer()
                                if profile.id == selectedId {
                                    Image(systemName: "checkmark")
                                        .foregroundStyle(Theme.rose)
                                }
                            }
                            .padding(14)
                            .background(profile.id == selectedId ? Theme.panelHigh : Theme.panel)
                            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                        }
                        .buttonStyle(.plain)
                    }
                    NavigationLink {
                        ProfilesView()
                    } label: {
                        Text("Gestionar perfiles")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(Theme.rose)
                    }
                    .padding(.top, 4)
                }
                .padding(18)
            }
            .background(Theme.ink)
            .navigationTitle("Perfil de reproducción")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cerrar") { dismiss() }
                        .foregroundStyle(Theme.rose)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

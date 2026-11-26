import SwiftUI

struct DetailView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var profiles: ProfileStore

    private let item: MediaItem
    private let play: (MediaItem, Int, Int) -> Void

    @State private var detail: DetailData?
    @State private var loading = true
    @State private var selectedSeason = 0
    @State private var episodes: [EpisodeInfo] = []
    @State private var loadingEpisodes = false
    @State private var showProfiles = false

    init(item: MediaItem, play: @escaping (MediaItem, Int, Int) -> Void) {
        self.item = item
        self.play = play
    }

    var body: some View {
        ScrollView {
            if let detail {
                header(detail.item)
                actions(detail.item)
                profileLine
                scoreTiles(detail.item)
                if !detail.item.overview.isEmpty {
                    Text(detail.item.overview)
                        .font(.system(size: 14))
                        .foregroundStyle(Theme.text)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 8)
                }
                if !detail.item.genres.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(detail.item.genres, id: \.self) { genre in
                                Badge(text: genre, foreground: Theme.muted, background: Theme.panelHigh)
                            }
                        }
                        .padding(.horizontal, 18)
                    }
                }
                if !detail.item.cast.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Reparto")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(Theme.text)
                        Text(detail.item.cast.joined(separator: " · "))
                            .font(.system(size: 12))
                            .foregroundStyle(Theme.muted)
                    }
                    .padding(.horizontal, 18)
                    .padding(.vertical, 10)
                }
                if detail.item.isSeries, !detail.seasons.isEmpty {
                    seasonsSection(detail)
                }
                if !detail.similar.isEmpty {
                    MediaRowView(row: HomeRow(
                        title: "Títulos parecidos",
                        subtitle: "",
                        items: detail.similar
                    ))
                    .padding(.top, 12)
                }
            } else if loading {
                ProgressView().tint(Theme.rose).padding(80)
            } else {
                EmptyStateView(
                    title: "No pudimos abrir la ficha",
                    message: Messages.notFound
                )
            }
        }
        .background(Theme.ink)
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        .task(id: selectedSeason) { await loadEpisodes() }
        .sheet(isPresented: $showProfiles) {
            ProfilePickerSheet(
                profiles: profiles.profiles,
                selectedId: profiles.selectedId,
                onSelect: { profiles.select($0.id) }
            )
        }
    }

    // MARK: Cabecera

    private func header(_ item: MediaItem) -> some View {
        ZStack(alignment: .bottomLeading) {
            Artwork(url: item.backdrop, seed: item.key, title: nil)
                .frame(height: 340)
                .frame(maxWidth: .infinity)
                .clipped()
                .overlay(
                    LinearGradient(
                        stops: [
                            .init(color: .black.opacity(0.25), location: 0),
                            .init(color: .black.opacity(0.7), location: 0.55),
                            .init(color: Theme.ink, location: 1)
                        ],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )

            HStack(alignment: .bottom, spacing: 14) {
                Artwork(url: item.poster, seed: item.key, title: item.poster.isEmpty ? item.title : nil)
                    .frame(width: 96, height: 144)
                    .clipped()
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                VStack(alignment: .leading, spacing: 6) {
                    Text(item.title)
                        .font(.system(size: 26, weight: .black))
                        .foregroundStyle(Theme.text)
                        .lineLimit(3)
                        .minimumScaleFactor(0.75)
                    Text(metaLine(item))
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(Theme.muted)
                    ScoreBadges(item)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 18)
            .padding(.bottom, 12)
        }
        .frame(height: 340)
    }

    private func metaLine(_ item: MediaItem) -> String {
        var parts: [String] = []
        if item.year > 0 { parts.append("\(item.year)") }
        if item.isSeries {
            parts.append("\(item.seasons) temporada\(item.seasons == 1 ? "" : "s")")
        } else if item.runtime > 0 {
            parts.append(formatRuntime(item.runtime))
        }
        if !item.originalTitle.isEmpty && item.originalTitle != item.title {
            parts.append(item.originalTitle)
        }
        return parts.joined(separator: " · ")
    }

    // MARK: Acciones

    private func actions(_ item: MediaItem) -> some View {
        HStack(spacing: 10) {
            Button {
                let season = item.isSeries ? selectedSeason : 0
                let episode = item.isSeries ? (episodes.first?.number ?? 1) : 0
                play(item, season, episode)
            } label: {
                Label(item.isSeries ? "Ver T\(selectedSeason)" : "Reproducir", systemImage: "play.fill")
                    .font(.system(size: 14, weight: .bold))
                    .padding(.horizontal, 18)
                    .padding(.vertical, 12)
                    .background(.white)
                    .foregroundStyle(Color(hex: "#0B0D12"))
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            .buttonStyle(.plain)

            Button {
                library.toggle(item)
            } label: {
                Label(
                    library.isSaved(item) ? "En tu lista" : "Mi lista",
                    systemImage: library.isSaved(item) ? "checkmark" : "plus"
                )
                .font(.system(size: 13, weight: .semibold))
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .background(library.isSaved(item) ? Theme.rose.opacity(0.2) : Theme.panelHigh)
                .foregroundStyle(library.isSaved(item) ? Theme.rose : Theme.text)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            .buttonStyle(.plain)

            Spacer()
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 8)
    }

    private var profileLine: some View {
        HStack(spacing: 8) {
            Text("Reproducción automática")
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(Theme.muted)
            ProfilePill(profile: profiles.selected) { showProfiles = true }
            Spacer()
        }
        .padding(.horizontal, 18)
        .padding(.bottom, 4)
    }

    private func scoreTiles(_ item: MediaItem) -> some View {
        HStack(spacing: 10) {
            if let tomato = item.tomato {
                ScoreTile(emoji: "🍅", value: "\(tomato)%", label: "Tomatometer", tint: Color(hex: "#FF8A75"))
            }
            if let audience = item.audience {
                ScoreTile(emoji: "🍿", value: "\(audience)%", label: "Audiencia", tint: Theme.golden)
            }
            if !item.scoreLabel.isEmpty {
                ScoreTile(emoji: "★", value: item.scoreLabel, label: "IMDb", tint: Theme.golden)
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 6)
    }

    // MARK: Temporadas

    @ViewBuilder
    private func seasonsSection(_ detail: DetailData) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Temporadas")
                .font(.system(size: 19, weight: .bold))
                .foregroundStyle(Theme.text)
                .padding(.horizontal, 18)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(detail.seasons) { season in
                        ChipButton(
                            text: season.name,
                            selected: season.number == selectedSeason,
                            action: { selectedSeason = season.number }
                        )
                    }
                }
                .padding(.horizontal, 18)
            }

            if loadingEpisodes {
                ProgressView().tint(Theme.rose).frame(maxWidth: .infinity).padding(20)
            } else if episodes.isEmpty {
                Text("Los episodios de esta temporada aparecerán aquí.")
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.muted)
                    .padding(.horizontal, 18)
            } else {
                ForEach(episodes) { episode in
                    Button {
                        play(detail.item, selectedSeason, episode.number)
                    } label: {
                        episodeRow(episode)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(.top, 12)
    }

    private func episodeRow(_ episode: EpisodeInfo) -> some View {
        HStack(spacing: 12) {
            ZStack {
                Artwork(url: episode.still, seed: "ep-\(episode.number)", title: nil)
                    .frame(width: 96, height: 54)
                    .clipped()
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                Circle()
                    .fill(Color.black.opacity(0.6))
                    .frame(width: 26, height: 26)
                Image(systemName: "play.fill")
                    .font(.system(size: 11))
                    .foregroundStyle(.white)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text("\(episode.number). \(episode.title.isEmpty ? "Episodio \(episode.number)" : episode.title)")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Theme.text)
                    .lineLimit(1)
                if !episode.overview.isEmpty {
                    Text(episode.overview)
                        .font(.system(size: 10))
                        .foregroundStyle(Theme.muted)
                        .lineLimit(2)
                }
            }
            Spacer()
            if episode.runtime > 0 {
                Text("\(episode.runtime) min")
                    .font(.system(size: 10))
                    .foregroundStyle(Theme.muted)
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 6)
        .contentShape(Rectangle())
    }

    // MARK: Carga

    private func load() async {
        loading = true
        detail = await model.catalog.detail(key: item.key, type: item.type)
        if let first = detail?.seasons.first?.number {
            selectedSeason = first
        }
        loading = false
    }

    private func loadEpisodes() async {
        guard let detail, detail.item.isSeries, selectedSeason > 0 else { return }
        loadingEpisodes = true
        episodes = await model.catalog.seasonEpisodes(detail.item, season: selectedSeason)
        loadingEpisodes = false
    }
}

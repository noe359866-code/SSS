import SwiftUI

struct HomeView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var settings: SettingsStore
    @EnvironmentObject private var plugins: PluginStore
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var profiles: ProfileStore

    private let play: (MediaItem, Int, Int) -> Void
    private let openProfiles: () -> Void

    @State private var snapshot: CatalogSnapshot?
    @State private var loading = true

    init(play: @escaping (MediaItem, Int, Int) -> Void, openProfiles: @escaping () -> Void) {
        self.play = play
        self.openProfiles = openProfiles
    }

    private var rows: [HomeRow] { snapshot?.rows ?? [] }

    var body: some View {
        Group {
            if !settings.value.hasCatalogSources {
                SetupHomeView()
            } else {
                catalog
            }
        }
        .background(Theme.ink)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Text("CINEFLUX")
                    .font(.system(size: 15, weight: .black))
                    .tracking(3)
                    .foregroundStyle(Theme.text)
            }
        }
        .task(id: settings.value.catalogSources) { await load(force: false) }
    }

    private var catalog: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if loading && snapshot == nil {
                    ProgressView()
                        .tint(Theme.rose)
                        .frame(maxWidth: .infinity)
                        .padding(90)
                } else {
                    if let hero = rows.first?.items.first {
                        HeroView(
                            item: hero,
                            profile: profiles.selected,
                            onPlay: {
                                let season = hero.isSeries ? 1 : 0
                                play(hero, season, season)
                            },
                            onProfile: openProfiles
                        )
                    }

                    if !library.continueWatching.isEmpty {
                        VStack(alignment: .leading, spacing: 10) {
                            HStack(alignment: .firstTextBaseline) {
                                Text("Continuar viendo")
                                    .font(.system(size: 22, weight: .bold))
                                    .foregroundStyle(Theme.text)
                                Spacer()
                                Text("En este dispositivo")
                                    .font(.system(size: 10, weight: .medium))
                                    .foregroundStyle(Theme.muted)
                            }
                            .padding(.horizontal, 18)

                            ScrollView(.horizontal, showsIndicators: false) {
                                LazyHStack(spacing: 14) {
                                    ForEach(library.continueWatching) { entry in
                                        Button {
                                            play(entry.item, entry.season, entry.episode)
                                        } label: {
                                            ContinueCard(entry: entry)
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                                .padding(.horizontal, 18)
                            }
                        }
                        .padding(.top, 10)
                        .padding(.bottom, 26)
                    }

                    ForEach(Array(rows.dropFirst())) { row in
                        MediaRowView(row: row)
                    }

                    if rows.isEmpty {
                        EmptyStateView(
                            title: "No hemos podido cargar el catálogo",
                            message: (snapshot?.message).flatMap { $0.isEmpty ? nil : $0 } ?? Messages.network,
                            actionLabel: "Reintentar",
                            action: { Task { await load(force: true) } }
                        )
                    }

                    if plugins.plugins.isEmpty {
                        pluginCard
                    }

                    VStack(alignment: .leading, spacing: 4) {
                        Text("Nuvio + Cinemeta · TheIntroDB · IntroDB · AnimeSkip")
                            .font(.system(size: 10, weight: .medium))
                            .foregroundStyle(Theme.muted)
                        Text("Cineflux no incluye scrapers. Cada plugin es un manifest que tú instalas.")
                            .font(.system(size: 10, weight: .medium))
                            .foregroundStyle(Theme.muted.opacity(0.7))
                    }
                    .padding(.horizontal, 22)
                    .padding(.vertical, 18)
                }
            }
        }
        .refreshable { await load(force: true) }
        .scrollIndicators(.hidden)
    }

    private var pluginCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Sin plugins de reproducción")
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(Theme.text)
            Text("Pega el manifest de un plugin (o su URL) y el reproductor empezará a resolver streams por ti.")
                .font(.system(size: 12))
                .foregroundStyle(Theme.muted)
            NavigationLink {
                PluginsView()
            } label: {
                Text("Instalar el primero")
                    .font(.system(size: 14, weight: .bold))
                    .padding(.horizontal, 16)
                    .padding(.vertical, 11)
                    .background(Theme.rose)
                    .foregroundStyle(.white)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            }
            .buttonStyle(.plain)
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.panel)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .padding(.horizontal, 18)
        .padding(.top, 8)
    }

    private func load(force: Bool) async {
        guard settings.value.hasCatalogSources else {
            loading = false
            snapshot = nil
            return
        }
        loading = true
        snapshot = await model.catalog.home(force: force)
        loading = false
    }
}

// MARK: - Estado inicial (sin fuentes de catálogo)

struct SetupHomeView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text("Tu cine.\nSin elegir servidores.")
                    .font(.system(size: 40, weight: .black))
                    .foregroundStyle(Theme.text)
                    .padding(.top, 34)

                Text("Conecta tus catálogos (Nuvio, Cinemeta o cualquier manifest) y añade un plugin de reproducción. El reproductor elegirá la mejor opción por ti y saltará intros y créditos.")
                    .font(.system(size: 15))
                    .foregroundStyle(Theme.muted)
                    .padding(.top, 14)

                NavigationLink {
                    SettingsView()
                } label: {
                    Text("Añadir catálogo")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(Color(hex: "#0B0B0D"))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 15)
                        .background(Color.white)
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
                .buttonStyle(.plain)
                .padding(.top, 26)

                NavigationLink {
                    PluginsView()
                } label: {
                    Text("Instalar un plugin")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Theme.text)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 15)
                        .background(Theme.panelHigh)
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
                .buttonStyle(.plain)
                .padding(.top, 10)

                VStack(alignment: .leading, spacing: 14) {
                    SetupFeature(title: "Catálogo real", subtitle: "Nuvio y Cinemeta aportan tendencias, populares y fichas completas.")
                    SetupFeature(title: "Reproducción automática", subtitle: "Sin listas de webs ni fuentes: un perfil decide todo.")
                    SetupFeature(title: "Saltos inteligentes", subtitle: "TheIntroDB, IntroDB y AnimeSkip detectan intro, recap y créditos.")
                }
                .padding(.top, 30)

                Text("Sin demos. Lo que ves es tu catálogo.")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(Theme.muted)
                    .padding(.top, 30)
            }
            .padding(.horizontal, 24)
            .padding(.top, 24)
            .padding(.bottom, 40)
        }
        .scrollIndicators(.hidden)
        .background(
            LinearGradient(
                colors: [Color(hex: "#1A0004"), Theme.ink],
                startPoint: .top,
                endPoint: .center
            )
            .ignoresSafeArea()
        )
    }
}

private struct SetupFeature: View {
    let title: String
    let subtitle: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Circle().fill(Theme.rose).frame(width: 6, height: 6).padding(.top, 6)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Theme.text)
                Text(subtitle)
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.muted)
            }
        }
    }
}

// MARK: - Explorar

struct BrowseView: View {
    @EnvironmentObject private var model: AppModel
    private let type: String

    @State private var rows: [HomeRow] = []
    @State private var loading = true

    init(type: String) {
        self.type = type
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(type == "series" ? "Series" : "Películas")
                        .font(.system(size: 34, weight: .black))
                        .foregroundStyle(Theme.text)
                    Text("Nuvio + Cinemeta · puntuaciones de IMDb y RT")
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.muted)
                }
                .padding(.horizontal, 18)
                .padding(.top, 10)
                .padding(.bottom, 16)

                if loading {
                    ProgressView().tint(Theme.rose).frame(maxWidth: .infinity).padding(60)
                } else if rows.isEmpty {
                    EmptyStateView(title: "No hay títulos ahora mismo", message: Messages.network)
                } else {
                    ForEach(rows) { row in
                        MediaRowView(row: row)
                    }
                }
            }
        }
        .scrollIndicators(.hidden)
        .background(Theme.ink)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: type) {
            loading = true
            rows = await model.catalog.browse(type)
            loading = false
        }
    }
}

// MARK: - Buscar

struct SearchView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var settings: SettingsStore

    @State private var query = ""
    @State private var results: [MediaItem] = []
    @State private var loading = false

    private let columns = [GridItem(.adaptive(minimum: 104), spacing: 12)]

    var body: some View {
        ScrollView {
            if !settings.value.hasCatalogSources {
                Text("Añade una fuente de catálogo en Ajustes para buscar.")
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.muted)
                    .padding(.horizontal, 18)
                    .padding(.top, 8)
            }
            if loading && results.isEmpty {
                ProgressView().tint(Theme.rose).frame(maxWidth: .infinity).padding(60)
            } else if query.isEmpty {
                EmptyStateView(
                    title: "¿Qué quieres ver?",
                    message: "Busca por título, saga o género. Los resultados se abren con tu perfil activo."
                )
            } else if results.isEmpty {
                EmptyStateView(title: "Sin resultados", message: "No encontramos «\(query)». Prueba con otro título.")
            } else {
                LazyVGrid(columns: columns, spacing: 16) {
                    ForEach(results) { item in
                        NavigationLink(value: item) {
                            PosterCard(item: item, width: 104)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 10)
            }
        }
        .scrollIndicators(.hidden)
        .background(Theme.ink)
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Busca películas y series")
        .navigationTitle("Buscar")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: query) {
            guard !query.trimmingCharacters(in: .whitespaces).isEmpty else {
                results = []
                return
            }
            try? await Task.sleep(nanoseconds: 350_000_000)
            loading = true
            results = await model.catalog.search(query)
            loading = false
        }
    }
}

// MARK: - Mi lista

struct LibraryView: View {
    @EnvironmentObject private var library: LibraryStore
    private let play: (MediaItem, Int, Int) -> Void

    private let columns = [GridItem(.adaptive(minimum: 104), spacing: 12)]

    init(play: @escaping (MediaItem, Int, Int) -> Void) {
        self.play = play
    }

    var body: some View {
        ScrollView {
            if library.saved.isEmpty && library.continueWatching.isEmpty {
                VStack(alignment: .leading, spacing: 0) {
                    Text("Mi lista")
                        .font(.system(size: 34, weight: .black))
                        .foregroundStyle(Theme.text)
                        .padding(.horizontal, 18)
                        .padding(.top, 14)
                    EmptyStateView(
                        title: "Tu lista está vacía",
                        message: "Añade títulos desde su ficha para tenerlos a mano. El progreso se guarda solo en este dispositivo."
                    )
                }
            } else {
                LazyVStack(alignment: .leading, spacing: 16) {
                    Text("Mi lista")
                        .font(.system(size: 34, weight: .black))
                        .foregroundStyle(Theme.text)
                        .padding(.horizontal, 18)

                    if !library.continueWatching.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Continuar viendo")
                                .font(.system(size: 22, weight: .bold))
                                .foregroundStyle(Theme.text)
                                .padding(.horizontal, 18)
                            ScrollView(.horizontal, showsIndicators: false) {
                                LazyHStack(spacing: 14) {
                                    ForEach(library.continueWatching) { entry in
                                        Button {
                                            play(entry.item, entry.season, entry.episode)
                                        } label: {
                                            ContinueCard(entry: entry)
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                                .padding(.horizontal, 18)
                            }
                        }
                    }

                    if !library.saved.isEmpty {
                        Text("Guardados")
                            .font(.system(size: 22, weight: .bold))
                            .foregroundStyle(Theme.text)
                            .padding(.horizontal, 18)
                        LazyVGrid(columns: columns, spacing: 16) {
                            ForEach(library.saved) { entry in
                                NavigationLink(value: entry.item) {
                                    PosterCard(item: entry.item, width: 104)
                                }
                                .buttonStyle(.plain)
                                .contextMenu {
                                    Button("Quitar de mi lista", role: .destructive) {
                                        library.toggle(entry.item)
                                    }
                                }
                            }
                        }
                        .padding(.horizontal, 16)
                    }
                }
                .padding(.vertical, 14)
            }
        }
        .scrollIndicators(.hidden)
        .background(Theme.ink)
        .navigationBarTitleDisplayMode(.inline)
    }
}

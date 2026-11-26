import SwiftUI

@main
struct CinefluxApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .environmentObject(model.settings)
                .environmentObject(model.plugins)
                .environmentObject(model.profiles)
                .environmentObject(model.library)
                .tint(Theme.rose)
                .preferredColorScheme(.dark)
        }
    }
}

struct PlayTarget: Identifiable {
    let item: MediaItem
    var season: Int = 0
    var episode: Int = 0
    var id: String { "\(item.key)-\(season)-\(episode)-\(UUID().uuidString)" }
}

struct AppChrome: ViewModifier {
    @EnvironmentObject private var profiles: ProfileStore
    @Binding var showProfiles: Bool
    @Binding var showSettings: Bool

    func body(content: Content) -> some View {
        content
            .toolbar {
                ToolbarItemGroup(placement: .navigationBarTrailing) {
                    Button {
                        showProfiles = true
                    } label: {
                        HStack(spacing: 6) {
                            Circle()
                                .fill(colorFromHex(profiles.selected.accent))
                                .frame(width: 7, height: 7)
                            Text(profiles.selected.name)
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundStyle(Theme.text)
                        }
                        .padding(.horizontal, 9)
                        .padding(.vertical, 5)
                        .background(Theme.panelHigh)
                        .clipShape(Capsule())
                    }

                    NavigationLink {
                        SearchView()
                    } label: {
                        Image(systemName: "magnifyingglass")
                            .foregroundStyle(Theme.text)
                    }

                    Button {
                        showSettings = true
                    } label: {
                        Image(systemName: "gearshape")
                            .foregroundStyle(Theme.muted)
                    }
                }
            }
            .toolbarBackground(Theme.ink, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
    }
}

struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var profiles: ProfileStore
    @State private var playTarget: PlayTarget?
    @State private var showProfiles = false
    @State private var showSettings = false

    private func play(_ item: MediaItem, _ season: Int = 0, _ episode: Int = 0) {
        playTarget = PlayTarget(item: item, season: season, episode: episode)
    }

    var body: some View {
        TabView {
            NavigationStack {
                HomeView(play: play, openProfiles: { showProfiles = true })
                    .modifier(AppChrome(showProfiles: $showProfiles, showSettings: $showSettings))
                    .navigationDestination(for: MediaItem.self) { item in
                        DetailView(item: item, play: play)
                    }
            }
            .tabItem { Label("Inicio", systemImage: "house.fill") }

            NavigationStack {
                BrowseView(type: "movie")
                    .modifier(AppChrome(showProfiles: $showProfiles, showSettings: $showSettings))
                    .navigationDestination(for: MediaItem.self) { item in
                        DetailView(item: item, play: play)
                    }
            }
            .tabItem { Label("Películas", systemImage: "film.fill") }

            NavigationStack {
                BrowseView(type: "series")
                    .modifier(AppChrome(showProfiles: $showProfiles, showSettings: $showSettings))
                    .navigationDestination(for: MediaItem.self) { item in
                        DetailView(item: item, play: play)
                    }
            }
            .tabItem { Label("Series", systemImage: "tv.fill") }

            NavigationStack {
                LibraryView(play: play)
                    .modifier(AppChrome(showProfiles: $showProfiles, showSettings: $showSettings))
                    .navigationDestination(for: MediaItem.self) { item in
                        DetailView(item: item, play: play)
                    }
            }
            .tabItem { Label("Mi lista", systemImage: "bookmark.fill") }
        }
        .tint(Theme.rose)
        .background(Theme.ink)
        .toolbarBackground(Theme.ink, for: .tabBar)
        .toolbarBackground(.visible, for: .tabBar)
        .fullScreenCover(item: $playTarget) { target in
            PlayerScreen(
                model: model,
                item: target.item,
                season: target.season,
                episode: target.episode
            )
        }
        .sheet(isPresented: $showProfiles) {
            ProfilePickerSheet(
                profiles: profiles.profiles,
                selectedId: profiles.selectedId,
                onSelect: { profiles.select($0.id) }
            )
        }
        .sheet(isPresented: $showSettings) {
            SettingsSheet()
        }
    }
}

struct SettingsSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            SettingsView()
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cerrar") { dismiss() }
                            .foregroundStyle(Theme.rose)
                    }
                }
        }
        .preferredColorScheme(.dark)
    }
}

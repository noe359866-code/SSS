import SwiftUI
import UIKit

struct SettingsView: View {
    @EnvironmentObject private var settings: SettingsStore
    @EnvironmentObject private var plugins: PluginStore
    @EnvironmentObject private var profiles: ProfileStore

    var body: some View {
        List {
            Section {
                NavigationLink {
                    ProfilesView()
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Perfiles de reproducción")
                        Text("Activo: \(profiles.selected.name) · \(profiles.profiles.count) perfiles")
                            .font(.system(size: 11))
                            .foregroundStyle(Theme.muted)
                    }
                }
                NavigationLink {
                    SkipSettingsView()
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Saltos de intro, recap y créditos")
                        Text("TheIntroDB · IntroDB · AnimeSkip · AniSkip")
                            .font(.system(size: 11))
                            .foregroundStyle(Theme.muted)
                    }
                }
                NavigationLink {
                    PluginsView()
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Plugins")
                        Text(plugins.plugins.isEmpty
                             ? "Sin plugins instalados"
                             : "\(plugins.plugins.count) instalados · \(plugins.plugins.filter(\.enabled).count) activos")
                            .font(.system(size: 11))
                            .foregroundStyle(Theme.muted)
                    }
                }
            } header: {
                Text("Reproducción")
            }

            Section {
                NavigationLink {
                    CatalogSettingsView()
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Fuentes de catálogo")
                        Text(catalogStatus)
                            .font(.system(size: 11))
                            .foregroundStyle(Theme.muted)
                    }
                }
                NavigationLink("Red y reproducción") {
                    NetworkSettingsView()
                }
            } header: {
                Text("Catálogo")
            }

            Section {
                NavigationLink("Copia de seguridad") { DataSettingsView() }
                NavigationLink("Acerca de Cineflux") { AboutView() }
            } header: {
                Text("Datos")
            }

            Section {
                Text("Cineflux no incluye scrapers ni aloja contenido. Los plugins que instales solo añaden fuentes de reproducción.")
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.muted)
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.ink)
        .navigationTitle("Ajustes")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var catalogStatus: String {
        let count = settings.value.catalogSources.count
        let sources = count == 0 ? "Sin fuentes" : "\(count) fuentes de catálogo"
        let rt = settings.value.hasOmdb ? "OMDb conectado" : "OMDb opcional"
        return "\(sources) · \(rt)"
    }
}

struct CatalogSettingsView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var settings: SettingsStore

    @State private var newSource = ""
    @State private var message = ""
    @State private var testing = false

    private let languages = [("es-ES", "Español"), ("es-MX", "Latino"), ("en-US", "English")]
    private let regions = ["ES", "MX", "AR", "US"]

    var body: some View {
        Form {
            Section("Fuentes de catálogo") {
                Text("De aquí salen las filas y las fichas de la app. Puedes añadir una fuente o restaurar las oficiales. Sin fuentes no hay títulos.")
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.muted)
                ForEach(Array(settings.value.catalogSources.indices), id: \.self) { index in
                    let url = settings.value.catalogSources[index]
                    HStack(alignment: .center) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Fuente \(index + 1)").font(.system(size: 13, weight: .semibold))
                            Text(index == 0 ? "Catálogo principal" : "Catálogo adicional")
                                .font(.system(size: 10))
                                .foregroundStyle(Theme.muted)
                                .lineLimit(1)
                        }
                        Spacer()
                        Button("Quitar", role: .destructive) {
                            settings.update { $0.catalogSources.removeAll { $0 == url } }
                            message = "Fuente eliminada."
                        }
                        .font(.system(size: 12))
                    }
                }
                TextField("Dirección de la fuente", text: $newSource)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                Button("Añadir fuente") {
                    let url = newSource.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard url.hasPrefix("http") else {
                        message = "Pega una URL que empiece por https://"
                        return
                    }
                    settings.update { current in
                        if !current.catalogSources.contains(url) {
                            current.catalogSources.append(url)
                        }
                    }
                    newSource = ""
                    message = "Fuente añadida. Recarga el catálogo."
                }
                Button("Restaurar catálogo oficial") {
                    settings.update { $0.catalogSources = CatalogDefaults.sources }
                    message = "Fuentes oficiales restauradas."
                }
                .font(.system(size: 12))
            }

            Section("Rotten Tomatoes") {
                SecureField("Clave de OMDb (opcional)", text: Binding(
                    get: { settings.value.omdbKey },
                    set: { value in settings.update { $0.omdbKey = value.trimmingCharacters(in: .whitespaces) } }
                ))
                Text("OMDb entrega el Tomatometer usando el ID de IMDb. Plan gratuito: 1.000 consultas/día.")
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.muted)
                Button {
                    Task { await reload() }
                } label: {
                    if testing {
                        ProgressView().tint(.white)
                    } else {
                        Text("Recargar catálogo")
                    }
                }
                .disabled(testing)
                if !message.isEmpty {
                    Text(message).font(.system(size: 11)).foregroundStyle(Theme.golden)
                }
            }

            Section("Idioma y región") {
                Picker("Idioma de la interfaz y OMDb", selection: Binding(
                    get: { settings.value.language },
                    set: { value in settings.update { $0.language = value } }
                )) {
                    ForEach(languages, id: \.0) { code, label in
                        Text(label).tag(code)
                    }
                }
                Picker("Región de estrenos", selection: Binding(
                    get: { settings.value.region },
                    set: { value in settings.update { $0.region = value } }
                )) {
                    ForEach(regions, id: \.self) { code in
                        Text(code).tag(code)
                    }
                }
            }

            Section("Rotten Tomatoes") {
                Text("Las notas de crítica y audiencia aparecen en las fichas y en las filas del catálogo. Con OMDb llegan siempre; sin OMDb, Cineflux consulta la búsqueda pública de RT y, si tu red la bloquea, puedes configurar un proxy en Red.")
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.muted)
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.ink)
        .navigationTitle("Catálogo")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func reload() async {
        testing = true
        message = ""
        let result = await model.catalog.home(force: true)
        message = result.rows.isEmpty
            ? (result.message.isEmpty ? Messages.catalog : result.message)
            : "Catálogo actualizado · \(result.rows.count) filas."
        testing = false
    }

}

struct NetworkSettingsView: View {
    @EnvironmentObject private var settings: SettingsStore
    @EnvironmentObject private var library: LibraryStore

    var body: some View {
        Form {
            Section("Proxy de red") {
                TextField("https://tu-proxy.example/?url={url}", text: Binding(
                    get: { settings.value.proxyTemplate },
                    set: { value in settings.update { $0.proxyTemplate = value } }
                ))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                Text("Solo se usa si la petición directa falla. Debe incluir {url}.")
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.muted)
                TextField("User-Agent", text: Binding(
                    get: { settings.value.userAgent },
                    set: { value in settings.update { $0.userAgent = value } }
                ))
            }

            Section("Reproducción") {
                Toggle("Incluir candidatos torrent", isOn: Binding(
                    get: { settings.value.allowTorrents },
                    set: { value in settings.update { $0.allowTorrents = value } }
                ))
                Text("Por defecto se ignoran: Cineflux reproduce HTTP/HLS y no incluye motor torrent.")
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.muted)
                Button("Borrar progreso de reproducción", role: .destructive) {
                    library.clearProgress()
                }
            }

            Section {
                Text("En el móvil casi todo funciona sin proxy. Si un plugin concreto no responde, actualiza el plugin o ponlo por debajo de otro más rápido en la lista de plugins.")
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.muted)
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.ink)
        .navigationTitle("Red")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct DataSettingsView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var library: LibraryStore

    @State private var exportText = ""
    @State private var importText = ""
    @State private var message = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                SettingsCard {
                    Text("Exportar")
                        .font(.system(size: 14, weight: .semibold))
                    Text("Copia el JSON con toda tu configuración para pasarla a otro dispositivo.")
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.muted)
                    Button("Generar copia") {
                        exportText = model.exportBackup()
                        message = "Copia generada."
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    if !exportText.isEmpty {
                        Button("Copiar al portapapeles") {
                            UIPasteboard.general.string = exportText
                            message = "Copia lista en el portapapeles."
                        }
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(Theme.rose)
                    }
                }

                SettingsCard {
                    Text("Importar")
                        .font(.system(size: 14, weight: .semibold))
                    TextEditor(text: $importText)
                        .font(.system(size: 11, design: .monospaced))
                        .frame(height: 120)
                        .padding(6)
                        .background(Theme.panelHigh)
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                        .scrollContentBackground(.hidden)
                        .foregroundStyle(Theme.text)
                    Button("Importar copia") {
                        if let error = model.importBackup(importText) {
                            message = error
                        } else {
                            message = "Copia importada correctamente."
                            importText = ""
                        }
                    }
                    .buttonStyle(GhostButtonStyle())
                    .disabled(importText.trimmingCharacters(in: .whitespaces).isEmpty)
                }

                SettingsCard {
                    Text("Zona delicada")
                        .font(.system(size: 14, weight: .semibold))
                    Button("Borrar mi lista y el progreso", role: .destructive) {
                        library.clearAll()
                        message = "Lista y progreso borrados."
                    }
                    Button("Restablecer la app", role: .destructive) {
                        model.clearEverything()
                        message = "Todo restablecido."
                    }
                }

                if !message.isEmpty {
                    Text(message)
                        .font(.system(size: 11))
                        .foregroundStyle(Theme.golden)
                        .padding(.horizontal, 22)
                }
            }
            .padding(.vertical, 14)
        }
        .background(Theme.ink)
        .navigationTitle("Datos")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct AboutView: View {
    @EnvironmentObject private var settings: SettingsStore

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                SettingsCard {
                    Text("Cineflux")
                        .font(.system(size: 20, weight: .black))
                    Text("Cliente de catálogo y reproducción con plugins. Nada de scrapers dentro de la app.")
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.muted)
                    Text("Núcleo compartido: \(NativeCore.version()) (C++17)")
                        .font(.system(size: 11))
                        .foregroundStyle(Theme.muted)
                    Text("iOS: Swift + SwiftUI + AVPlayer")
                        .font(.system(size: 11))
                        .foregroundStyle(Theme.muted)
                    Text("Android: Kotlin + Jetpack Compose + Media3")
                        .font(.system(size: 11))
                        .foregroundStyle(Theme.muted)
                    Text("Catálogo y puntuaciones de Rotten Tomatoes")
                        .font(.system(size: 11))
                        .foregroundStyle(Theme.muted)
                }

                SettingsCard {
                    Text("Cómo funcionan los plugins")
                        .font(.system(size: 14, weight: .semibold))
                    Text("Cada plugin aporta fuentes de reproducción para películas y series. La app lo valida, deduce los identificadores IMDb/TMDB y le pide opciones. Los plugins se instalan desde su enlace.")
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.muted)
                    Text("Los perfiles deciden qué stream se reproduce: idioma, calidad, HDR, tamaño y prioridad de plugins. El reproductor prueba la mejor opción y, si falla, cambia a la siguiente sin mostrar errores de fuentes.")
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.muted)
                }

                SettingsCard {
                    Text("Aviso")
                        .font(.system(size: 14, weight: .semibold))
                    Text("Cineflux no aloja ni distribuye contenido. Los plugins son componentes de terceros que tú decides instalar; asegúrate de usarlos con contenido al que tengas derecho de acceso.")
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.muted)
                }
            }
            .padding(.vertical, 14)
        }
        .background(Theme.ink)
        .navigationTitle("Acerca de")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct SkipSettingsView: View {
    @EnvironmentObject private var settings: SettingsStore

    var body: some View {
        Form {
            Section {
                Toggle("Mostrar botón de salto", isOn: Binding(
                    get: { settings.value.skipSegmentsEnabled },
                    set: { value in settings.update { $0.skipSegmentsEnabled = value } }
                ))
                Text("Aparece durante la intro, el recap y los créditos. Sin avisos ni errores.")
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.muted)
            } header: {
                Text("Botón")
            }

            Section {
                Toggle("Saltar intros automáticamente", isOn: Binding(
                    get: { settings.value.autoSkipIntro },
                    set: { value in settings.update { $0.autoSkipIntro = value } }
                ))
                Toggle("Saltar recaps automáticamente", isOn: Binding(
                    get: { settings.value.autoSkipRecap },
                    set: { value in settings.update { $0.autoSkipRecap = value } }
                ))
                Toggle("Saltar créditos automáticamente", isOn: Binding(
                    get: { settings.value.autoSkipCredits },
                    set: { value in settings.update { $0.autoSkipCredits = value } }
                ))
            } header: {
                Text("Automático")
            } footer: {
                Text("Los tiempos son de la comunidad y pueden variar entre versiones del episodio. Si un salto cae mal, desactiva su automatismo: el botón seguirá disponible.")
            }

            Section {
                Text("TheIntroDB (series, películas y anime) e IntroDB se consultan siempre. AniSkip se usa en anime con el ID de MyAnimeList. Anime Skip enriquece los tiempos con tu client ID.")
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.muted)
                SecureField("Anime Skip · client ID (opcional)", text: Binding(
                    get: { settings.value.animeSkipClientId },
                    set: { value in settings.update { $0.animeSkipClientId = value.trimmingCharacters(in: .whitespaces) } }
                ))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                Text("Sin client ID se usa AniSkip (MAL) y las demás fuentes.")
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.muted)
            } header: {
                Text("Fuentes")
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.ink)
        .navigationTitle("Saltos")
        .navigationBarTitleDisplayMode(.inline)
    }
}

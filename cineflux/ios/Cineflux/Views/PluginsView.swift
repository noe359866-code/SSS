import SwiftUI

struct PluginsView: View {
    @EnvironmentObject private var plugins: PluginStore

    @State private var input = ""
    @State private var baseURL = ""
    @State private var installing = false
    @State private var message = ""
    @State private var success = false
    @State private var feedback: [String: String] = [:]
    @State private var pendingDelete: Plugin?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                installCard
                howCard

                if plugins.plugins.isEmpty {
                    EmptyStateView(
                        title: "Todavía no hay plugins",
                        message: "Instala un plugin de streams para poder reproducir. Los plugins de catálogo añaden filas extra a la app."
                    )
                } else {
                    Text("Instalados")
                        .font(.system(size: 19, weight: .bold))
                        .foregroundStyle(Theme.text)
                        .padding(.horizontal, 18)
                    ForEach(plugins.plugins) { plugin in
                        pluginCard(plugin)
                    }
                }
            }
            .padding(.vertical, 14)
        }
        .background(Theme.ink)
        .navigationTitle("Plugins")
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog(
            "Quitar plugin",
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { if !$0 { pendingDelete = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Quitar", role: .destructive) {
                if let pendingDelete {
                    plugins.remove(pendingDelete)
                }
                pendingDelete = nil
            }
        } message: {
            Text("Se eliminará de este dispositivo. Puedes volver a instalarlo cuando quieras.")
        }
    }

    private var installCard: some View {
        SettingsCard {
            HStack(spacing: 8) {
                Image(systemName: "puzzlepiece.extension.fill")
                    .foregroundStyle(Theme.rose)
                Text("Añadir plugin")
                    .font(.system(size: 16, weight: .bold))
            }
            Text("Pega la URL del manifest o el JSON en crudo. Si el manifest no trae transportUrl, indícalo abajo.")
                .font(.system(size: 12))
                .foregroundStyle(Theme.muted)

            TextEditor(text: $input)
                .font(.system(size: 11, design: .monospaced))
                .frame(height: 120)
                .padding(6)
                .background(Theme.panelHigh)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .scrollContentBackground(.hidden)
                .foregroundStyle(Theme.text)
                .overlay(alignment: .topLeading) {
                    if input.isEmpty {
                        Text("https://mi-addon.example/manifest.json")
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundStyle(Theme.muted)
                            .padding(.horizontal, 11)
                            .padding(.vertical, 14)
                            .allowsHitTesting(false)
                    }
                }

            TextField("URL base (opcional)", text: $baseURL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(10)
                .background(Theme.panelHigh)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .foregroundStyle(Theme.text)

            Text("Solo para manifests en crudo que no incluyan transportUrl.")
                .font(.system(size: 10))
                .foregroundStyle(Theme.muted)

            HStack(spacing: 10) {
                Button {
                    Task { await install() }
                } label: {
                    HStack(spacing: 6) {
                        if installing { ProgressView().tint(.white) }
                        Text("Instalar")
                    }
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(input.trimmingCharacters(in: .whitespaces).isEmpty || installing)

                Menu("Ejemplos") {
                    Button("Ejemplo: streams") {
                        input = PluginExamples.streams
                        baseURL = ""
                    }
                    Button("Ejemplo: catálogo") {
                        input = PluginExamples.catalog
                        baseURL = ""
                    }
                }
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(Theme.rose)
            }

            if !message.isEmpty {
                Text(message)
                    .font(.system(size: 11))
                    .foregroundStyle(success ? Color(hex: "#6EE7B7") : Theme.golden)
            }
        }
    }

    private var howCard: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Cómo se usa un manifest")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Theme.text)
            Text("El manifest declara recursos (stream, catalog, meta, subtitles). Cineflux pide los streams a todos los plugins activos a la vez, los ordena con el perfil activo y reproduce el primero que funcione. Si una opción falla, prueba la siguiente sin preguntarte nada.")
                .font(.system(size: 11))
                .foregroundStyle(Theme.muted)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.panelHigh)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .padding(.horizontal, 18)
    }

    private func pluginCard(_ plugin: Plugin) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                ZStack {
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(Theme.panelHigh)
                        .frame(width: 38, height: 38)
                    Text(String(plugin.manifest.name.prefix(1)).uppercased())
                        .font(.system(size: 16, weight: .black))
                        .foregroundStyle(Theme.rose)
                }
                VStack(alignment: .leading, spacing: 1) {
                    Text(plugin.manifest.name.isEmpty ? "Plugin" : plugin.manifest.name)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Theme.text)
                    Text(plugin.canStream ? "Streams" : "Catálogo")
                        .font(.system(size: 10))
                        .foregroundStyle(Theme.muted)
                }
                Spacer()
                Toggle("", isOn: Binding(
                    get: { plugin.enabled },
                    set: { plugins.setEnabled(plugin, enabled: $0) }
                ))
                .labelsHidden()
                .tint(Theme.rose)
            }

            if !plugin.manifest.description.isEmpty {
                Text(plugin.manifest.description)
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.muted)
                    .lineLimit(2)
            }

            HStack(spacing: 6) {
                ForEach(plugin.manifest.resourceNames.prefix(4), id: \.self) { name in
                    Badge(text: name, foreground: Theme.muted, background: Theme.panelHigh)
                }
                if !plugin.manifest.types.isEmpty {
                    Badge(text: plugin.manifest.types.joined(separator: " · "), foreground: Theme.muted, background: Theme.panelHigh)
                }
            }

            if plugin.manifest.baseUrl.isEmpty {
                Text("Falta la URL de transporte: este plugin no puede pedir streams.")
                    .font(.system(size: 10))
                    .foregroundStyle(Theme.golden)
            }

            HStack(spacing: 4) {
                Text("Prioridad \(plugin.priority + 1)")
                    .font(.system(size: 10))
                    .foregroundStyle(Theme.muted)
                Button { plugins.move(plugin, delta: -1) } label: {
                    Image(systemName: "arrow.up").font(.system(size: 11))
                }
                .foregroundStyle(Theme.muted)
                Button { plugins.move(plugin, delta: 1) } label: {
                    Image(systemName: "arrow.down").font(.system(size: 11))
                }
                .foregroundStyle(Theme.muted)

                Spacer()

                Button("Probar") {
                    Task {
                        feedback[plugin.key] = "Comprobando…"
                        feedback[plugin.key] = await plugins.test(plugin).1
                    }
                }
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(Theme.muted)

                Button("Actualizar") {
                    Task {
                        feedback[plugin.key] = "Actualizando…"
                        feedback[plugin.key] = await plugins.refresh(plugin).1
                    }
                }
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(Theme.muted)

                Button {
                    pendingDelete = plugin
                } label: {
                    Image(systemName: "trash")
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.rose.opacity(0.8))
                }
            }

            if let text = feedback[plugin.key], !text.isEmpty {
                Text(text)
                    .font(.system(size: 10))
                    .foregroundStyle(Theme.golden)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.panel)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .padding(.horizontal, 18)
    }

    private func install() async {
        installing = true
        message = ""
        let result = await plugins.install(input: input, baseURL: baseURL)
        success = result.0
        message = result.1
        if result.0 {
            input = ""
            baseURL = ""
        }
        installing = false
    }
}

enum PluginExamples {
    static let catalog = """
    {
      "id": "com.linvo.cinemeta",
      "version": "3.0.0",
      "name": "Cinemeta",
      "description": "Catálogo y metadatos de películas y series.",
      "types": ["movie", "series"],
      "resources": ["catalog", "meta"],
      "catalogs": [
        {"type": "movie", "id": "top", "name": "Películas mejor valoradas"},
        {"type": "series", "id": "top", "name": "Series mejor valoradas"}
      ],
      "idPrefixes": ["tt"],
      "transportUrl": "https://v3-cinemeta.strem.io/manifest.json"
    }
    """

    static let streams = """
    {
      "id": "mi.plugin.streams",
      "version": "1.0.0",
      "name": "Mi plugin",
      "description": "Sustituye transportUrl por la base de tu addon (manifest en crudo).",
      "types": ["movie", "series"],
      "resources": [
        {"name": "stream", "types": ["movie", "series"], "idPrefixes": ["tt"]}
      ],
      "idPrefixes": ["tt"],
      "transportUrl": "https://mi-servidor.example/manifest.json"
    }
    """
}

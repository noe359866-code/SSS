import SwiftUI
import UniformTypeIdentifiers

struct PluginsView: View {
    @EnvironmentObject private var plugins: PluginStore

    @State private var input = ""
    @State private var installing = false
    @State private var message = ""
    @State private var success = false
    @State private var feedback: [String: String] = [:]
    @State private var pendingDelete: Plugin?
    @State private var pendingDeleteJs: JsPlugin?
    @State private var showingFileImporter = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                installCard
                howCard

                if plugins.plugins.isEmpty && plugins.jsPlugins.isEmpty {
                    EmptyStateView(
                        title: "Todavía no hay plugins",
                        message: "Añade un plugin por URL, un repositorio JS estilo Nuvio o importa un archivo .js local. Las fuentes de catálogo se añaden en Ajustes → Catálogo."
                    )
                } else {
                    if !plugins.plugins.isEmpty {
                        Text("Addons de streams")
                            .font(.system(size: 19, weight: .bold))
                            .foregroundStyle(Theme.text)
                            .padding(.horizontal, 18)
                        ForEach(plugins.plugins) { plugin in
                            pluginCard(plugin)
                        }
                    }
                    if !plugins.jsPlugins.isEmpty {
                        Text("Plugins JS locales")
                            .font(.system(size: 19, weight: .bold))
                            .foregroundStyle(Theme.text)
                            .padding(.horizontal, 18)
                        ForEach(plugins.jsPlugins) { jsPlugin in
                            jsPluginCard(jsPlugin)
                        }
                    }
                }
            }
            .padding(.vertical, 14)
        }
        .background(Theme.ink)
        .navigationTitle("Plugins")
        .navigationBarTitleDisplayMode(.inline)
        .fileImporter(
            isPresented: $showingFileImporter,
            allowedContentTypes: [.javaScript, .plainText, .data],
            allowsMultipleSelection: false
        ) { result in
            guard case .success(let urls) = result, let url = urls.first else { return }
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            guard let code = try? String(contentsOf: url, encoding: .utf8) else {
                success = false
                message = "No se pudo leer el archivo .js seleccionado."
                return
            }
            let res = plugins.installLocalJs(name: url.lastPathComponent, code: code)
            success = res.0
            message = res.1
        }
        .confirmationDialog(
            "Quitar plugin",
            isPresented: Binding(
                get: { pendingDelete != nil || pendingDeleteJs != nil },
                set: { if !$0 { pendingDelete = nil; pendingDeleteJs = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Quitar", role: .destructive) {
                if let pendingDelete {
                    plugins.remove(pendingDelete)
                } else if let pendingDeleteJs {
                    plugins.removeJs(pendingDeleteJs)
                }
                pendingDelete = nil
                pendingDeleteJs = nil
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
            Text("Pega el enlace de un plugin, un repositorio JS estilo Nuvio (manifest.json) o un archivo .js directo, o importa un .js local.")
                .font(.system(size: 12))
                .foregroundStyle(Theme.muted)

            TextField("https://…/manifest.json o https://…/plugin.js", text: $input)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
                .padding(10)
                .background(Theme.panelHigh)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .foregroundStyle(Theme.text)

            Text("Compatible con addons de streams, repos Nuvio JS y scripts .js.")
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

                Button("Importar .js") {
                    showingFileImporter = true
                }
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(Theme.text)
                .disabled(installing)
            }

            if !message.isEmpty {
                Text(message)
                    .font(.system(size: 11))
                    .foregroundStyle(success ? Color(hex: "#6EE7B7") : Theme.golden)
            }
        }
    }

    private func jsPluginCard(_ plugin: JsPlugin) -> some View {
        SettingsCard {
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(plugin.name)
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(Theme.text)
                            .lineLimit(1)
                        Text("JS")
                            .font(.system(size: 10, weight: .bold))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Theme.rose.opacity(0.2))
                            .foregroundStyle(Theme.rose)
                            .clipShape(Capsule())
                    }
                    let origin = !plugin.repoName.isEmpty ? plugin.repoName : (plugin.isLocalFile ? "Archivo local" : "URL")
                    Text("\(origin) · v\(plugin.version) · \(plugin.types.joined(separator: " · "))")
                        .font(.system(size: 11))
                        .foregroundStyle(Theme.muted)
                }
                Spacer()
                Toggle("", isOn: Binding(
                    get: { plugin.enabled },
                    set: { plugins.setJsEnabled(plugin, enabled: $0) }
                ))
                .labelsHidden()
                .tint(Theme.rose)
            }

            if !plugin.description.isEmpty {
                Text(plugin.description)
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.muted)
                    .lineLimit(2)
            }

            HStack(spacing: 12) {
                Button("Probar") {
                    let res = plugins.testJs(plugin)
                    feedback[plugin.key] = res.1
                }
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(Theme.text)

                if !plugin.isLocalFile {
                    Button("Actualizar") {
                        Task {
                            feedback[plugin.key] = "Actualizando…"
                            let res = await plugins.refreshJs(plugin)
                            feedback[plugin.key] = res.1
                        }
                    }
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(Theme.text)
                }

                Spacer()

                Button { plugins.moveJs(plugin, delta: -1) } label: {
                    Image(systemName: "chevron.up").foregroundStyle(Theme.muted)
                }
                Button { plugins.moveJs(plugin, delta: 1) } label: {
                    Image(systemName: "chevron.down").foregroundStyle(Theme.muted)
                }
                Button { pendingDeleteJs = plugin } label: {
                    Image(systemName: "trash").foregroundStyle(Theme.rose)
                }
            }

            if let fb = feedback[plugin.key], !fb.isEmpty {
                Text(fb)
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.golden)
            }
        }
    }

    private var howCard: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Cómo funcionan los plugins")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Theme.text)
            Text("Cineflux consulta todos los plugins activos a la vez, ordena las opciones con el perfil activo y reproduce la primera que funcione. Si una opción falla, prueba la siguiente sin preguntarte nada.")
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
                    Text("Reproducción")
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

            if plugin.manifest.baseUrl.isEmpty {
                Text("Este plugin no está disponible.")
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
        let result = await plugins.install(input: input, baseURL: "")
        success = result.0
        message = result.1
        if result.0 {
            input = ""
        }
        installing = false
    }
}

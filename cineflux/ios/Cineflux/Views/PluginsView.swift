import SwiftUI

struct PluginsView: View {
    @EnvironmentObject private var plugins: PluginStore

    @State private var input = ""
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
                        message: "Solo necesitas un plugin de reproducción. Las fuentes de catálogo se añaden en Ajustes → Catálogo."
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
            Text("Pega el enlace del plugin. Cineflux lo valida y lo añade a tu lista.")
                .font(.system(size: 12))
                .foregroundStyle(Theme.muted)

            TextField("https://…", text: $input)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
                .padding(10)
                .background(Theme.panelHigh)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .foregroundStyle(Theme.text)

            Text("Se comprobará antes de instalarlo.")
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

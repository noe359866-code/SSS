import SwiftUI

private let accents = ["#22d3ee", "#f43f5e", "#f59e0b", "#a78bfa", "#34d399", "#94a3b8"]
private let languagesList: [(String, String)] = [
    ("latino", "Latino"),
    ("castellano", "Castellano"),
    ("es", "Español"),
    ("dual", "Dual"),
    ("en", "Original"),
    ("sub", "Subtítulos")
]
private let qualities = [480, 720, 1080, 1440, 2160]

struct ProfilesView: View {
    @EnvironmentObject private var profiles: ProfileStore
    @State private var editing: Profile?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                SettingsCard {
                    Text("Sin selector de webs")
                        .font(.system(size: 16, weight: .bold))
                    Text("No eliges servidores ni fuentes: eliges un perfil. Con él, el núcleo puntúa todos los streams disponibles (idioma, calidad, HDR, tamaño y prioridad de plugins) y el reproductor arranca la mejor opción. Si falla, salta a la siguiente sin mostrar errores.")
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.muted)
                    Button {
                        editing = Profile(
                            id: "custom-\(Int(Date().timeIntervalSince1970))",
                            name: "",
                            accent: accents.randomElement() ?? "#22d3ee",
                            languages: ["latino"],
                            targetQuality: 1080,
                            maxQuality: 1080
                        )
                    } label: {
                        Label("Crear perfil", systemImage: "plus")
                    }
                    .buttonStyle(PrimaryButtonStyle())
                }

                Text("Tus perfiles")
                    .font(.system(size: 19, weight: .bold))
                    .foregroundStyle(Theme.text)
                    .padding(.horizontal, 22)

                ForEach(profiles.profiles) { profile in
                    profileRow(profile)
                }

                Button("Restaurar perfiles recomendados") {
                    profiles.resetToDefaults()
                }
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(Theme.muted)
                .padding(.horizontal, 22)
            }
            .padding(.vertical, 14)
        }
        .background(Theme.ink)
        .navigationTitle("Perfiles")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $editing) { profile in
            ProfileEditorSheet(initial: profile) { updated in
                profiles.save(updated)
            }
        }
    }

    private func profileRow(_ profile: Profile) -> some View {
        let active = profile.id == profiles.selectedId
        return VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 10) {
                Circle().fill(colorFromHex(profile.accent)).frame(width: 12, height: 12)
                VStack(alignment: .leading, spacing: 2) {
                    Text(profile.name)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Theme.text)
                    Text(profile.summary)
                        .font(.system(size: 10))
                        .foregroundStyle(Theme.muted)
                }
                Spacer()
                if active {
                    Text("Activo")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(Theme.rose)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(Theme.rose.opacity(0.18))
                        .clipShape(Capsule())
                }
            }
            if !profile.description.isEmpty {
                Text(profile.description)
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.muted)
            }
            HStack(spacing: 14) {
                if !active {
                    Button("Usar este perfil") {
                        profiles.select(profile.id)
                    }
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(Theme.rose)
                }
                Spacer()
                Button("Editar") { editing = profile }
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(Theme.muted)
                Button("Eliminar", role: .destructive) {
                    _ = profiles.delete(profile)
                }
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(Theme.rose.opacity(0.8))
            }
        }
        .padding(14)
        .background(active ? Theme.panelHigh : Theme.panel)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .padding(.horizontal, 18)
    }
}

struct ProfileEditorSheet: View {
    @Environment(\.dismiss) private var dismiss

    @State private var draft: Profile
    @State private var error = ""
    private let onSave: (Profile) -> String?

    init(initial: Profile, onSave: @escaping (Profile) -> String?) {
        _draft = State(initialValue: initial)
        self.onSave = onSave
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Nombre") {
                    TextField("Latino 1080p", text: $draft.name)
                    TextField("Descripción breve", text: $draft.description)
                    HStack(spacing: 10) {
                        ForEach(accents, id: \.self) { hex in
                            Button {
                                draft.accent = hex
                            } label: {
                                Circle()
                                    .fill(colorFromHex(hex))
                                    .frame(width: 28, height: 28)
                                    .overlay(
                                        Circle().strokeBorder(
                                            draft.accent == hex ? Color.white : .clear,
                                            lineWidth: 2
                                        )
                                    )
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }

                Section("Idioma (orden de preferencia)") {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 88), spacing: 8)], alignment: .leading, spacing: 8) {
                        ForEach(languagesList, id: \.0) { tag, label in
                            ChipButton(text: label, selected: draft.languages.contains(tag)) {
                                if draft.languages.contains(tag) {
                                    draft.languages.removeAll { $0 == tag }
                                } else {
                                    draft.languages.append(tag)
                                }
                            }
                        }
                    }
                    .padding(.vertical, 4)
                }

                Section("Calidad") {
                    Picker("Objetivo", selection: $draft.targetQuality) {
                        ForEach(qualities, id: \.self) { quality in
                            Text("\(quality)p").tag(quality)
                        }
                    }
                    Picker("Techo", selection: $draft.maxQuality) {
                        ForEach(qualities.filter { $0 >= draft.targetQuality }, id: \.self) { quality in
                            Text("\(quality)p").tag(quality)
                        }
                    }
                }

                Section("Preferencias") {
                    Toggle("Permitir CAM", isOn: $draft.allowCam)
                    Toggle("Preferir HDR", isOn: $draft.preferHdr)
                    Toggle("Preferir subtítulos", isOn: $draft.preferSubtitles)
                    Toggle("Preferir dual", isOn: $draft.preferDual)
                    Toggle("Idioma estricto", isOn: $draft.strictLanguage)
                    Toggle("Ahorro de datos", isOn: $draft.dataSaver)
                }

                if !error.isEmpty {
                    Section {
                        Text(error)
                            .font(.system(size: 11))
                            .foregroundStyle(Theme.rose)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(Theme.ink)
            .navigationTitle(draft.name.isEmpty ? "Nuevo perfil" : "Editar perfil")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancelar") { dismiss() }
                        .foregroundStyle(Theme.muted)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Guardar") {
                        if let message = onSave(draft) {
                            error = message
                        } else {
                            dismiss()
                        }
                    }
                    .foregroundStyle(Theme.rose)
                }
            }
            .onChange(of: draft.targetQuality) { newValue in
                if draft.maxQuality < newValue {
                    draft.maxQuality = newValue
                }
            }
        }
        .preferredColorScheme(.dark)
    }
}

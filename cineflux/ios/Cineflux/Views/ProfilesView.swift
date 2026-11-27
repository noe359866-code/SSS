import SwiftUI

private let netflixAvatarColors = [
    "#E50914", // Rojo Netflix
    "#00A8E8", // Azul clásico
    "#F5C518", // Amarillo clásico
    "#10B981", // Verde esmeralda
    "#8B5CF6", // Morado
    "#EC4899"  // Rosa
]

private let languagesList: [(String, String)] = [
    ("latino", "Latino"),
    ("castellano", "Castellano"),
    ("es", "Español"),
    ("dual", "Dual"),
    ("en", "Original"),
    ("sub", "Subtítulos")
]

private let qualities = [480, 720, 1080, 1440, 2160]

/// Avatar cuadrado estilo Netflix (sonrisa minimalista sobre degradado vibrante).
struct NetflixProfileAvatar: View {
    let accentHex: String
    var size: CGFloat = 112
    var selected: Bool = false
    var editOverlay: Bool = false

    var body: some View {
        let base = colorFromHex(accentHex)
        ZStack {
            LinearGradient(
                colors: [base, base.opacity(0.58)],
                startPoint: .top,
                endPoint: .bottom
            )

            Canvas { ctx, sz in
                let w = sz.width
                let h = sz.height
                let eyeR = w * 0.068
                let eyeY = h * 0.37

                let leftEye = Path(ellipseIn: CGRect(x: w * 0.30 - eyeR, y: eyeY - eyeR, width: eyeR * 2, height: eyeR * 2))
                let rightEye = Path(ellipseIn: CGRect(x: w * 0.70 - eyeR, y: eyeY - eyeR, width: eyeR * 2, height: eyeR * 2))
                ctx.fill(leftEye, with: .color(.white))
                ctx.fill(rightEye, with: .color(.white))

                var smile = Path()
                smile.move(to: CGPoint(x: w * 0.24, y: h * 0.56))
                smile.addQuadCurve(
                    to: CGPoint(x: w * 0.76, y: h * 0.56),
                    control: CGPoint(x: w * 0.50, y: h * 0.78)
                )
                ctx.stroke(smile, with: .color(.white), style: StrokeStyle(lineWidth: w * 0.075, lineCap: .round))
            }

            if editOverlay {
                Color.black.opacity(0.52)
                Image(systemName: "pencil")
                    .font(.system(size: size * 0.28, weight: .bold))
                    .foregroundStyle(.white)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .stroke(selected ? Color.white : Theme.stroke.opacity(0.4), lineWidth: selected ? 2.5 : 1)
        )
    }
}

struct ProfilesView: View {
    @EnvironmentObject private var profiles: ProfileStore
    @Environment(\.dismiss) private var dismiss

    @State private var manageMode = false
    @State private var editing: Profile?

    private let columns = [
        GridItem(.flexible(), spacing: 22),
        GridItem(.flexible(), spacing: 22)
    ]

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                LazyVGrid(columns: columns, spacing: 24) {
                    ForEach(profiles.profiles) { profile in
                        let isSelected = profile.id == profiles.selectedId
                        Button {
                            if manageMode {
                                editing = profile
                            } else {
                                profiles.select(profile.id)
                                dismiss()
                            }
                        } label: {
                            VStack(spacing: 8) {
                                NetflixProfileAvatar(
                                    accentHex: profile.accent,
                                    size: 112,
                                    selected: isSelected && !manageMode,
                                    editOverlay: manageMode
                                )
                                Text(profile.name)
                                    .font(.system(size: 15, weight: isSelected ? .bold : .medium))
                                    .foregroundStyle(isSelected ? Theme.text : Theme.muted)
                                    .lineLimit(1)
                                Text(profile.summary)
                                    .font(.system(size: 10, weight: .medium))
                                    .foregroundStyle(Theme.muted)
                                    .lineLimit(1)
                            }
                        }
                        .buttonStyle(.plain)
                    }

                    // Tarjeta "+ Añadir perfil" estilo Netflix
                    Button {
                        editing = Profile(
                            id: "custom-\(Int(Date().timeIntervalSince1970))",
                            name: "",
                            accent: netflixAvatarColors.randomElement() ?? "#E50914",
                            languages: ["latino"],
                            targetQuality: 1080,
                            maxQuality: 1080
                        )
                    } label: {
                        VStack(spacing: 8) {
                            ZStack {
                                RoundedRectangle(cornerRadius: 10, style: .continuous)
                                    .fill(Theme.panel)
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                                            .stroke(Theme.stroke, lineWidth: 1.5)
                                    )
                                Image(systemName: "plus")
                                    .font(.system(size: 38, weight: .light))
                                    .foregroundStyle(Theme.text)
                            }
                            .frame(width: 112, height: 112)

                            Text("Añadir perfil")
                                .font(.system(size: 15, weight: .medium))
                                .foregroundStyle(Theme.muted)
                        }
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 32)
                .padding(.top, 20)

                Button("Restaurar perfiles recomendados") {
                    profiles.resetToDefaults()
                }
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(Theme.muted)
                .padding(.bottom, 24)
            }
        }
        .background(Theme.ink)
        .navigationTitle(manageMode ? "Administrar perfiles" : "¿Quién está viendo?")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button(manageMode ? "Listo" : "Editar") {
                    manageMode.toggle()
                }
                .foregroundStyle(Theme.text)
                .fontWeight(.semibold)
            }
        }
        .sheet(item: $editing) { profile in
            let isExisting = profiles.profiles.contains { $0.id == profile.id }
            ProfileEditorSheet(
                initial: profile,
                canDelete: isExisting && profiles.profiles.count > 1,
                onSave: { updated in profiles.save(updated) },
                onDelete: { _ = profiles.delete(profile) }
            )
        }
    }
}

private struct ProfileEditorSheet: View {
    @Environment(\.dismiss) private var dismiss

    let initial: Profile
    let canDelete: Bool
    let onSave: (Profile) -> Void
    let onDelete: () -> Void

    @State private var name: String
    @State private var accent: String
    @State private var languages: Set<String>
    @State private var targetQuality: Int
    @State private var preferHdr: Bool
    @State private var preferDual: Bool
    @State private var preferSubtitles: Bool
    @State private var strictLanguage: Bool
    @State private var dataSaver: Bool
    @State private var allowCam: Bool

    init(
        initial: Profile,
        canDelete: Bool,
        onSave: @escaping (Profile) -> Void,
        onDelete: @escaping () -> Void
    ) {
        self.initial = initial
        self.canDelete = canDelete
        self.onSave = onSave
        self.onDelete = onDelete
        _name = State(initialValue: initial.name)
        _accent = State(initialValue: initial.accent)
        _languages = State(initialValue: Set(initial.languages))
        _targetQuality = State(initialValue: initial.targetQuality)
        _preferHdr = State(initialValue: initial.preferHdr)
        _preferDual = State(initialValue: initial.preferDual)
        _preferSubtitles = State(initialValue: initial.preferSubtitles)
        _strictLanguage = State(initialValue: initial.strictLanguage)
        _dataSaver = State(initialValue: initial.dataSaver)
        _allowCam = State(initialValue: initial.allowCam)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(spacing: 14) {
                        NetflixProfileAvatar(accentHex: accent, size: 96, selected: true)
                        HStack(spacing: 10) {
                            ForEach(netflixAvatarColors, id: \.self) { hex in
                                let chosen = hex.lowercased() == accent.lowercased()
                                Button {
                                    accent = hex
                                } label: {
                                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                                        .fill(colorFromHex(hex))
                                        .frame(width: 34, height: 34)
                                        .overlay(
                                            RoundedRectangle(cornerRadius: 8, style: .continuous)
                                                .stroke(chosen ? Color.white : .clear, lineWidth: 2)
                                        )
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
                    .listRowBackground(Color.clear)

                    TextField("Nombre del perfil", text: $name)
                }

                Section("Idiomas preferidos") {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(languagesList, id: \.0) { code, label in
                                let active = languages.contains(code)
                                Button(label) {
                                    if active { languages.remove(code) } else { languages.insert(code) }
                                }
                                .font(.system(size: 12, weight: .semibold))
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(active ? Theme.rose : Theme.panelHigh)
                                .foregroundStyle(active ? .white : Theme.muted)
                                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                                .buttonStyle(.plain)
                            }
                        }
                    }
                }

                Section("Calidad máxima") {
                    Picker("Calidad", selection: $targetQuality) {
                        ForEach(qualities, id: \.self) { q in
                            Text(q == 2160 ? "4K" : "\(q)p").tag(q)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                Section("Preferencias de reproducción") {
                    Toggle("Preferir HDR / Dolby Vision", isOn: $preferHdr).tint(Theme.rose)
                    Toggle("Preferir audio Dual", isOn: $preferDual).tint(Theme.rose)
                    Toggle("Preferir subtítulos", isOn: $preferSubtitles).tint(Theme.rose)
                    Toggle("Exigir idioma seleccionado", isOn: $strictLanguage).tint(Theme.rose)
                    Toggle("Ahorro de datos", isOn: $dataSaver).tint(Theme.rose)
                    Toggle("Permitir copias CAM", isOn: $allowCam).tint(Theme.rose)
                }

                if canDelete {
                    Section {
                        Button("Eliminar perfil", role: .destructive) {
                            onDelete()
                            dismiss()
                        }
                    }
                }
            }
            .navigationTitle(name.isEmpty ? "Nuevo perfil" : "Editar perfil")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancelar") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Guardar") {
                        var copy = initial
                        copy.name = name.trimmingCharacters(in: .whitespaces).isEmpty ? "Perfil" : name.trimmingCharacters(in: .whitespaces)
                        copy.accent = accent
                        copy.languages = Array(languages)
                        copy.targetQuality = targetQuality
                        copy.maxQuality = targetQuality
                        copy.preferHdr = preferHdr
                        copy.preferDual = preferDual
                        copy.preferSubtitles = preferSubtitles
                        copy.strictLanguage = strictLanguage
                        copy.dataSaver = dataSaver
                        copy.allowCam = allowCam
                        onSave(copy)
                        dismiss()
                    }
                    .fontWeight(.bold)
                }
            }
        }
    }
}

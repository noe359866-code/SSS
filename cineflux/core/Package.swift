// swift-tools-version:5.9
// Paquete Swift que expone el núcleo C++ a iOS mediante la API C de capi.h.
import PackageDescription

let package = Package(
    name: "CinefluxCore",
    platforms: [.iOS(.v16)],
    products: [
        .library(name: "CinefluxCore", targets: ["CinefluxCore"])
    ],
    targets: [
        .target(
            name: "CinefluxCore",
            path: ".",
            exclude: ["tests", "tools", "build", "CMakeLists.txt", "Makefile"],
            sources: ["src"],
            publicHeadersPath: "include/cineflux",
            cxxSettings: [.headerSearchPath("include")],
            linkerSettings: [.linkedLibrary("c++")]
        )
    ],
    cxxLanguageStandard: .cxx17
)

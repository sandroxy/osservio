// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "Osservio",
    defaultLocalization: "en",
    platforms: [.iOS(.v13), .macOS(.v10_15)],
    products: [
        .library(name: "OsservioCore", targets: ["OsservioCore"]),
        .library(name: "OsservioStore", targets: ["OsservioStore"]),
        .library(name: "OsservioUI", targets: ["OsservioUI"]),
        .library(name: "OsservioURLSession", targets: ["OsservioURLSession"]),
        .library(name: "OsservioRuntime", targets: ["OsservioRuntime"]),
        .library(name: "OsservioExport", targets: ["OsservioExport"])
    ],
    targets: [
        .target(name: "OsservioCore", path: "native/ios/Sources/OsservioCore"),
        .target(name: "OsservioStore", dependencies: ["OsservioCore"], path: "native/ios/Sources/OsservioStore",
                linkerSettings: [.linkedLibrary("sqlite3")]),
        .target(name: "OsservioUI", dependencies: ["OsservioCore"], path: "native/ios/Sources/OsservioUI",
                resources: [.process("Resources")]),
        .target(name: "OsservioURLSession", dependencies: ["OsservioCore"], path: "native/ios/Sources/OsservioURLSession"),
        .target(name: "OsservioRuntime", dependencies: ["OsservioCore"], path: "native/ios/Sources/OsservioRuntime"),
        .target(name: "OsservioExport", dependencies: ["OsservioCore"], path: "native/ios/Sources/OsservioExport"),
        .testTarget(name: "OsservioCoreTests", dependencies: ["OsservioCore", "OsservioStore"], path: "native/ios/Tests/OsservioCoreTests"),
        .testTarget(name: "OsservioURLSessionTests", dependencies: ["OsservioCore", "OsservioStore", "OsservioURLSession"],
                    path: "native/ios/Tests/OsservioURLSessionTests"),
        .testTarget(name: "OsservioRuntimeTests", dependencies: ["OsservioCore", "OsservioStore", "OsservioRuntime"],
                    path: "native/ios/Tests/OsservioRuntimeTests"),
        .testTarget(name: "OsservioUITests", dependencies: ["OsservioCore", "OsservioUI"],
                    path: "native/ios/Tests/OsservioUITests"),
        .testTarget(name: "OsservioExportTests", dependencies: ["OsservioCore", "OsservioExport"],
                    path: "native/ios/Tests/OsservioExportTests")
    ],
    swiftLanguageVersions: [.v5]
)

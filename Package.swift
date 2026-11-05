// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "Osservio",
    defaultLocalization: "en",
    platforms: [.iOS(.v13), .macOS(.v10_15)],
    products: [
        .library(name: "OsservioCore", targets: ["OsservioCore"]),
        .library(name: "OsservioStore", targets: ["OsservioStore"]),
        .library(name: "OsservioUI", targets: ["OsservioUI"])
    ],
    targets: [
        .target(name: "OsservioCore", path: "native/ios/Sources/OsservioCore"),
        .target(name: "OsservioStore", dependencies: ["OsservioCore"], path: "native/ios/Sources/OsservioStore"),
        .target(name: "OsservioUI", dependencies: ["OsservioCore"], path: "native/ios/Sources/OsservioUI",
                resources: [.process("Resources")]),
        .testTarget(name: "OsservioCoreTests", dependencies: ["OsservioCore", "OsservioStore"], path: "native/ios/Tests/OsservioCoreTests")
    ],
    swiftLanguageVersions: [.v5]
)

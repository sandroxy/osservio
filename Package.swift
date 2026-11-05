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
        .library(name: "OsservioURLSession", targets: ["OsservioURLSession"])
    ],
    targets: [
        .target(name: "OsservioCore", path: "native/ios/Sources/OsservioCore"),
        .target(name: "OsservioStore", dependencies: ["OsservioCore"], path: "native/ios/Sources/OsservioStore"),
        .target(name: "OsservioUI", dependencies: ["OsservioCore"], path: "native/ios/Sources/OsservioUI",
                resources: [.process("Resources")]),
        .target(name: "OsservioURLSession", dependencies: ["OsservioCore"], path: "native/ios/Sources/OsservioURLSession"),
        .testTarget(name: "OsservioCoreTests", dependencies: ["OsservioCore", "OsservioStore"], path: "native/ios/Tests/OsservioCoreTests"),
        .testTarget(name: "OsservioURLSessionTests", dependencies: ["OsservioCore", "OsservioStore", "OsservioURLSession"],
                    path: "native/ios/Tests/OsservioURLSessionTests")
    ],
    swiftLanguageVersions: [.v5]
)

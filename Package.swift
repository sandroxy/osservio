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
    dependencies: [
        .package(url: "https://github.com/weichsel/ZIPFoundation.git", exact: "0.9.20")
    ],
    targets: [
        .target(name: "OsservioCrash", dependencies: ["OsservioCore", "OsservioCrashSupport", "OsservioCrashBackend"],
                path: "native/ios/Sources/OsservioCrash"),
        .target(name: "OsservioCrashSupport", path: "native/ios/Sources/OsservioCrashSupport",
                resources: [.copy("Resources/PrivacyInfo.xcprivacy")]),
        .target(name: "OsservioCrashBackend", dependencies: ["OsservioCrashSupport"],
                path: "native/ios/Vendor/PLCrashReporter",
                exclude: ["Source/PLCrashReport.proto", "Source/dwarf_opstream.hpp", "Source/dwarf_stack.hpp",
                          "Source/PLCrashAsyncDwarfCFAState.hpp", "Source/PLCrashAsyncDwarfCIE.hpp",
                          "Source/PLCrashAsyncDwarfEncoding.hpp", "Source/PLCrashAsyncDwarfExpression.hpp",
                          "Source/PLCrashAsyncDwarfFDE.hpp", "Source/PLCrashAsyncDwarfPrimitives.hpp",
                          "Source/PLCrashAsyncLinkedList.hpp"],
                sources: ["Source", "Dependencies/protobuf-c"],
                resources: [.copy("Resources/PrivacyInfo.xcprivacy")],
                publicHeadersPath: "include",
                cSettings: [.define("PLCR_PRIVATE"), .define("PLCF_RELEASE_BUILD"),
                            .define("PLCRASHREPORTER_PREFIX", to: "Osservio_"),
                            .define("PLCRASH_FEATURE_MACH_EXCEPTIONS", to: "0"),
                            .define("SWIFT_PACKAGE"), .headerSearchPath("Source"),
                            .headerSearchPath("Dependencies/protobuf-c")],
                linkerSettings: [.linkedFramework("Foundation"), .linkedLibrary("c++")]),
        .target(name: "OsservioCore", path: "native/ios/Sources/OsservioCore"),
        .target(name: "OsservioStore", dependencies: ["OsservioCore"], path: "native/ios/Sources/OsservioStore",
                linkerSettings: [.linkedLibrary("sqlite3")]),
        .target(name: "OsservioUI", dependencies: ["OsservioCore"], path: "native/ios/Sources/OsservioUI",
                resources: [.process("Resources")]),
        .target(name: "OsservioURLSession", dependencies: ["OsservioCore"], path: "native/ios/Sources/OsservioURLSession"),
        .target(name: "OsservioRuntime", dependencies: ["OsservioCore"], path: "native/ios/Sources/OsservioRuntime"),
        .target(name: "OsservioExport", dependencies: ["OsservioCore", .product(name: "ZIPFoundation", package: "ZIPFoundation")],
                path: "native/ios/Sources/OsservioExport"),
        .testTarget(name: "OsservioCoreTests", dependencies: ["OsservioCore", "OsservioStore"], path: "native/ios/Tests/OsservioCoreTests"),
        .testTarget(name: "OsservioURLSessionTests", dependencies: ["OsservioCore", "OsservioStore", "OsservioURLSession"],
                    path: "native/ios/Tests/OsservioURLSessionTests"),
        .testTarget(name: "OsservioRuntimeTests", dependencies: ["OsservioCore", "OsservioStore", "OsservioRuntime"],
                    path: "native/ios/Tests/OsservioRuntimeTests"),
        .testTarget(name: "OsservioUITests", dependencies: ["OsservioCore", "OsservioUI"],
                    path: "native/ios/Tests/OsservioUITests"),
        .testTarget(name: "OsservioExportTests", dependencies: ["OsservioCore", "OsservioExport"],
                    path: "native/ios/Tests/OsservioExportTests"),
        .testTarget(name: "OsservioCrashBackendTests", dependencies: ["OsservioCrashBackend", "OsservioCrashSupport"],
                    path: "native/ios/Tests/OsservioCrashBackendTests"),
        .testTarget(name: "OsservioCrashTests", dependencies: ["OsservioCore", "OsservioCrash", "OsservioCrashSupport"],
                    path: "native/ios/Tests/OsservioCrashTests")
    ],
    swiftLanguageVersions: [.v5]
)

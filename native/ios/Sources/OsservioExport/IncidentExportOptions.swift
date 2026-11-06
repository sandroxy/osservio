import Foundation
import OsservioCore

/// Bounds completed temporary exports. One additional in-flight ZIP may use up to maximumFileBytes.
public struct IncidentExportOptions: Sendable {
    public static let maximumFileBytes: Int64 = 17 * 1_048_576
    public let fileCount: Int
    public let cacheBytes: Int64
    public let maximumAgeMillis: Int64
    public init(fileCount: Int = 4, cacheBytes: Int64 = 32 * 1_048_576, maximumAgeMillis: Int64 = 86_400_000) throws {
        guard (1...16).contains(fileCount), (1_048_576...134_217_728).contains(cacheBytes),
              (60_000...604_800_000).contains(maximumAgeMillis) else { throw CaptureConfigurationError.invalidLimit }
        self.fileCount = fileCount; self.cacheBytes = cacheBytes; self.maximumAgeMillis = maximumAgeMillis
    }
}

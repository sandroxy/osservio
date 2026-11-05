import OsservioCore

public struct BreadcrumbPersistenceOptions: Sendable {
    public let recordsPerRun: Int
    public let textBytesPerRun: Int
    public let retainedRuns: Int
    public let maximumAgeMillis: Int64
    public let databaseByteLimit: Int
    public let pendingEventLimit: Int
    public let pendingTextByteLimit: Int
    public let flushIntervalMillis: Int

    public init(recordsPerRun: Int = 200, textBytesPerRun: Int = 65_536, retainedRuns: Int = 4,
                maximumAgeMillis: Int64 = 604_800_000, databaseByteLimit: Int = 4_194_304,
                pendingEventLimit: Int = 64, pendingTextByteLimit: Int = 131_072, flushIntervalMillis: Int = 1_000) throws {
        guard (1...2_000).contains(recordsPerRun), (64...1_048_576).contains(textBytesPerRun),
              (1...16).contains(retainedRuns), (60_000...2_592_000_000).contains(maximumAgeMillis),
              (1_048_576...67_108_864).contains(databaseByteLimit),
              (1...256).contains(pendingEventLimit), (64...1_048_576).contains(pendingTextByteLimit),
              (100...5_000).contains(flushIntervalMillis) else { throw CaptureConfigurationError.invalidLimit }
        self.recordsPerRun = recordsPerRun; self.textBytesPerRun = textBytesPerRun; self.retainedRuns = retainedRuns
        self.maximumAgeMillis = maximumAgeMillis; self.databaseByteLimit = databaseByteLimit
        self.pendingEventLimit = pendingEventLimit; self.pendingTextByteLimit = pendingTextByteLimit
        self.flushIntervalMillis = flushIntervalMillis
    }
}

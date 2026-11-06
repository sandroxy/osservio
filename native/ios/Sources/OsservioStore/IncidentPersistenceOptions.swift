import OsservioCore

public struct IncidentPersistenceOptions: Sendable {
    public let retainedIncidents: Int
    public let retainedDiagnostics: Int
    public let maximumAgeMillis: Int64
    public let textBytesPerIncident: Int
    public let databaseByteLimit: Int
    public let pendingTextByteLimit: Int

    public init(retainedIncidents: Int = 10, maximumAgeMillis: Int64 = 604_800_000,
                textBytesPerIncident: Int = 131_072, databaseByteLimit: Int = 16_777_216,
                pendingTextByteLimit: Int = 524_288, retainedDiagnostics: Int = 10) throws {
        guard (1...16).contains(retainedIncidents), (60_000...2_592_000_000).contains(maximumAgeMillis),
              (64...2_097_152).contains(textBytesPerIncident), (1_048_576...67_108_864).contains(databaseByteLimit),
              (64...4_194_304).contains(pendingTextByteLimit), (1...16).contains(retainedDiagnostics) else { throw CaptureConfigurationError.invalidLimit }
        self.retainedIncidents = retainedIncidents; self.maximumAgeMillis = maximumAgeMillis
        self.textBytesPerIncident = textBytesPerIncident; self.databaseByteLimit = databaseByteLimit
        self.pendingTextByteLimit = pendingTextByteLimit
        self.retainedDiagnostics = retainedDiagnostics
    }
}

import Foundation

/// A receipt for one bounded source batch. Already processed means matching report material,
/// not proof that two observations describe the same real-world failure.
public struct DiagnosticRecoveryReceipt: Sendable {
    public let source: SystemDiagnosticSource
    public let observedAtMillis: Int64
    public let savedCount: Int
    public let alreadyProcessedCount: Int
    public let ignoredExitCount: Int
    public let outsideWindowCount: Int
    public let capacityExcludedCount: Int
    public let sourceRejectedCount: Int
    public let sourceExcludedCount: Int
    public let sourceLimitReached: Bool

    public init(batch: SystemDiagnosticBatch, savedCount: Int, alreadyProcessedCount: Int,
                ignoredExitCount: Int, outsideWindowCount: Int, capacityExcludedCount: Int) throws {
        let counts = [savedCount, alreadyProcessedCount, ignoredExitCount, outsideWindowCount, capacityExcludedCount]
        guard counts.allSatisfy({ (0...32).contains($0) }), counts.reduce(0, +) == batch.diagnostics.count else {
            throw PersistenceError.invalidRecord
        }
        source = batch.source; observedAtMillis = batch.observedAtMillis
        self.savedCount = savedCount; self.alreadyProcessedCount = alreadyProcessedCount
        self.ignoredExitCount = ignoredExitCount; self.outsideWindowCount = outsideWindowCount
        self.capacityExcludedCount = capacityExcludedCount; sourceRejectedCount = batch.rejectedCount
        sourceExcludedCount = batch.excludedCount; sourceLimitReached = batch.sourceLimitReached
    }
}

/// Optional persistence port for processing sanitized system history. It installs no collector.
public protocol DiagnosticRecoveryArchive: Sendable {
    /// Commits selected records and their bounded acknowledgements together. A failure commits neither.
    /// Completion may run on any queue, including immediate admission rejection.
    func recoverDiagnostics(_ batch: SystemDiagnosticBatch,
        completion: @escaping @Sendable (Result<DiagnosticRecoveryReceipt, PersistenceError>) -> Void)
}

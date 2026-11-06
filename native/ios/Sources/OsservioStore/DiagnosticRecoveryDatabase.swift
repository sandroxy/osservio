import Foundation
import OsservioCore

/// Worker-owned acknowledgements of sanitized report material. The caller owns the transaction.
final class DiagnosticRecoveryDatabase {
    static let maximumAcknowledgements = 256
    static let schema = [
        """
        CREATE TABLE diagnostic_acknowledgements (ordinal INTEGER PRIMARY KEY,
            fingerprint TEXT NOT NULL UNIQUE, occurrences INTEGER NOT NULL, processed_at INTEGER NOT NULL)
        """
    ]
    private let connection: SQLiteConnection
    private let options: IncidentPersistenceOptions
    init(connection: SQLiteConnection, options: IncidentPersistenceOptions) {
        self.connection = connection; self.options = options
    }
    /// Validate even expired rows before deleting them; corruption must not be silently repaired.
    @discardableResult
    func prepare(now: Int64) throws -> [String: Int] {
        let difference = now.subtractingReportingOverflow(options.maximumAgeMillis)
        let cutoff = difference.overflow ? Int64.min : difference.partialValue
        var retained: [String: Int] = [:]
        try connection.statement("SELECT ordinal,fingerprint,occurrences,processed_at FROM diagnostic_acknowledgements LIMIT 257") { row in
            var count = 0
            while try row.step() {
                count += 1
                let fingerprint = try row.text(1, byteLimit: 64), occurrences = try row.integer(2)
                guard count <= Self.maximumAcknowledgements, try row.integer(0) > 0,
                      fingerprint.utf8.count == 64, fingerprint.utf8.allSatisfy({ (48...57).contains($0) || (97...102).contains($0) }),
                      (1...32).contains(occurrences) else { throw PersistenceError.invalidRecord }
                if try row.integer(3) >= cutoff { retained[fingerprint] = Int(occurrences) }
            }
        }
        if !difference.overflow {
            try connection.execute("DELETE FROM diagnostic_acknowledgements WHERE processed_at<?", [.integer(cutoff)])
        }
        return retained
    }
    func recover(_ batch: SystemDiagnosticBatch, now: Int64, save: (IncidentSnapshot) throws -> Void) throws -> DiagnosticRecoveryReceipt {
        let prior = try prepare(now: now)
        let difference = now.subtractingReportingOverflow(options.maximumAgeMillis)
        let cutoff = difference.overflow ? Int64.min : difference.partialValue
        var occurrences: [String: Int] = [:]
        var aliases: [String: Int] = [:]
        var candidates: [(index: Int, diagnostic: SystemDiagnostic)] = []
        var ignored = 0, outside = 0, capacity = 0, repeated = 0
        for (index, diagnostic) in batch.diagnostics.enumerated() {
            if diagnostic.kind == .otherExit { ignored += 1; continue }
            if diagnostic.intervalEndMillis < cutoff || diagnostic.intervalEndMillis > now { outside += 1; continue }
            if diagnostic.textByteCount > options.textBytesPerIncident { capacity += 1; continue }
            let fingerprint = DiagnosticFingerprint.make(diagnostic)
            let occurrence = (occurrences[fingerprint] ?? 0) + 1
            occurrences[fingerprint] = occurrence
            if occurrence <= (prior[fingerprint] ?? 0) { repeated += 1 }
            else { candidates.append((index, diagnostic)) }
            if let alias = DiagnosticFingerprint.summaryAlias(diagnostic) { aliases[alias, default: 0] += 1 }
        }
        candidates.sort {
            if $0.diagnostic.intervalEndMillis != $1.diagnostic.intervalEndMillis { return $0.diagnostic.intervalEndMillis > $1.diagnostic.intervalEndMillis }
            if $0.diagnostic.intervalStartMillis != $1.diagnostic.intervalStartMillis { return $0.diagnostic.intervalStartMillis > $1.diagnostic.intervalStartMillis }
            return $0.index < $1.index
        }
        let selected = candidates.prefix(options.retainedDiagnostics)
        capacity += candidates.count - selected.count
        // Insert oldest first so ordinal-based retention keeps the newest selected evidence.
        for candidate in selected.reversed() {
            try save(.captureDiagnostic(candidate.diagnostic, observedAtMillis: batch.observedAtMillis))
        }
        // Alias counts must not change candidate admission within the same batch.
        for (alias, count) in aliases { occurrences[alias, default: 0] += count }
        for fingerprint in occurrences.keys.sorted() {
            let count = occurrences[fingerprint]!
            if let previous = prior[fingerprint] {
                if count > previous {
                    try connection.execute("UPDATE diagnostic_acknowledgements SET occurrences=? WHERE fingerprint=?", [.integer(Int64(count)), .text(fingerprint)])
                }
            } else {
                try connection.execute("INSERT INTO diagnostic_acknowledgements(fingerprint,occurrences,processed_at) VALUES (?,?,?)",
                    [.text(fingerprint), .integer(Int64(count)), .integer(now)])
            }
        }
        try connection.execute("DELETE FROM diagnostic_acknowledgements WHERE ordinal IN (SELECT ordinal FROM diagnostic_acknowledgements ORDER BY ordinal DESC LIMIT -1 OFFSET ?)",
                               [.integer(Int64(Self.maximumAcknowledgements))])
        return try DiagnosticRecoveryReceipt(batch: batch, savedCount: selected.count, alreadyProcessedCount: repeated,
            ignoredExitCount: ignored, outsideWindowCount: outside, capacityExcludedCount: capacity)
    }
}

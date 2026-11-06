import Foundation

public enum SystemDiagnosticSource: String, Sendable { case androidExitInfo, metricKit, appleSignal }
public enum SystemDiagnosticKind: String, Sendable {
    case managedCrash, nativeCrash, anr, lowMemoryTermination, signalTermination, otherExit, crash, hang
}

/// Reviewed system facts, not raw crash reports. No exception message, user context, or stack text is accepted.
public struct SystemDiagnostic: Sendable {
    public enum TombstoneState: String, CaseIterable, Sendable {
        case notRequested, notApplicable, unsupported, unavailable, invalid, tooLarge, limitReached, available
    }
    public struct AndroidExit: Sendable {
        public let reasonCode: Int32
        public let status: Int32
        public let processID: Int32
        public let importance: Int32
        /// Last system sample, not memory measured at termination. Nil means unavailable.
        public let pssKiB: Int64?
        public let rssKiB: Int64?
        public let tombstoneState: TombstoneState
        public let tombstone: AndroidTombstone?
    }
    public enum MetadataKey: String, CaseIterable, Sendable { case appVersion, appBuild, osVersion, deviceModel }
    public struct Text: Sendable {
        public let value: String
        public let isTruncated: Bool
        /// Only public application/build/system metadata belongs here.
        public static func capture(publicValue: String) throws -> Text {
            guard !publicValue.isEmpty, let safe = BoundedText.prefix(publicValue, bytes: 256) else {
                throw PersistenceError.invalidRecord
            }
            return Text(value: safe.text, isTruncated: safe.truncated)
        }
        public static func restore(value: String, isTruncated: Bool) throws -> Text {
            let checked = try capture(publicValue: value)
            guard !checked.isTruncated else { throw PersistenceError.invalidRecord }
            return Text(value: value, isTruncated: isTruncated)
        }
    }
    public struct MetricKit: Sendable {
        public let metadata: [MetadataKey: Text]
        public let signal: Int32?
        public let exceptionType: Int64?
        /// Exact canonical decimal, including signed or unsigned 64-bit encodings. Never a floating-point value.
        public let exceptionCode: String?
        public let hangDurationMillis: Int64?
    }

    public let source: SystemDiagnosticSource
    public let kind: SystemDiagnosticKind
    /// Android supplies a point in time. MetricKit supplies a reporting interval, not an exact crash time.
    public let intervalStartMillis: Int64
    public let intervalEndMillis: Int64
    public let androidExit: AndroidExit?
    public let metricKit: MetricKit?
    public let appleSignal: AppleSignalCrash?
    public var textByteCount: Int {
        (metricKit?.metadata.values.reduce(0) { $0 + $1.value.utf8.count } ?? 0)
            + (metricKit?.exceptionCode?.utf8.count ?? 0) + (appleSignal?.textByteCount ?? 0)
            + (androidExit?.tombstone?.textByteCount ?? 0)
    }

    public static func androidExit(timestampMillis: Int64, reasonCode: Int32, status: Int32,
                                   processID: Int32, importance: Int32, pssKiB: Int64?, rssKiB: Int64?) throws -> Self {
        try androidExit(timestampMillis: timestampMillis, reasonCode: reasonCode, status: status, processID: processID,
            importance: importance, pssKiB: pssKiB, rssKiB: rssKiB,
            tombstoneState: reasonCode == 5 ? .notRequested : .notApplicable, tombstone: nil)
    }
    public static func androidExit(timestampMillis: Int64, reasonCode: Int32, status: Int32,
                                   processID: Int32, importance: Int32, pssKiB: Int64?, rssKiB: Int64?,
                                   tombstoneState: TombstoneState, tombstone: AndroidTombstone?) throws -> Self {
        guard timestampMillis >= 0, reasonCode >= 0, processID > 0, importance >= 0,
              pssKiB.map({ $0 > 0 }) ?? true, rssKiB.map({ $0 > 0 }) ?? true,
              (tombstoneState == .available) == (tombstone != nil),
              (reasonCode != 5) == (tombstoneState == .notApplicable) else { throw PersistenceError.invalidRecord }
        let kind: SystemDiagnosticKind
        switch reasonCode {
        case 2: kind = .signalTermination
        case 3: kind = .lowMemoryTermination
        case 4: kind = .managedCrash
        case 5: kind = .nativeCrash
        case 6: kind = .anr
        default: kind = .otherExit
        }
        return Self(source: .androidExitInfo, kind: kind, intervalStartMillis: timestampMillis, intervalEndMillis: timestampMillis,
                    androidExit: AndroidExit(reasonCode: reasonCode, status: status, processID: processID,
                        importance: importance, pssKiB: pssKiB, rssKiB: rssKiB,
                        tombstoneState: tombstoneState, tombstone: tombstone), metricKit: nil, appleSignal: nil)
    }
    public static func metricKit(intervalStartMillis: Int64, intervalEndMillis: Int64, kind: SystemDiagnosticKind,
                                 metadata: [MetadataKey: Text], signal: Int32? = nil, exceptionType: Int64? = nil,
                                 exceptionCode: String? = nil, hangDurationMillis: Int64? = nil) throws -> Self {
        guard intervalStartMillis <= intervalEndMillis, kind == .crash || kind == .hang,
              signal.map({ $0 > 0 }) ?? true, exceptionType.map({ $0 >= 0 }) ?? true,
              exceptionCode.map(validCode) ?? true,
              (kind == .crash && hangDurationMillis == nil) ||
                (kind == .hang && signal == nil && exceptionType == nil && exceptionCode == nil &&
                    (hangDurationMillis.map({ $0 >= 0 }) ?? false)) else { throw PersistenceError.invalidRecord }
        return Self(source: .metricKit, kind: kind, intervalStartMillis: intervalStartMillis, intervalEndMillis: intervalEndMillis,
                    androidExit: nil, metricKit: MetricKit(metadata: metadata, signal: signal, exceptionType: exceptionType,
                        exceptionCode: exceptionCode, hangDurationMillis: hangDurationMillis), appleSignal: nil)
    }
    public static func appleSignal(_ evidence: AppleSignalCrash) -> Self {
        Self(source: .appleSignal, kind: .nativeCrash, intervalStartMillis: evidence.timestampMillis,
             intervalEndMillis: evidence.timestampMillis, androidExit: nil, metricKit: nil, appleSignal: evidence)
    }
    private static func validCode(_ value: String) -> Bool {
        let bytes = Array(value.utf8.prefix(22))
        guard !bytes.isEmpty, bytes.count <= 20 else { return false }
        if let signed = Int64(value), String(signed) == value { return true }
        return UInt64(value).map { String($0) == value } ?? false
    }
}

/// One bounded inspection of sanitized diagnostic history. Empty success is not proof that no failure occurred.
public struct SystemDiagnosticBatch: Sendable {
    public let source: SystemDiagnosticSource
    public let observedAtMillis: Int64
    public let diagnostics: [SystemDiagnostic]
    public let rejectedCount: Int
    public let excludedCount: Int
    /// The source or traversal budget was reached; the number of unavailable older records is unknown.
    public let sourceLimitReached: Bool
    public init(source: SystemDiagnosticSource, observedAtMillis: Int64, diagnostics: [SystemDiagnostic],
                rejectedCount: Int = 0, excludedCount: Int = 0, sourceLimitReached: Bool = false) throws {
        guard diagnostics.count <= 32, (0...32).contains(rejectedCount), (0...32).contains(excludedCount),
              diagnostics.count + rejectedCount + excludedCount <= 32,
              diagnostics.allSatisfy({ $0.source == source }) else { throw PersistenceError.invalidRecord }
        self.source = source; self.observedAtMillis = observedAtMillis; self.diagnostics = diagnostics
        self.rejectedCount = rejectedCount; self.excludedCount = excludedCount; self.sourceLimitReached = sourceLimitReached
    }
}
public enum SystemDiagnosticError: Error, Sendable { case busy, unsupported, closed, unavailable }
public protocol SystemDiagnosticProvider: Sendable {
    /// Explicit asynchronous history read. Completion may run on any queue, including immediate rejection.
    func read(completion: @escaping @Sendable (Result<SystemDiagnosticBatch, SystemDiagnosticError>) -> Void)
}

import Foundation
import OsservioCounters

/// Fixed-domain accounting. Values count recorder operations, not unique events or durable writes.
public final class CaptureStatistics: @unchecked Sendable {
    public enum Source: Int, CaseIterable, Sendable { case logs, http, webSocket, breadcrumbs }
    public enum Outcome: Int, CaseIterable, Sendable {
        case accepted, inactive, busy, rateLimited, activeLimit, oversized, protectedCapacity
        case invalid, missing, stale, sinkFailure, sequenceExhausted
    }
    public struct Snapshot: Sendable {
        public let startedAtMillis: Int64
        /// Counters are read independently. True also signals saturation or skipped bookkeeping under contention.
        public let isIncomplete: Bool
        private let counts: [Int64]
        init(startedAtMillis: Int64, isIncomplete: Bool, counts: [Int64]) {
            self.startedAtMillis = startedAtMillis; self.isIncomplete = isIncomplete; self.counts = counts
        }
        public func count(_ source: Source, _ outcome: Outcome) -> Int64 { counts[source.rawValue * CaptureStatistics.outcomeCount + outcome.rawValue] }
    }
    private static let outcomeCount = Outcome.allCases.count
    private let counters: OpaquePointer?
    private let startedAtMillis: Int64
    init(startedAtMillis: Int64) { self.startedAtMillis = startedAtMillis; counters = OsservioCountersCreate() }
    deinit { OsservioCountersDestroy(counters) }
    public func snapshot() -> Snapshot {
        let counts = (0..<(Source.allCases.count * Self.outcomeCount)).map { OsservioCountersRead(counters, UInt32($0)) }
        return Snapshot(startedAtMillis: startedAtMillis, isIncomplete: OsservioCountersIncomplete(counters), counts: counts)
    }
    func record(_ source: Source, _ outcome: Outcome) {
        OsservioCountersIncrement(counters, UInt32(source.rawValue * CaptureStatistics.outcomeCount + outcome.rawValue))
    }

    @discardableResult
    func observe(_ result: LogCaptureResult) -> LogCaptureResult {
        let outcome: Outcome
        switch result {
        case .recorded: outcome = .accepted
        case .inactive: outcome = .inactive
        case .busy: outcome = .busy
        case .rateLimited: outcome = .rateLimited
        case .tooLarge: outcome = .oversized
        case .sinkFailure: outcome = .sinkFailure
        case .sequenceExhausted: outcome = .sequenceExhausted
        case .invalidCategory: outcome = .invalid
        case .invalidMessage: outcome = .invalid
        case .protectedCapacity: outcome = .protectedCapacity
        }
        record(.logs, outcome)
        return result
    }
    @discardableResult
    func observe(_ result: BreadcrumbRecordResult) -> BreadcrumbRecordResult {
        let outcome: Outcome
        switch result {
        case .recorded: outcome = .accepted
        case .inactive: outcome = .inactive
        case .busy: outcome = .busy
        case .rateLimited: outcome = .rateLimited
        case .tooLarge: outcome = .oversized
        case .sinkFailure: outcome = .sinkFailure
        case .sequenceExhausted: outcome = .sequenceExhausted
        case .invalidLabel: outcome = .invalid
        case .invalidMessage: outcome = .invalid
        }
        record(.breadcrumbs, outcome)
        return result
    }
    @discardableResult
    func observe(_ result: HTTPRecordResult) -> HTTPRecordResult {
        let outcome: Outcome
        switch result {
        case .recorded: outcome = .accepted
        case .inactive: outcome = .inactive
        case .busy: outcome = .busy
        case .rateLimited: outcome = .rateLimited
        case .tooLarge: outcome = .oversized
        case .sinkFailure: outcome = .sinkFailure
        case .sequenceExhausted: outcome = .sequenceExhausted
        case .stale: outcome = .stale
        case .activeLimit: outcome = .activeLimit
        case .invalid: outcome = .invalid
        case .missing: outcome = .missing
        }
        record(.http, outcome)
        return result
    }
    @discardableResult
    func observe(_ result: WebSocketRecordResult) -> WebSocketRecordResult {
        let outcome: Outcome
        switch result {
        case .recorded: outcome = .accepted
        case .inactive: outcome = .inactive
        case .busy: outcome = .busy
        case .rateLimited: outcome = .rateLimited
        case .tooLarge: outcome = .oversized
        case .sinkFailure: outcome = .sinkFailure
        case .sequenceExhausted: outcome = .sequenceExhausted
        case .stale: outcome = .stale
        case .invalid: outcome = .invalid
        case .connectionLimit: outcome = .activeLimit
        }
        record(.webSocket, outcome)
        return result
    }
}

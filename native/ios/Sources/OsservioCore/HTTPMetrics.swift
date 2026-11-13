import Foundation

/// Individual observed phase occurrences. Repeated phases are not merged into a fictitious single attempt.
public enum HTTPPhase: String, Sendable, CaseIterable {
    case dns, connect, tls, request, requestHeaders, requestBody, waiting, response, responseHeaders, responseBody
}
public enum HTTPCache: String, Sendable { case unknown, network, local, validated, serverPush }

public struct HTTPPhaseTiming: Sendable {
    public let phase: HTTPPhase
    public let startMillis: Int64
    public let endMillis: Int64?
    /// Offsets share the collector's call origin. A missing end is incomplete, never zero duration.
    public init(phase: HTTPPhase, startMillis: Int64, endMillis: Int64?) throws {
        guard startMillis >= 0, endMillis.map({ $0 >= startMillis }) ?? true else { throw PersistenceError.invalidRecord }
        self.phase = phase; self.startMillis = startMillis; self.endMillis = endMillis
    }
}

/// Bounded observations, not additive durations. Protocol/cache describe the final observed response.
public struct HTTPMetrics: Sendable {
    public let phases: [HTTPPhaseTiming]
    public let protocolName: String?
    public let cache: HTTPCache
    public let connectionReused: Bool?
    public let limited: Bool
    public var textByteCount: Int { protocolName?.utf8.count ?? 0 }
    public init(phases: [HTTPPhaseTiming], protocolName: String? = nil, cache: HTTPCache = .unknown,
                connectionReused: Bool? = nil, limited: Bool = false) throws {
        guard phases.count <= 32, protocolName.map({ !$0.isEmpty && $0.utf8.prefix(33).count <= 32 && $0.utf8.allSatisfy { (33...126).contains($0) } }) ?? true else {
            throw PersistenceError.invalidRecord
        }
        self.phases = phases; self.protocolName = protocolName; self.cache = cache
        self.connectionReused = connectionReused; self.limited = limited
    }
}

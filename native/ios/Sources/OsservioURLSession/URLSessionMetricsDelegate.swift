import Foundation
import OsservioCore

/// Intercepts only metrics. Every other implemented optional selector is forwarded to the host.
/// Task delegates require iOS 15; older completion-handler tasks keep their original behavior.
final class URLSessionMetricsDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private let target: (any URLSessionTaskDelegate)?
    private let lock = NSLock()
    private var value: HTTPMetrics?
    init(target: (any URLSessionTaskDelegate)?) { self.target = target }
    override func responds(to selector: Selector!) -> Bool { super.responds(to: selector) || target?.responds(to: selector) == true }
    override func forwardingTarget(for selector: Selector!) -> Any? {
        target?.responds(to: selector) == true ? target : super.forwardingTarget(for: selector)
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didFinishCollecting metrics: URLSessionTaskMetrics) {
        if lock.try() { value = Self.convert(metrics); lock.unlock() }
        target?.urlSession?(session, task: task, didFinishCollecting: metrics)
    }
    func snapshot() -> HTTPMetrics? {
        guard lock.try() else { return nil }; defer { lock.unlock() }; return value
    }
    struct PhaseCollector {
        let origin: Date
        var phases: [HTTPPhaseTiming] = []
        var limited = false
        func offset(_ date: Date) -> Int64? {
            let value = date.timeIntervalSince(origin) * 1_000
            guard value.isFinite, value >= 0, value < Double(Int64.max) else { return nil }
            return Int64(value)
        }
        mutating func add(_ phase: HTTPPhase, _ start: Date?, _ end: Date?) {
            guard let start else { return }
            guard phases.count < 32, let started = offset(start) else { limited = true; return }
            let ended = end.flatMap(offset)
            guard end == nil || ended.map({ $0 >= started }) == true else { limited = true; return }
            if let value = try? HTTPPhaseTiming(phase: phase, startMillis: started, endMillis: ended) { phases.append(value) }
        }
    }
    static func convert(_ metrics: URLSessionTaskMetrics) -> HTTPMetrics? {
        var collector = PhaseCollector(origin: metrics.taskInterval.start)
        // Do not retain task metrics, requests, certificates, addresses, or task objects.
        let transactions = metrics.transactionMetrics
        for transaction in transactions.prefix(32) {
            collector.add(.dns, transaction.domainLookupStartDate, transaction.domainLookupEndDate)
            collector.add(.connect, transaction.connectStartDate, transaction.connectEndDate)
            collector.add(.tls, transaction.secureConnectionStartDate, transaction.secureConnectionEndDate)
            collector.add(.request, transaction.requestStartDate, transaction.requestEndDate)
            if let start = transaction.requestEndDate, let end = transaction.responseStartDate, end >= start { collector.add(.waiting, start, end) }
            collector.add(.response, transaction.responseStartDate, transaction.responseEndDate)
        }
        collector.limited = collector.limited || transactions.count > 32
        guard let last = transactions.last else { return try? HTTPMetrics(phases: [], limited: collector.limited) }
        let cache: HTTPCache
        switch last.resourceFetchType {
        case .networkLoad: cache = .network
        case .localCache: cache = .local
        case .serverPush: cache = .serverPush
        default: cache = .unknown
        }
        let protocolName = last.networkProtocolName
        let reused: Bool? = cache == .network || cache == .serverPush ? last.isReusedConnection : nil
        if let result = try? HTTPMetrics(phases: collector.phases, protocolName: protocolName, cache: cache,
                                        connectionReused: reused, limited: collector.limited) { return result }
        return try? HTTPMetrics(phases: collector.phases, cache: cache, connectionReused: reused, limited: true)
    }
}

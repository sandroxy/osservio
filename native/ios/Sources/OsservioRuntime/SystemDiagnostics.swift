import Foundation
import OsservioCore

/// One explicit read of OS-owned, previously delivered diagnostic history. No handlers or subscriptions are installed.
public final class SystemDiagnostics: SystemDiagnosticProvider, @unchecked Sendable {
    private let queue = DispatchQueue(label: "io.github.sandroxy.osservio.system-diagnostics", qos: .utility)
    private let lock = NSLock()
    private let source: (@Sendable () throws -> SystemDiagnosticBatch)?
    private var pending = false
    private var closed = false
    public init() {
        #if canImport(UIKit) && canImport(MetricKit)
        if #available(iOS 14, *) { source = { try MetricDiagnosticReader.past() } }
        else { source = nil }
        #else
        source = nil
        #endif
    }
    init(source: (@Sendable () throws -> SystemDiagnosticBatch)?) { self.source = source }
    public func read(completion: @escaping @Sendable (Result<SystemDiagnosticBatch, SystemDiagnosticError>) -> Void) {
        lock.lock()
        if closed { lock.unlock(); completion(.failure(.closed)); return }
        guard let source else { lock.unlock(); completion(.failure(.unsupported)); return }
        if pending { lock.unlock(); completion(.failure(.busy)); return }
        pending = true
        queue.async { [self] in
            let result: Result<SystemDiagnosticBatch, SystemDiagnosticError>
            do { result = .success(try source()) } catch { result = .failure(.unavailable) }
            lock.lock(); pending = false; lock.unlock()
            completion(result)
        }
        lock.unlock()
    }
    /// Rejects new reads; an already admitted read completes. Does not remove OS history or stop another subscriber.
    public func close() { lock.lock(); closed = true; lock.unlock() }
}

#if canImport(UIKit) && canImport(MetricKit)
import MetricKit

/// Explicit ownership of new MetricKit deliveries. Only bounded sanitized crash/hang facts reach the callback.
@available(iOS 14, *)
@MainActor
public final class MetricDiagnosticObserver {
    private var subscriber: MetricDiagnosticSubscriber?
    public init() {}
    /// Repeated starts keep the current callback. Stop before replacing it. Delivery runs on MetricKit's background queue.
    public func start(delivery: @escaping @Sendable (Result<SystemDiagnosticBatch, SystemDiagnosticError>) -> Void) {
        guard subscriber == nil else { return }
        let value = MetricDiagnosticSubscriber(delivery: delivery)
        subscriber = value; MXMetricManager.shared.add(value)
    }
    public func stop() {
        guard let value = subscriber else { return }
        value.invalidate(); subscriber = nil; MXMetricManager.shared.remove(value)
    }
    deinit {
        if let value = subscriber { value.invalidate(); MXMetricManager.shared.remove(value) }
    }
}

@available(iOS 14, *)
final class MetricDiagnosticSubscriber: NSObject, MXMetricManagerSubscriber, @unchecked Sendable {
    private let lock = NSLock()
    private let delivery: @Sendable (Result<SystemDiagnosticBatch, SystemDiagnosticError>) -> Void
    private var active = true
    private var pending = false
    init(delivery: @escaping @Sendable (Result<SystemDiagnosticBatch, SystemDiagnosticError>) -> Void) { self.delivery = delivery }
    func invalidate() { lock.lock(); active = false; lock.unlock() }
    func didReceive(_ payloads: [MXDiagnosticPayload]) {
        lock.lock()
        guard active else { lock.unlock(); return }
        guard !pending else { lock.unlock(); delivery(.failure(.busy)); return }
        pending = true; lock.unlock()
        let result: Result<SystemDiagnosticBatch, SystemDiagnosticError>
        do { result = .success(try MetricDiagnosticReader.read(payloads)) }
        catch { result = .failure(.unavailable) }
        lock.lock(); pending = false; let deliver = active; lock.unlock()
        // A delivery admitted just before stop may finish; no raw payload is queued or retained by this subscriber.
        if deliver { delivery(result) }
    }
}

@available(iOS 14, *)
enum MetricDiagnosticReader {
    static func past() throws -> SystemDiagnosticBatch { try read(MXMetricManager.shared.pastDiagnosticPayloads) }
    static func read(_ payloads: [MXDiagnosticPayload]) throws -> SystemDiagnosticBatch {
        var records: [SystemDiagnostic] = []
        var rejected = 0
        var inspected = 0
        var limited = payloads.count > 4
        for payload in payloads.prefix(4) {
            let start = milliseconds(payload.timeStampBegin.timeIntervalSince1970)
            let end = milliseconds(payload.timeStampEnd.timeIntervalSince1970)
            // Do not serialize payloads, inspect signposts, or touch exception messages or call-stack trees.
            let crashes = payload.crashDiagnostics ?? []
            let hangs = payload.hangDiagnostics ?? []
            for index in 0..<min(crashes.count, 32 - inspected) {
                inspected += 1
                do {
                    guard let start, let end else { throw SystemDiagnosticError.unavailable }
                    let item = crashes[index]
                    let signal = try item.signal.map { number -> Int32 in
                        guard let value = Int32(try decimal(number)) else { throw SystemDiagnosticError.unavailable }; return value
                    }
                    let type = try item.exceptionType.map { number -> Int64 in
                        guard let value = Int64(try decimal(number)) else { throw SystemDiagnosticError.unavailable }; return value
                    }
                    records.append(try .metricKit(intervalStartMillis: start, intervalEndMillis: end, kind: .crash,
                        metadata: metadata(item), signal: signal, exceptionType: type, exceptionCode: try item.exceptionCode.map(decimal)))
                } catch { rejected += 1 }
            }
            for index in 0..<min(hangs.count, 32 - inspected) {
                inspected += 1
                do {
                    let item = hangs[index]
                    guard let start, let end,
                          let duration = milliseconds(item.hangDuration.converted(to: .seconds).value), duration >= 0 else {
                        throw SystemDiagnosticError.unavailable
                    }
                    records.append(try .metricKit(intervalStartMillis: start, intervalEndMillis: end, kind: .hang,
                        metadata: metadata(item), hangDurationMillis: duration))
                } catch { rejected += 1 }
            }
            if inspected == 32 { limited = true; break }
        }
        return try SystemDiagnosticBatch(source: .metricKit, observedAtMillis: SystemLogClock().wallTimeMillis(),
            diagnostics: records, rejectedCount: rejected, sourceLimitReached: limited)
    }
    private static func metadata(_ item: MXDiagnostic) throws -> [SystemDiagnostic.MetadataKey: SystemDiagnostic.Text] {
        let fields: [(SystemDiagnostic.MetadataKey, String)] = [
            (.appVersion, item.applicationVersion), (.appBuild, item.metaData.applicationBuildVersion),
            (.osVersion, item.metaData.osVersion), (.deviceModel, item.metaData.deviceType)
        ]
        var result: [SystemDiagnostic.MetadataKey: SystemDiagnostic.Text] = [:]
        for (key, value) in fields where !value.isEmpty { result[key] = try .capture(publicValue: value) }
        return result
    }
    private static func decimal(_ number: NSNumber) throws -> String {
        let encoding = String(cString: number.objCType)
        guard CFGetTypeID(number) != CFBooleanGetTypeID(), ["c", "C", "s", "S", "i", "I", "l", "L", "q", "Q"].contains(encoding) else {
            throw SystemDiagnosticError.unavailable
        }
        return number.stringValue
    }
    private static func milliseconds(_ seconds: Double) -> Int64? {
        guard seconds.isFinite else { return nil }
        return Int64(exactly: (seconds * 1_000).rounded(.towardZero))
    }
}
#endif

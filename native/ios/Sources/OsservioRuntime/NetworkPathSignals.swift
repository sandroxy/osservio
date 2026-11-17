import Foundation
import Network
import OsservioCore

/// Optional OS path observations. Does not probe servers, inspect addresses, or own UI visibility.
@MainActor
public final class NetworkPathSignals {
    private let recorder: BreadcrumbRecorder
    private var monitor: NWPathMonitor?
    private var lease: NetworkPathLease?
    public init(recorder: BreadcrumbRecorder) { self.recorder = recorder }
    /// Repeated starts are idempotent. Each restart owns a new monitor and callback lease.
    public func start() {
        guard monitor == nil else { return }
        let next = NWPathMonitor(), lease = NetworkPathLease(recorder: recorder)
        next.pathUpdateHandler = { path in
            let status: String
            switch path.status {
            case .satisfied: status = "available"
            case .unsatisfied: status = "unavailable"
            case .requiresConnection: status = "requiresConnection"
            @unknown default: status = "unknown"
            }
            let types: [(NWInterface.InterfaceType, String)] = [(.wifi, "wifi"), (.cellular, "cellular"), (.wiredEthernet, "ethernet"), (.loopback, "loopback"), (.other, "other")]
            let interfaces = types.filter { path.usesInterfaceType($0.0) }.map(\.1)
            lease.record("status=" + status + "; interfaces=" + (interfaces.isEmpty ? "none" : interfaces.joined(separator: ","))
                + "; expensive=" + String(path.isExpensive) + "; constrained=" + String(path.isConstrained))
        }
        self.monitor = next; self.lease = lease
        next.start(queue: DispatchQueue(label: "Osservio.NetworkPath", qos: .utility))
    }
    public func stop() {
        lease?.invalidate(); lease = nil
        monitor?.cancel(); monitor = nil
    }
    deinit { lease?.invalidate(); monitor?.cancel() }
}

/// A callback that already entered delivery may finish; old registrations cannot enter a replacement.
final class NetworkPathLease: @unchecked Sendable {
    private let delivery = NSRecursiveLock(), validity = NSLock()
    private var active = true, delivering = false
    private var session: String?, last: String?
    private let recorder: BreadcrumbRecorder
    init(recorder: BreadcrumbRecorder) { self.recorder = recorder }
    func invalidate() { validity.lock(); active = false; validity.unlock() }
    func record(_ description: String) {
        guard delivery.try() else { return }
        defer { delivery.unlock() }
        guard !delivering, validity.try() else { return }
        let admitted = active; validity.unlock()
        guard admitted, let status = recorder.captureController.status() else { return }
        guard status.mode != .off else { session = nil; last = nil; return }
        guard status.sessionID != session || description != last else { return }
        delivering = true; defer { delivering = false }
        if recorder.record(.networkPath, label: "ios.network.path", message: .publicText(description), retention: .restartRelevant) == .recorded {
            session = status.sessionID; last = description
        }
    }
}

import Foundation
import OsservioCore
#if canImport(UIKit)
import UIKit

/// Explicit notification ownership. No screen inspection, swizzling, or fatal-handler work.
@MainActor
public final class RuntimeSignals {
    private let recorder: BreadcrumbRecorder
    private let center: NotificationCenter
    private var observers: [NSObjectProtocol] = []
    private var lease: SignalLease?
    public init(recorder: BreadcrumbRecorder, notificationCenter: NotificationCenter = .default) {
        self.recorder = recorder; center = notificationCenter
    }
    public func start() {
        guard lease == nil else { return }
        let lease = SignalLease(recorder: recorder)
        self.lease = lease
        let signals: [(Notification.Name, BreadcrumbKind, String)] = [
            (UIApplication.didBecomeActiveNotification, .appActive, "ios.app.active"),
            (UIApplication.willResignActiveNotification, .appInactive, "ios.app.inactive"),
            (UIApplication.willEnterForegroundNotification, .appForeground, "ios.app.foreground"),
            (UIApplication.didEnterBackgroundNotification, .appBackground, "ios.app.background"),
            (UIApplication.didReceiveMemoryWarningNotification, .memoryWarning, "ios.memory.warning")
        ]
        for (name, kind, label) in signals {
            observers.append(center.addObserver(forName: name, object: nil, queue: nil) { _ in lease.record(kind, label: label) })
        }
        observers.append(center.addObserver(forName: ProcessInfo.thermalStateDidChangeNotification, object: nil, queue: nil) { _ in
            lease.record(.thermalState, label: "ios.thermal.state", value: Int64(ProcessInfo.processInfo.thermalState.rawValue))
        })
    }
    public func stop() {
        lease?.invalidate(); lease = nil
        for observer in observers { center.removeObserver(observer) }
        observers.removeAll()
    }
    deinit {
        lease?.invalidate()
        for observer in observers { center.removeObserver(observer) }
    }
}
#endif

/// Late callbacks retain their old lease and cannot record after it is invalidated.
/// An event already submitted to a host sink can finish; recursive control must not deadlock it.
final class SignalLease: @unchecked Sendable {
    private let lock = NSRecursiveLock()
    private var active = true
    private let recorder: BreadcrumbRecorder
    init(recorder: BreadcrumbRecorder) { self.recorder = recorder }
    func invalidate() { lock.lock(); active = false; lock.unlock() }
    func record(_ kind: BreadcrumbKind, label: String, value: Int64? = nil) {
        guard lock.try() else { return }
        defer { lock.unlock() }
        guard active else { return }
        recorder.record(kind, label: label, value: value, retention: .restartRelevant)
    }
}

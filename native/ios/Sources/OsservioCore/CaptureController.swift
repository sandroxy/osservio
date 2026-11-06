import Foundation

public enum CaptureConfigurationError: Error { case invalidLimit, invalidDuration, busy }

public enum CaptureMode: String, Sendable { case off, baseline, detailed }

public struct CaptureStatus: Sendable {
    public let mode: CaptureMode
    public let baselineEnabled: Bool
    public let sessionID: String?
    public let remainingMillis: UInt64
}

struct CaptureContext {
    let sessionID: String
    let sequence: Int64
    let monotonicMillis: UInt64
    let sessionStartMillis: UInt64
    let mode: CaptureMode
}

enum CaptureAccess<Value> {
    case value(Value), inactive, busy, sequenceExhausted
}

protocol CaptureSessionObserver: AnyObject {
    // Called under admission lock. Implementations only release buffers and submit bounded sanitized updates.
    func captureSessionDidEnd()
}

private final class WeakCaptureObserver {
    weak var value: (any CaptureSessionObserver)?
    init(_ value: any CaptureSessionObserver) { self.value = value }
}

/// Shared admission and lifecycle for native recorders. No collectors are installed automatically.
public final class CaptureController: @unchecked Sendable {
    private let lock = NSLock()
    let clock: any LogClock
    private var baselineEnabled: Bool
    private var deadline: UInt64 = 0
    private var sessionID = UUID().uuidString
    private var sequence: Int64 = 0
    private var sessionStartMillis: UInt64
    private var observers: [WeakCaptureObserver] = []
    private weak var permissionObserver: (any CapturePermissionObserver)?

    public init(baselineEnabled: Bool = false, clock: any LogClock = SystemLogClock()) {
        self.baselineEnabled = baselineEnabled
        self.clock = clock
        self.sessionStartMillis = clock.monotonicMillis()
    }

    /// Starts or replaces detailed capture. Expiry returns to the configured baseline or off state.
    public func startDetailed(durationMillis: UInt64 = 900_000) throws {
        guard (1...86_400_000).contains(durationMillis) else { throw CaptureConfigurationError.invalidDuration }
        guard lock.try() else { throw CaptureConfigurationError.busy }
        defer { lock.unlock() }
        let now = clock.monotonicMillis()
        guard now <= UInt64.max - durationMillis else { throw CaptureConfigurationError.invalidDuration }
        deadline = now + durationMillis
        replaceSession(at: now)
    }

    @discardableResult
    public func stopDetailed() -> Bool {
        guard lock.try() else { return false }
        defer { lock.unlock() }
        if deadline != 0 { deadline = 0; replaceSession(at: clock.monotonicMillis()) }
        return true
    }

    /// Changes fallback capture without interrupting an active detailed session.
    @discardableResult
    public func setBaselineEnabled(_ enabled: Bool) -> Bool {
        guard lock.try() else { return false }
        defer { lock.unlock() }
        let now = clock.monotonicMillis()
        expire(at: now)
        if baselineEnabled != enabled {
            baselineEnabled = enabled
            if deadline == 0 { replaceSession(at: now) }
            else { publishPermission() }
        }
        return true
    }

    /// Stops all new observations. Previously retained data is owned by stores.
    @discardableResult
    public func stopAll() -> Bool {
        guard lock.try() else { return false }
        defer { lock.unlock() }
        if baselineEnabled || deadline != 0 {
            baselineEnabled = false
            deadline = 0
            replaceSession(at: clock.monotonicMillis())
        }
        return true
    }

    /// Nil means contention. It never means that capture has stopped.
    public func status() -> CaptureStatus? { inspect { $0 } }

    // Native fatal mechanisms cannot call an injected Swift clock from a signal handler.
    package var usesSystemClock: Bool { clock is SystemLogClock }

    /// The caller retains the observer and controller for the entire binding lifetime.
    /// Initial delivery and subsequent changes are serialized with capture control; no initial-state gap exists.
    package func observePermission(_ observer: any CapturePermissionObserver) throws {
        guard lock.try() else { throw CapturePermissionBindingError.busy }
        defer { lock.unlock() }
        guard permissionObserver == nil else { throw CapturePermissionBindingError.alreadyBound }
        expire(at: clock.monotonicMillis())
        permissionObserver = observer
        publishPermission()
    }

    func register(_ observer: any CaptureSessionObserver) throws {
        guard lock.try() else { throw CaptureConfigurationError.busy }
        defer { lock.unlock() }
        observers.removeAll { $0.value == nil }
        guard observers.count < 16 else { throw CaptureConfigurationError.invalidLimit }
        observers.append(WeakCaptureObserver(observer))
    }

    func inspect<Value>(_ body: (CaptureStatus) -> Value) -> Value? {
        guard lock.try() else { return nil }
        defer { lock.unlock() }
        let now = clock.monotonicMillis()
        expire(at: now)
        let mode = currentMode
        return body(CaptureStatus(mode: mode, baselineEnabled: baselineEnabled, sessionID: mode == .off ? nil : sessionID,
                                  remainingMillis: deadline == 0 ? 0 : deadline - now))
    }

    // All recorder state associated with this controller is accessed under this lock.
    // The body must remain bounded and must not wait, perform I/O, or re-enter capture.
    func record<Value>(requiresDetailed: Bool,
                       _ body: (CaptureContext) -> Value) -> CaptureAccess<Value> {
        guard lock.try() else { return .busy }
        defer { lock.unlock() }
        let now = clock.monotonicMillis()
        expire(at: now)
        let mode = currentMode
        guard mode != .off, !requiresDetailed || mode == .detailed else { return .inactive }
        guard sequence < Int64.max else { return .sequenceExhausted }
        sequence += 1
        return .value(body(CaptureContext(sessionID: sessionID, sequence: sequence,
                                          monotonicMillis: now, sessionStartMillis: sessionStartMillis, mode: mode)))
    }

    private var currentMode: CaptureMode {
        deadline != 0 ? .detailed : baselineEnabled ? .baseline : .off
    }

    private func expire(at now: UInt64) {
        if deadline != 0 && now >= deadline { deadline = 0; replaceSession(at: now) }
    }

    private func replaceSession(at now: UInt64) {
        observers.removeAll { $0.value == nil }
        for observer in observers { observer.value?.captureSessionDidEnd() }
        sessionID = UUID().uuidString
        sequence = 0
        sessionStartMillis = now
        publishPermission()
    }

    private func publishPermission() {
        permissionObserver?.capturePermissionDidChange(CapturePermission(
            baselineEnabled: baselineEnabled, detailedDeadlineMillis: deadline))
    }
}

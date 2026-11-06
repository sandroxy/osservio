import Foundation
import OsservioCore
import OsservioCrashSupport

enum CrashCapturePermissionError: Error { case unsupportedClock, alreadyOwned, busy }

/// Normal-execution bridge only. The backend reads the C atomic and uptime clock directly when handling a signal.
final class CrashCapturePermission: CapturePermissionObserver {
    private static let ownership = NSLock()
    private static var claimed = false
    private let capture: CaptureController
    private var ownsPermission = false
    // Only the installation worker changes this flag. Policy callbacks only update C atomics.
    private var permanentlyDisabled = false

    init(captureController: CaptureController, initiallyArmed: Bool = true) throws {
        capture = captureController
        guard capture.usesSystemClock else { throw CrashCapturePermissionError.unsupportedClock }
        guard Self.ownership.try() else { throw CrashCapturePermissionError.busy }
        guard !Self.claimed else {
            Self.ownership.unlock()
            throw CrashCapturePermissionError.alreadyOwned
        }
        Self.claimed = true; ownsPermission = true
        Self.ownership.unlock()
        OsservioCrashCaptureSetArmed(initiallyArmed)
        try capture.observePermission(self)
    }

    func capturePermissionDidChange(_ permission: CapturePermission) {
        OsservioCrashCaptureUpdate(permission.baselineEnabled, permission.detailedDeadlineMillis)
    }

    func arm() {
        guard !permanentlyDisabled else { return }
        OsservioCrashCaptureSetArmed(true)
    }
    func disablePermanently() {
        permanentlyDisabled = true
        OsservioCrashCaptureSetArmed(false)
    }

    deinit {
        guard ownsPermission else { return }
        Self.ownership.lock()
        // A callback temporarily retains this observer, so no old update can outlive this revocation.
        OsservioCrashCaptureSetArmed(false)
        OsservioCrashCaptureUpdate(false, 0)
        Self.claimed = false
        Self.ownership.unlock()
    }
}

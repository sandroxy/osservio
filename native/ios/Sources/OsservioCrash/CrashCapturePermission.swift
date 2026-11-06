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

    init(captureController: CaptureController) throws {
        capture = captureController
        guard capture.usesSystemClock else { throw CrashCapturePermissionError.unsupportedClock }
        guard Self.ownership.try() else { throw CrashCapturePermissionError.busy }
        guard !Self.claimed else {
            Self.ownership.unlock()
            throw CrashCapturePermissionError.alreadyOwned
        }
        Self.claimed = true; ownsPermission = true
        Self.ownership.unlock()
        try capture.observePermission(self)
    }

    func capturePermissionDidChange(_ permission: CapturePermission) {
        OsservioCrashCaptureUpdate(permission.baselineEnabled, permission.detailedDeadlineMillis)
    }

    deinit {
        guard ownsPermission else { return }
        Self.ownership.lock()
        // A callback temporarily retains this observer, so no old update can outlive this revocation.
        OsservioCrashCaptureUpdate(false, 0)
        Self.claimed = false
        Self.ownership.unlock()
    }
}

import Foundation

/// A bounded native permission mirror. The deadline uses the controller's monotonic clock, not wall time.
package struct CapturePermission: Sendable {
    package let baselineEnabled: Bool
    package let detailedDeadlineMillis: UInt64
}

/// One process-level mechanism may mirror a controller. Called synchronously under its admission lock.
/// Implementations must only update bounded state: no I/O, waiting, callbacks, or capture reentry.
package protocol CapturePermissionObserver: AnyObject {
    func capturePermissionDidChange(_ permission: CapturePermission)
}

package enum CapturePermissionBindingError: Error { case busy, alreadyBound }

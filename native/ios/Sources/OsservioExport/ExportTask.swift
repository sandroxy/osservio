import Foundation
import OsservioCore

/// Cancellation and completion share one linearization point. No callback executes under this lock.
final class ExportTask: IncidentExportTask, @unchecked Sendable {
    private let lock = NSLock()
    private var cancelled = false
    private var finished = false
    func cancel() {
        lock.lock(); if !finished { cancelled = true }; lock.unlock()
    }
    func check() throws {
        lock.lock(); let value = cancelled; lock.unlock()
        if value { throw IncidentExportError.cancelled }
    }
    /// Returns whether cancellation won before completion became final.
    func finish() -> Bool {
        lock.lock(); defer { lock.unlock() }
        finished = true; return cancelled
    }
}

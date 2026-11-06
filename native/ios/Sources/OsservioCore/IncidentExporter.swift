import Foundation

public enum IncidentExportError: Error, Sendable {
    case busy, closed, cancelled, tooLarge, unavailable, storageFull, invalidRecord
}

/// A completed temporary ZIP file. The exporter owns cache retention; copy it to retain it independently.
public struct IncidentExportFile: Sendable {
    public let url: URL
    public let byteCount: Int64
    public init(url: URL, byteCount: Int64) throws {
        guard url.isFileURL, byteCount > 0 else { throw IncidentExportError.invalidRecord }
        self.url = url; self.byteCount = byteCount
    }
}

public protocol IncidentExportTask: Sendable {
    /// Best-effort cancellation before completion is finalized. A later cancel does not retract a delivered file.
    func cancel()
}

/// Explicit file preparation from frozen evidence, independent of archive storage and presentation.
public protocol IncidentExporter: Sendable {
    /// Completion runs once, on any queue, including immediate rejection. A rejected request returns no task.
    /// Success means a complete file is ready for an explicit share; it does not mean anything was sent.
    @discardableResult
    func prepare(_ snapshot: IncidentSnapshot,
                 completion: @escaping @Sendable (Result<IncidentExportFile, IncidentExportError>) -> Void) -> (any IncidentExportTask)?
}

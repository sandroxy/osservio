import Foundation
import OsservioCore

/// Bounded asynchronous ZIP preparation. Retain at host scope and provide a dedicated app-private cache directory.
public final class IncidentFileExporter: IncidentExporter, @unchecked Sendable {
    private let directory: URL
    private let options: IncidentExportOptions
    private let clock: any LogClock
    private let queue = DispatchQueue(label: "io.github.sandroxy.osservio.export", qos: .utility)
    private let lock = NSLock()
    private var accepting = true
    private var closed = false
    private var active: ExportTask?
    // Confined to the worker. No filesystem operation occurs in init, prepare admission, cancel, or close admission.
    private var cache: ExportCache?

    public init(directory: URL, options: IncidentExportOptions? = nil, clock: any LogClock = SystemLogClock()) throws {
        guard directory.isFileURL, directory.host.map({ $0.isEmpty || $0 == "localhost" }) ?? true,
              directory.path != "/", directory.path.utf8.prefix(4_097).count <= 4_096 else {
            throw CaptureConfigurationError.invalidLimit
        }
        self.directory = directory; self.options = try options ?? IncidentExportOptions(); self.clock = clock
    }
    @discardableResult
    public func prepare(_ snapshot: IncidentSnapshot,
                        completion: @escaping @Sendable (Result<IncidentExportFile, IncidentExportError>) -> Void) -> (any IncidentExportTask)? {
        guard snapshot.textByteCount <= IncidentDocuments.maximumTextBytes else { completion(.failure(.tooLarge)); return nil }
        guard lock.try() else { completion(.failure(.busy)); return nil }
        guard accepting, active == nil else {
            let error: IncidentExportError = accepting ? .busy : .closed
            lock.unlock(); completion(.failure(error)); return nil
        }
        let task = ExportTask(); active = task
        queue.async { [self] in
            var result = autoreleasepool { make(snapshot, task: task) }
            if task.finish() {
                if case .success(let file) = result { try? FileManager.default.removeItem(at: file.url) }
                result = .failure(.cancelled)
            }
            lock.lock(); active = nil; lock.unlock()
            completion(result)
        }
        lock.unlock(); return task
    }
    /// Cancels admitted work, then releases directory ownership. Completed files remain under cache retention.
    public func close(completion: @escaping @Sendable (Result<Void, IncidentExportError>) -> Void) {
        guard lock.try() else { completion(.failure(.busy)); return }
        if closed { lock.unlock(); completion(.success(())); return }
        guard accepting else { lock.unlock(); completion(.failure(.busy)); return }
        accepting = false; active?.cancel()
        queue.async { [self] in
            cache?.close(); cache = nil
            lock.lock(); closed = true; lock.unlock()
            completion(.success(()))
        }
        lock.unlock()
    }
    private func make(_ snapshot: IncidentSnapshot, task: ExportTask) -> Result<IncidentExportFile, IncidentExportError> {
        var partial: URL?
        defer { if let partial { try? FileManager.default.removeItem(at: partial) } }
        do {
            try task.check()
            if cache == nil { cache = try ExportCache(url: directory, options: options) }
            guard let cache else { throw IncidentExportError.unavailable }
            try cache.clean(now: clock.wallTimeMillis())
            try task.check()
            let documents = try IncidentDocuments.encode(snapshot)
            try task.check()
            let destination = cache.newPartial(); partial = destination
            let size = try IncidentZIP.write(documents, to: destination, task: task)
            try task.check()
            return .success(try cache.commit(destination, bytes: size, now: clock.wallTimeMillis()))
        } catch {
            if let error = error as? IncidentExportError { return .failure(error) }
            if let error = error as? IncidentDocumentError { return .failure(error == .tooLarge ? .tooLarge : .invalidRecord) }
            let failure = error as NSError
            if (failure.domain == NSPOSIXErrorDomain && [Int(ENOSPC), Int(EDQUOT)].contains(failure.code))
                || (failure.domain == NSCocoaErrorDomain && failure.code == NSFileWriteOutOfSpaceError) { return .failure(.storageFull) }
            return .failure(.unavailable)
        }
    }
    deinit {
        let cache = cache
        queue.async { cache?.close() }
    }
}

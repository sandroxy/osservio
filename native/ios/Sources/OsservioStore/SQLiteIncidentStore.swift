import Foundation
import OsservioCore

/// Explicit asynchronous snapshot storage. No live history or capture state is owned by this store.
public final class SQLiteIncidentStore: IncidentArchive, DiagnosticRecoveryArchive, @unchecked Sendable {
    private let options: IncidentPersistenceOptions
    private let clock: any LogClock
    private let queue = DispatchQueue(label: "io.github.sandroxy.osservio.incident-storage", qos: .utility)
    private let lock = NSLock()
    private var state = PersistenceState.opening
    private var accepting = true
    private var operations = 0
    private var writes = 0
    private var pendingBytes = 0
    private var committed: Int64 = 0
    private var discarded: Int64 = 0
    private var failures: Int64 = 0
    private var lastError: PersistenceError?
    // Confined to the serial worker. Jobs retain self until their callback has returned.
    private var database: IncidentDatabase?
    private var directory: EvidenceDirectory?

    /// Use a dedicated app-private, backup-excluded directory. File operations happen on the worker.
    public init(directory: URL, options: IncidentPersistenceOptions? = nil, clock: any LogClock = SystemLogClock()) throws {
        guard directory.isFileURL, directory.path != "/", Array(directory.path.utf8.prefix(4_097)).count <= 4_096 else {
            throw CaptureConfigurationError.invalidLimit
        }
        let options = try options ?? IncidentPersistenceOptions()
        self.options = options; self.clock = clock
        queue.async { [self] in
            do {
                let owner = try EvidenceDirectory(url: directory, contents: .incidents)
                self.directory = owner
                database = try IncidentDatabase(url: owner.databaseURL, options: options, now: clock.wallTimeMillis())
                lock.lock(); if accepting { state = .ready }; lock.unlock()
            } catch { fail(error as? PersistenceError ?? .unavailable) }
        }
    }
    public func persistenceStatus() -> PersistenceStatus? {
        guard lock.try() else { return nil }
        defer { lock.unlock() }
        return PersistenceStatus(state: state, queuedEvents: writes, queuedTextBytes: pendingBytes,
            committedEvents: committed, discardedEvents: discarded, failedBatches: failures, lastError: lastError)
    }
    public func save(_ snapshot: IncidentSnapshot, completion: @escaping @Sendable (Result<Void, PersistenceError>) -> Void) {
        let bytes = snapshot.textByteCount
        guard bytes <= options.textBytesPerIncident else { completion(.failure(.invalidRecord)); return }
        perform(completion, writeBytes: bytes) { [clock] in try $0.save(snapshot, now: clock.wallTimeMillis()) }
    }
    public func readIncidents(completion: @escaping @Sendable (Result<[IncidentSummary], PersistenceError>) -> Void) {
        perform(completion, reading: true) { try $0.summaries() }
    }
    public func recoverDiagnostics(_ batch: SystemDiagnosticBatch,
        completion: @escaping @Sendable (Result<DiagnosticRecoveryReceipt, PersistenceError>) -> Void) {
        let bytes = batch.diagnostics.reduce(0) { $0 + $1.textByteCount }
        perform(completion, writeBytes: bytes, writeCount: batch.diagnostics.count, reading: true,
                committedCount: { $0.savedCount }) { [clock] in try $0.recover(batch, now: clock.wallTimeMillis()) }
    }
    public func readIncident(id: String, completion: @escaping @Sendable (Result<IncidentSnapshot?, PersistenceError>) -> Void) {
        guard IncidentDatabase.validID(id) else { completion(.failure(.invalidRecord)); return }
        perform(completion, reading: true) { try $0.read(id: id) }
    }
    public func deleteIncident(id: String, completion: @escaping @Sendable (Result<Void, PersistenceError>) -> Void) {
        guard IncidentDatabase.validID(id) else { completion(.failure(.invalidRecord)); return }
        perform(completion) { try $0.delete(id: id) }
    }
    public func deleteAllIncidents(completion: @escaping @Sendable (Result<Void, PersistenceError>) -> Void) {
        perform(completion) { try $0.deleteAll() }
    }
    /// Finishes admitted operations, then releases storage ownership. Never blocks the caller.
    public func close(completion: @escaping @Sendable (Result<Void, PersistenceError>) -> Void) {
        guard lock.try() else { completion(.failure(.busy)); return }
        if state == .closed { lock.unlock(); completion(.success(())); return }
        guard accepting else { lock.unlock(); completion(.failure(.busy)); return }
        accepting = false; state = .closing
        queue.async { [self] in
            let usable = database != nil
            database?.close(); database = nil; directory?.close(); directory = nil
            lock.lock(); state = .closed; let error = usable ? nil : lastError; lock.unlock()
            completion(error.map { .failure($0) } ?? .success(()))
        }
        lock.unlock()
    }
    private func perform<Value: Sendable>(_ completion: @escaping @Sendable (Result<Value, PersistenceError>) -> Void,
        writeBytes: Int? = nil, writeCount: Int = 1, reading: Bool = false,
        committedCount: @escaping @Sendable (Value) -> Int = { _ in 1 },
        operation: @escaping @Sendable (IncidentDatabase) throws -> Value) {
        guard lock.try() else { completion(.failure(.busy)); return }
        guard accepting, operations < 4, (writeBytes ?? 0) <= options.pendingTextByteLimit - pendingBytes else {
            let error: PersistenceError = accepting ? .busy : .closed
            if writeBytes != nil { discarded = increment(discarded, by: writeCount) }
            lock.unlock(); completion(.failure(error)); return
        }
        operations += 1
        if let writeBytes { writes += writeCount; pendingBytes += writeBytes }
        // Enqueue under the admission lock so close cannot overtake an accepted operation.
        queue.async { [self] in
            let result: Result<Value, PersistenceError>
            if let database {
                do { result = .success(try operation(database)) }
                catch {
                    let error = error as? PersistenceError ?? .unavailable
                    if error == .corrupt || error == .unsupportedSchema || (reading && error == .invalidRecord) { fail(error) }
                    result = .failure(error)
                }
            } else {
                lock.lock(); let error = lastError ?? .unavailable; lock.unlock()
                result = .failure(error)
            }
            lock.lock()
            operations -= 1
            if let writeBytes {
                writes -= writeCount; pendingBytes -= writeBytes
                switch result {
                case .success(let value):
                    let count = committedCount(value)
                    committed = increment(committed, by: count); discarded = increment(discarded, by: writeCount - count); lastError = nil
                case .failure: discarded = increment(discarded, by: writeCount); failures = increment(failures)
                }
            }
            if case .failure(let error) = result { lastError = error }
            lock.unlock()
            // No locks or permits are held across host callbacks. Their failures are never retried.
            completion(result)
        }
        lock.unlock()
    }
    private func fail(_ error: PersistenceError) {
        lock.lock(); state = accepting ? .failed : .closing; lastError = error; lock.unlock()
        database?.close(); database = nil; directory?.close(); directory = nil
    }
    private func increment(_ value: Int64, by amount: Int = 1) -> Int64 {
        value > Int64.max - Int64(amount) ? Int64.max : value + Int64(amount)
    }
    deinit {
        let database = database; let directory = directory
        queue.async { database?.close(); directory?.close() }
    }
}

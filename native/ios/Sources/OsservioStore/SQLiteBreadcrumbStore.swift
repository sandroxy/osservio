import Foundation
import OsservioCore

/// Bounded live history and best-effort restart evidence. Initialization and all disk work are asynchronous.
public final class SQLiteBreadcrumbStore: BreadcrumbSink, BreadcrumbHistory, BreadcrumbArchive, @unchecked Sendable {
    public let currentRunID = UUID().uuidString
    private let memory: MemoryBreadcrumbStore
    private let options: BreadcrumbPersistenceOptions
    private let clock: any LogClock
    private let queue = DispatchQueue(label: "io.github.sandroxy.osservio.breadcrumb-storage", qos: .utility)
    private let lock = NSLock()
    private var state = PersistenceState.opening
    private var accepting = true
    private var pending: [BreadcrumbEvent] = []
    private var outstandingEvents = 0
    private var outstandingBytes = 0
    private var drainScheduled = false
    private var queryCount = 0
    private var committed: Int64 = 0
    private var discarded: Int64 = 0
    private var failedBatches: Int64 = 0
    private var lastError: PersistenceError?
    // Worker-confined resources. Pending jobs retain self, so deinit cannot race an active operation.
    private var database: BreadcrumbDatabase?
    private var directory: EvidenceDirectory?

    /// Supply a dedicated app-private directory. Existing group/world-readable directories are rejected.
    public init(directory: URL, options: BreadcrumbPersistenceOptions? = nil, clock: any LogClock = SystemLogClock()) throws {
        guard directory.isFileURL, directory.path != "/", Array(directory.path.utf8.prefix(4_097)).count <= 4_096 else {
            throw CaptureConfigurationError.invalidLimit
        }
        let options = try options ?? BreadcrumbPersistenceOptions()
        self.options = options; self.clock = clock
        memory = try MemoryBreadcrumbStore(recordLimit: options.recordsPerRun, textByteLimit: options.textBytesPerRun)
        queue.async { [self] in
            do {
                let owner = try EvidenceDirectory(url: directory)
                self.directory = owner
                database = try BreadcrumbDatabase(url: owner.databaseURL, options: options, runID: currentRunID,
                    platform: .ios, processID: Int64(ProcessInfo.processInfo.processIdentifier), startedAtMillis: clock.wallTimeMillis())
                lock.lock(); if accepting { state = .ready }; lock.unlock()
            } catch { fail(error as? PersistenceError ?? .unavailable) }
        }
    }
    public func append(_ event: BreadcrumbEvent) throws -> BreadcrumbWriteResult {
        guard lock.try() else { return .busy }
        defer { lock.unlock() }
        guard accepting else { throw PersistenceError.closed }
        let result = memory.append(event)
        guard case .stored = result, event.retention == .restartRelevant else { return result }
        guard state != .failed, outstandingEvents < options.pendingEventLimit,
              event.textByteCount <= options.pendingTextByteLimit - outstandingBytes else {
            discarded = saturatingAdd(discarded, 1); return .stored
        }
        pending.append(event); outstandingEvents += 1; outstandingBytes += event.textByteCount
        if !drainScheduled {
            drainScheduled = true
            queue.asyncAfter(deadline: .now() + .milliseconds(options.flushIntervalMillis)) { [weak self] in
                guard let self else { return }
                self.lock.lock(); self.drainScheduled = false; self.lock.unlock()
                self.drain()
            }
        }
        return .stored
    }
    public func snapshot() -> BreadcrumbSnapshot? { memory.snapshot() }
    /// Clears only live history. Persisted evidence has its own retention policy.
    public func clear() { memory.clear() }
    public func persistenceStatus() -> PersistenceStatus? {
        guard lock.try() else { return nil }
        defer { lock.unlock() }
        return PersistenceStatus(state: state, queuedEvents: outstandingEvents, queuedTextBytes: outstandingBytes,
            committedEvents: committed, discardedEvents: discarded, failedBatches: failedBatches, lastError: lastError)
    }
    public func readRuns(completion: @escaping @Sendable (Result<[RetainedRun], PersistenceError>) -> Void) {
        perform(completion) { try $0.runs() }
    }
    public func readRun(id: String, completion: @escaping @Sendable (Result<RetainedBreadcrumbs?, PersistenceError>) -> Void) {
        guard Array(id.utf8.prefix(37)).count == 36, UUID(uuidString: id) != nil else { completion(.failure(.invalidRecord)); return }
        perform(completion) { try $0.read(id: id) }
    }
    public func flush(completion: @escaping @Sendable (Result<Void, PersistenceError>) -> Void) {
        perform(completion, flush: true) { _ in () }
    }
    /// Stops admission and finishes admitted writes before releasing the database. Does not clear either history.
    public func close(completion: @escaping @Sendable (Result<Void, PersistenceError>) -> Void) {
        guard lock.try() else { completion(.failure(.busy)); return }
        if state == .closed { lock.unlock(); completion(.success(())); return }
        guard accepting else { lock.unlock(); completion(.failure(.busy)); return }
        accepting = false; state = .closing
        queue.async { [self] in
            drain()
            database?.close(); database = nil; directory?.close(); directory = nil
            lock.lock(); state = .closed; let error = lastError; lock.unlock()
            completion(error.map { .failure($0) } ?? .success(()))
        }
        lock.unlock()
    }
    private func perform<Value: Sendable>(_ completion: @escaping @Sendable (Result<Value, PersistenceError>) -> Void,
                                          flush: Bool = false, operation: @escaping @Sendable (BreadcrumbDatabase) throws -> Value) {
        guard lock.try() else { completion(.failure(.busy)); return }
        guard accepting, queryCount < 4 else {
            let error: PersistenceError = accepting ? .busy : .closed
            lock.unlock(); completion(.failure(error)); return
        }
        queryCount += 1
        queue.async { [self] in
            if flush { drain() }
            let result: Result<Value, PersistenceError>
            if let database {
                do { result = .success(try operation(database)) }
                catch {
                    let failure = error as? PersistenceError ?? .unavailable
                    if failure == .corrupt || failure == .invalidRecord || failure == .unsupportedSchema { fail(failure) }
                    result = .failure(failure)
                }
            } else {
                lock.lock(); let error = lastError ?? .unavailable; lock.unlock()
                result = .failure(error)
            }
            lock.lock(); queryCount -= 1; lock.unlock()
            // Release the query permit before invoking host code so callback reentry is safe.
            completion(result)
        }
        lock.unlock()
    }
    private func drain() {
        guard let database else { return }
        lock.lock()
        let batch = pending; pending.removeAll(keepingCapacity: true)
        lock.unlock()
        guard !batch.isEmpty else { return }
        do {
            try database.write(batch, now: clock.wallTimeMillis())
            let bytes = batch.reduce(0) { $0 + $1.textByteCount }
            lock.lock()
            outstandingEvents -= batch.count; outstandingBytes -= bytes
            committed = saturatingAdd(committed, Int64(batch.count))
            lock.unlock()
        } catch {
            lock.lock(); failedBatches = saturatingAdd(failedBatches, 1); lock.unlock()
            fail(error as? PersistenceError ?? .unavailable)
        }
    }
    private func fail(_ error: PersistenceError) {
        lock.lock()
        state = accepting ? .failed : .closing; lastError = error
        discarded = saturatingAdd(discarded, Int64(outstandingEvents))
        pending.removeAll(); outstandingEvents = 0; outstandingBytes = 0
        lock.unlock()
        database?.close(); database = nil; directory?.close(); directory = nil
    }
    private func saturatingAdd(_ value: Int64, _ increment: Int64) -> Int64 { value > Int64.max - increment ? Int64.max : value + increment }
    deinit {
        let database = database; let directory = directory
        queue.async { database?.close(); directory?.close() }
    }
}

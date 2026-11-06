import Foundation

public enum DiagnosticRecoveryError: Error, Sendable {
    case inactive, captureChanged, busy, closed
    case source(SystemDiagnosticError)
    case persistence(PersistenceError)
}

/// Capture-gated orchestration of one source read or delivered batch. Owns no source, archive, or timer lifecycle.
public final class SystemDiagnosticRecovery: @unchecked Sendable {
    public typealias Completion = @Sendable (Result<DiagnosticRecoveryReceipt, DiagnosticRecoveryError>) -> Void
    private final class Job: @unchecked Sendable {
        enum Phase { case reading, saving }
        let sessionID: String
        let completion: Completion
        var phase = Phase.reading
        init(sessionID: String, completion: @escaping Completion) { self.sessionID = sessionID; self.completion = completion }
    }
    private let capture: CaptureController
    private let archive: any DiagnosticRecoveryArchive
    private let lock = NSLock()
    private var pending: Job?
    private var closed = false

    public init(captureController: CaptureController, archive: any DiagnosticRecoveryArchive) {
        capture = captureController; self.archive = archive
    }
    /// One history read at a time. A capture-session change before persistence rejects the old result.
    public func recover(from provider: any SystemDiagnosticProvider, completion: @escaping Completion) {
        switch begin(completion) {
        case .failure(let error): completion(.failure(error))
        case .success(let job):
            provider.read { [self] result in
                switch result {
                case .success(let batch): persist(batch, job: job)
                case .failure(let error): finish(job, phase: .reading, result: .failure(.source(error)))
                }
            }
        }
    }
    /// Processes a sanitized source delivery only while baseline or detailed capture is active.
    public func recover(_ batch: SystemDiagnosticBatch, completion: @escaping Completion) {
        switch begin(completion) {
        case .failure(let error): completion(.failure(error))
        case .success(let job): persist(batch, job: job)
        }
    }
    /// Rejects future work and invalidates a pending read. An admitted persistence batch finishes normally.
    /// The provider and archive remain owned by the caller. Completion never runs while a lock is held.
    public func close() {
        lock.lock(); closed = true
        let cancelled = pending?.phase == .reading ? pending : nil
        if cancelled != nil { pending = nil }
        lock.unlock()
        cancelled?.completion(.failure(.closed))
    }
    private func begin(_ completion: @escaping Completion) -> Result<Job, DiagnosticRecoveryError> {
        guard lock.try() else { return .failure(.busy) }
        defer { lock.unlock() }
        guard !closed else { return .failure(.closed) }
        guard pending == nil else { return .failure(.busy) }
        guard let status = capture.status() else { return .failure(.busy) }
        guard let sessionID = status.sessionID else { return .failure(.inactive) }
        let job = Job(sessionID: sessionID, completion: completion); pending = job
        return .success(job)
    }
    private func persist(_ batch: SystemDiagnosticBatch, job: Job) {
        lock.lock()
        guard pending === job, job.phase == .reading else { lock.unlock(); return }
        let error: DiagnosticRecoveryError?
        if let status = capture.status() {
            if status.sessionID == nil { error = .inactive }
            else { error = status.sessionID == job.sessionID ? nil : .captureChanged }
        } else { error = .busy }
        if let error {
            pending = nil; lock.unlock(); job.completion(.failure(error)); return
        }
        // Admission is linearized at the capture check. Stop may follow; this accepted batch may finish.
        job.phase = .saving; lock.unlock()
        archive.recoverDiagnostics(batch) { [self] result in
            finish(job, phase: .saving, result: result.mapError(DiagnosticRecoveryError.persistence))
        }
    }
    private func finish(_ job: Job, phase: Job.Phase, result: Result<DiagnosticRecoveryReceipt, DiagnosticRecoveryError>) {
        lock.lock()
        guard pending === job, job.phase == phase else { lock.unlock(); return }
        pending = nil; lock.unlock()
        job.completion(result)
    }
}

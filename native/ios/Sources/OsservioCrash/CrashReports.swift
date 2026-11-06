import Foundation
import OsservioCore

/// Prior-process pending evidence, independently of whether a recorder can be installed now.
/// Opening validates a dedicated private directory and applies its age limit; reads never delete files.
public final class CrashReports: SystemDiagnosticProvider, @unchecked Sendable {
    public typealias OpenCompletion = @Sendable (Result<CrashReports, PersistenceError>) -> Void
    private static let opening = NSLock()
    private static var openingPending = false
    private static let preparation = DispatchQueue(label: "io.github.sandroxy.osservio.crash.open", qos: .utility)
    // The installation manager shares this queue so slot selection cannot race a read.
    let worker = DispatchQueue(label: "io.github.sandroxy.osservio.crash.files", qos: .utility)
    let directory: CrashReportDirectory
    private let admission = NSLock()
    private var reading = false
    private init(_ directory: CrashReportDirectory) { self.directory = directory }

    /// One open may be pending process-wide. Completion may run immediately for rejection.
    /// Releasing this handle releases its lease unless a recorder installation has pinned it.
    public static func open(directory: URL, completion: @escaping OpenCompletion) {
        guard opening.try() else { completion(.failure(.busy)); return }
        guard !openingPending else { opening.unlock(); completion(.failure(.busy)); return }
        openingPending = true; opening.unlock()
        preparation.async {
            let result: Result<CrashReports, PersistenceError>
            do { result = .success(CrashReports(try CrashReportDirectory(directory: directory, nowMillis: SystemLogClock().wallTimeMillis()))) }
            catch let error as PersistenceError { result = .failure(error) }
            catch { result = .failure(.unavailable) }
            opening.lock(); openingPending = false; opening.unlock()
            completion(result)
        }
    }

    /// Reads previous-process slots without changing capture or attaching current context.
    /// Use SystemDiagnosticRecovery when persisting through capture-gated admission.
    public func read(completion: @escaping @Sendable (Result<SystemDiagnosticBatch, SystemDiagnosticError>) -> Void) {
        guard admission.try() else { completion(.failure(.busy)); return }
        guard !reading else { admission.unlock(); completion(.failure(.busy)); return }
        reading = true; admission.unlock()
        worker.async { [self] in
            let result: Result<SystemDiagnosticBatch, SystemDiagnosticError>
            do { result = .success(try directory.read(observedAtMillis: SystemLogClock().wallTimeMillis())) }
            catch { result = .failure(.unavailable) }
            admission.lock(); reading = false; admission.unlock()
            completion(result)
        }
    }
}

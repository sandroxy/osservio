import Foundation
import OsservioCore
import OsservioCrashBackend
import OsservioCrashSupport

public enum CrashHandlerConflict: Error, Sendable {
    case signalHandler, exceptionHandler, machHandler, debuggerAttached, inspectionFailed
}

public enum CrashRecorderError: Error, Sendable {
    case busy, alreadyInstalled, restartRequired, unsupportedClock, captureControllerAlreadyBound, preparationFailed
    case handlersUnavailable(CrashHandlerConflict)
    case storage(PersistenceError)
    /// A backend installation attempt failed. Its process-lifetime state remains pinned and disarmed.
    case backendFailed
}

/// Explicit, process-lifetime BSD signal recording. The host must own crash handlers exclusively.
/// CaptureController controls recording; hiding the inspector does not uninstall handlers.
public enum CrashRecorder {
    public typealias InstallationCompletion = @Sendable (Result<Void, CrashRecorderError>) -> Void
    private final class Lifetime {
        let reports: CrashReports
        let permission: CrashCapturePermission
        let backend: Osservio_PLCrashReporter
        init(reports: CrashReports, permission: CrashCapturePermission, backend: Osservio_PLCrashReporter) {
            self.reports = reports; self.permission = permission; self.backend = backend
        }
    }
    private static let ownership = NSLock()
    private static var installing = false
    private static var installed = false
    // Set before entering the backend: even a partial failure must retain the
    // directory lease, policy binding and handler context until process exit.
    private static var pinned: Lifetime?

    /// Installs using an already-open report source, on its bounded worker.
    /// Rejection may complete immediately. The source remains readable when preflight rejects installation.
    /// Installation is explicit even when capture is off; only file recording follows capture permission.
    public static func install(reports: CrashReports, captureController: CaptureController, completion: @escaping InstallationCompletion) {
        guard ownership.try() else { completion(.failure(.busy)); return }
        let rejection: CrashRecorderError?
        if installing { rejection = .busy }
        else if installed { rejection = .alreadyInstalled }
        else if pinned != nil { rejection = .restartRequired }
        else { rejection = nil; installing = true }
        ownership.unlock()
        if let rejection { completion(.failure(rejection)); return }
        reports.worker.async {
            let result: Result<Void, CrashRecorderError>
            do {
                let prepared = try prepare(reports: reports, capture: captureController)
                ownership.lock(); pinned = prepared; ownership.unlock()
                do {
                    try prepared.backend.enableAndReturnError()
                    prepared.permission.arm()
                    result = .success(())
                } catch {
                    prepared.permission.disablePermanently()
                    result = .failure(.backendFailed)
                }
            } catch let error as CrashRecorderError { result = .failure(error) }
            catch { result = .failure(.storage(.unavailable)) }
            ownership.lock()
            installing = false
            if case .success = result { installed = true }
            ownership.unlock()
            completion(result)
        }
    }

    private static func prepare(reports: CrashReports, capture: CaptureController) throws -> Lifetime {
        guard capture.usesSystemClock else { throw CrashRecorderError.unsupportedClock }
        switch OsservioCrashInspectHandlers() {
        case OsservioCrashHandlersAvailable: break
        case OsservioCrashSignalHandlerPresent: throw CrashRecorderError.handlersUnavailable(.signalHandler)
        case OsservioCrashExceptionHandlerPresent: throw CrashRecorderError.handlersUnavailable(.exceptionHandler)
        case OsservioCrashMachHandlerPresent: throw CrashRecorderError.handlersUnavailable(.machHandler)
        case OsservioCrashDebuggerPresent: throw CrashRecorderError.handlersUnavailable(.debuggerAttached)
        default: throw CrashRecorderError.handlersUnavailable(.inspectionFailed)
        }
        let permission: CrashCapturePermission
        do { permission = try CrashCapturePermission(captureController: capture, initiallyArmed: false) }
        catch CrashCapturePermissionError.unsupportedClock { throw CrashRecorderError.unsupportedClock }
        catch CapturePermissionBindingError.alreadyBound { throw CrashRecorderError.captureControllerAlreadyBound }
        catch { throw CrashRecorderError.busy }
        let active: URL
        do { active = try reports.directory.reserveForRecording() }
        catch let error as PersistenceError { throw CrashRecorderError.storage(error) }
        catch { throw CrashRecorderError.storage(.unavailable) }
        let configuration = Osservio_PLCrashReporterConfig(signalHandlerType: .BSD, symbolicationStrategy: [],
            shouldRegisterUncaughtExceptionHandler: false, basePath: active.path,
            maxReportBytes: UInt(CrashReportDirectory.maximumReportBytes))
        guard let backend = Osservio_PLCrashReporter(applicationIdentifier: "osservio", appVersion: metadata("CFBundleVersion"),
            appMarketingVersion: metadata("CFBundleShortVersionString"), configuration: configuration),
              backend.crashReportPath() == active.appendingPathComponent(CrashReportDirectory.reportName).path else {
            throw CrashRecorderError.preparationFailed
        }
        return Lifetime(reports: reports, permission: permission, backend: backend)
    }

    private static func metadata(_ key: String) -> String {
        guard let value = Bundle.main.object(forInfoDictionaryKey: key) as? String,
              value.utf8.prefix(257).count <= 256, !value.utf8.contains(0) else { return "" }
        return value
    }
}

import Darwin
import Foundation
import OsservioCore
import OsservioCrash
import OsservioStore
import OsservioCrashSupport

@_silgen_name("OsservioProbeFailSecondHandler") private func failSecondHandler()

private struct FixtureFailure: Error { let message: String }
private func require(_ value: Bool, _ message: String) throws {
    if !value { throw FixtureFailure(message: message) }
}
private func open(_ directory: URL) async throws -> CrashReports {
    try await withCheckedContinuation { continuation in
        CrashReports.open(directory: directory) { continuation.resume(returning: $0) }
    }.get()
}
private func read(_ reports: CrashReports) async throws -> SystemDiagnosticBatch {
    try await withCheckedContinuation { continuation in reports.read { continuation.resume(returning: $0) } }.get()
}
private func install(_ reports: CrashReports, _ capture: CaptureController) async -> Result<Void, CrashRecorderError> {
    await withCheckedContinuation { continuation in
        CrashRecorder.install(reports: reports, captureController: capture) { continuation.resume(returning: $0) }
    }
}
private func emit(_ value: [String: Any]) throws {
    let bytes = try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])
    FileHandle.standardOutput.write(bytes); FileHandle.standardOutput.write(Data([10]))
}

@main private enum RecorderProbe {
    static func main() async {
        do { try await exercise() }
        catch {
            FileHandle.standardError.write(Data("Recorder fixture failed: \(error)\n".utf8))
            exit(70)
        }
    }
    static func exercise() async throws {
        try require(CommandLine.arguments.count == 3, "expected mode and fixture directory")
        let mode = CommandLine.arguments[1], root = URL(fileURLWithPath: CommandLine.arguments[2], isDirectory: true)
        let pending = root.appendingPathComponent("pending", isDirectory: true)
        var reports: CrashReports? = try await open(pending)
        let capture = CaptureController(baselineEnabled: mode != "default-off")
        if mode == "recover" || mode == "recover-again" || mode == "conflict-read" {
            guard let reports else { throw FixtureFailure(message: "missing source") }
            if mode == "conflict-read" {
                signal(SIGABRT, SIG_IGN)
                let result = await install(reports, capture)
                guard case .failure(.handlersUnavailable(.signalHandler)) = result else {
                    throw FixtureFailure(message: "conflict was not rejected: \(result)")
                }
                try require(OsservioCrashInspectHandlers() == OsservioCrashSignalHandlerPresent, "host handler changed")
                let batch = try await read(reports)
                try require(batch.diagnostics.count == 4 && batch.excludedCount == 0, "conflict evicted or hid prior files")
                try emit(["reports": batch.diagnostics.count, "conflictPreserved": true])
                return
            }
            let batch = try await read(reports)
            try require(batch.diagnostics.count == 1 && batch.rejectedCount == 0, "prior fatal file unavailable")
            guard let original = batch.diagnostics.first?.appleSignal else { throw FixtureFailure(message: "missing signal facts") }
            let archive = try SQLiteIncidentStore(directory: root.appendingPathComponent("archive", isDirectory: true))
            let recovery = SystemDiagnosticRecovery(captureController: capture, archive: archive)
            try require(capture.stopAll(), "capture contention")
            let off: Result<DiagnosticRecoveryReceipt, DiagnosticRecoveryError> = await withCheckedContinuation { continuation in
                recovery.recover(from: reports) { continuation.resume(returning: $0) }
            }
            guard case .failure(.inactive) = off else { throw FixtureFailure(message: "off recovery admitted") }
            try require(capture.setBaselineEnabled(true), "capture contention")
            let receipt = try await withCheckedContinuation { continuation in
                recovery.recover(from: reports) { continuation.resume(returning: $0) }
            }.get()
            try require(receipt.savedCount == (mode == "recover" ? 1 : 0) &&
                receipt.alreadyProcessedCount == (mode == "recover" ? 0 : 1), "recovery acknowledgement changed")
            let summaries = try await withCheckedContinuation { continuation in
                archive.readIncidents { continuation.resume(returning: $0) }
            }.get()
            try require(summaries.count == 1, "wrong saved incident count")
            let saved = try await withCheckedContinuation { continuation in
                archive.readIncident(id: summaries[0].id) { continuation.resume(returning: $0) }
            }.get()
            guard let restored = saved?.systemDiagnostic?.appleSignal else { throw FixtureFailure(message: "saved crash missing") }
            try require(restored.reportID == original.reportID && restored.frames == original.frames &&
                restored.timestampMillis == original.timestampMillis, "archive changed fatal facts")
            recovery.close()
            try await withCheckedContinuation { continuation in archive.close { continuation.resume(returning: $0) } }.get()
            try emit(["reportID": restored.reportID, "frames": restored.frames.count, "images": restored.images.count,
                      "saved": receipt.savedCount, "alreadyProcessed": receipt.alreadyProcessedCount])
            return
        }
        if mode == "detail" || mode == "expired" || mode == "expired-before-install" {
            try require(capture.stopAll(), "capture contention")
        }
        if mode == "expired-before-install" {
            try capture.startDetailed(durationMillis: 1)
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        if mode == "partial-failure" { failSecondHandler() }
        let installed = await install(reports!, capture)
        if mode == "partial-failure" {
            guard case .failure(.backendFailed) = installed else { throw FixtureFailure(message: "partial failure was not surfaced: \(installed)") }
            try require(!OsservioCrashCaptureAllowsRecording(), "failed recorder armed")
            try require(capture.stopAll() && capture.setBaselineEnabled(true), "capture contention")
            try capture.startDetailed()
            try require(!OsservioCrashCaptureAllowsRecording(), "Core update armed failed recorder")
            guard case .failure(.restartRequired) = await install(reports!, capture) else {
                throw FixtureFailure(message: "partial failure permitted another installation")
            }
        } else {
            try installed.get()
            guard case .failure(.alreadyInstalled) = await install(reports!, capture) else {
                throw FixtureFailure(message: "second recorder installed")
            }
            switch mode {
            case "baseline", "default-off", "expired-before-install": break
            case "off": try require(capture.stopAll(), "capture contention")
            case "detail": try capture.startDetailed(durationMillis: 10_000)
            case "expired", "baseline-after-expiry":
                try capture.startDetailed(durationMillis: 1)
                try await Task.sleep(nanoseconds: 10_000_000)
            default: throw FixtureFailure(message: "unknown fixture mode")
            }
        }
        weak var retained = reports
        reports = nil
        try require(retained != nil, "installed recorder released its source")
        retained = nil
        do { _ = try await open(pending); throw FixtureFailure(message: "installed recorder lost directory lease") }
        catch let error as PersistenceError { try require(error == .busy, "wrong lease failure") }
        try emit(["mode": mode, "armed": OsservioCrashCaptureAllowsRecording(), "sourcePinned": true])
        raise(SIGABRT)
        throw FixtureFailure(message: "fatal signal returned")
    }
}

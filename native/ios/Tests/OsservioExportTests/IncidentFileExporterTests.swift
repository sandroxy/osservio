import Foundation
import OsservioCore
@testable import OsservioExport
import XCTest
import ZIPFoundation

final class IncidentFileExporterTests: XCTestCase {
    private var root: URL!
    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("osservio-export-test-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
    }
    override func tearDownWithError() throws { try FileManager.default.removeItem(at: root) }

    func testZIPWriterPreservesAnExistingDestination() throws {
        let documents = try IncidentDocuments.encode(IncidentDocumentFixture.make("complete"))
        let target = root.appendingPathComponent("write.partial")
        let bytes = try IncidentZIP.write(documents, to: target, task: ExportTask())
        let original = try Data(contentsOf: target)
        XCTAssertEqual(bytes, Int64(original.count))
        XCTAssertThrowsError(try IncidentZIP.write(documents, to: target, task: ExportTask()))
        XCTAssertEqual(try Data(contentsOf: target), original)
    }

    func testCacheKeepsTheActivePartialUntilCommit() throws {
        let cache = try ExportCache(url: root, options: IncidentExportOptions())
        defer { cache.close() }
        XCTAssertNoThrow(try cache.clean(now: SystemLogClock().wallTimeMillis()), "initial cleanup")
        let partial = cache.newPartial()
        let documents = try IncidentDocuments.encode(IncidentDocumentFixture.make("complete"))
        let bytes = try IncidentZIP.write(documents, to: partial, task: ExportTask())
        XCTAssertTrue(FileManager.default.fileExists(atPath: partial.path))
        let file = try cache.commit(partial, bytes: bytes, now: SystemLogClock().wallTimeMillis())
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.url.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: partial.path))
    }

    func testCompleteZIPPreservesTheIndependentDocumentsAndSurvivesClose() async throws {
        let exporter = try IncidentFileExporter(directory: root)
        let snapshot = try IncidentDocumentFixture.make("complete")
        let file = try await prepare(exporter, snapshot).get()
        XCTAssertEqual(file.url.pathExtension, "zip")
        let attributes = try FileManager.default.attributesOfItem(atPath: file.url.path)
        XCTAssertEqual((attributes[.posixPermissions] as? NSNumber)?.intValue, 0o600)
        XCTAssertEqual((attributes[.size] as? NSNumber)?.int64Value, file.byteCount)
        let archive = try Archive(url: file.url, accessMode: .read)
        XCTAssertEqual(Set(archive.map(\.path)), ["incident.json", "payloads.json"])
        let documents = try IncidentDocuments.encode(snapshot)
        for (name, expected) in [("incident.json", documents.metadataJSON), ("payloads.json", documents.payloadsJSON)] {
            let entry = try XCTUnwrap(archive[name]); var actual = Data()
            let crc = try archive.extract(entry) { actual.append($0) }
            XCTAssertEqual(actual, expected); XCTAssertEqual(crc, entry.checksum)
        }
        try await close(exporter).get()
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.url.path))
        assertFailure(await prepare(exporter, snapshot), .closed)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: root.path).filter { $0.hasSuffix(".partial") }.count, 0)
    }

    func testCancellationBusyAdmissionAndOrderedClose() async throws {
        let clock = GatedExportClock(), exporter = try IncidentFileExporter(directory: root, clock: clock)
        let snapshot = try IncidentDocumentFixture.make("absent")
        let completed = expectation(description: "cancelled exactly once"); completed.assertForOverFulfill = true
        let task = try XCTUnwrap(exporter.prepare(snapshot) { result in
            self.assertFailure(result, .cancelled); completed.fulfill()
        })
        XCTAssertEqual(clock.entered.wait(timeout: .now() + 3), .success)
        assertFailure(await prepare(exporter, snapshot), .busy)
        task.cancel(); task.cancel()
        let closed = expectation(description: "closed after work")
        exporter.close { result in
            if case .failure(let error) = result { XCTFail("Unexpected close failure: \(error)") }
            closed.fulfill()
        }
        assertFailure(await prepare(exporter, snapshot), .closed)
        clock.release.signal()
        await fulfillment(of: [completed, closed], timeout: 5, enforceOrder: true)
        XCTAssertFalse(try FileManager.default.contentsOfDirectory(atPath: root.path).contains { $0.hasSuffix(".zip") || $0.hasSuffix(".partial") })
        let replacement = try IncidentFileExporter(directory: root)
        _ = try await prepare(replacement, snapshot).get()
        try await close(replacement).get()
    }

    func testExclusiveDirectoryOwnershipAndRetryAfterRelease() async throws {
        let first = try IncidentFileExporter(directory: root), second = try IncidentFileExporter(directory: root)
        let snapshot = try IncidentDocumentFixture.make("absent")
        _ = try await prepare(first, snapshot).get()
        assertFailure(await prepare(second, snapshot), .busy)
        try await close(first).get()
        _ = try await prepare(second, snapshot).get()
        try await close(second).get()
    }

    func testCancellationAfterWritingRemovesTheUnpublishedFile() async throws {
        let clock = GatedExportClock(pauseAt: 2), exporter = try IncidentFileExporter(directory: root, clock: clock)
        let completed = expectation(description: "cancelled after writing"); completed.assertForOverFulfill = true
        let task = try XCTUnwrap(exporter.prepare(IncidentDocumentFixture.make("complete")) { result in
            self.assertFailure(result, .cancelled); completed.fulfill()
        })
        XCTAssertEqual(clock.entered.wait(timeout: .now() + 3), .success)
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: root.path).contains { $0.hasSuffix(".partial") })
        task.cancel(); clock.release.signal()
        await fulfillment(of: [completed], timeout: 5)
        XCTAssertFalse(try FileManager.default.contentsOfDirectory(atPath: root.path).contains { $0.hasSuffix(".zip") || $0.hasSuffix(".partial") })
        try await close(exporter).get()
    }

    func testReplacedOwnershipFileStopsTheOldExporter() async throws {
        let directory = root.appendingPathComponent("cache")
        let first = try IncidentFileExporter(directory: directory), second = try IncidentFileExporter(directory: directory)
        let snapshot = try IncidentDocumentFixture.make("absent")
        _ = try await prepare(first, snapshot).get()
        try FileManager.default.moveItem(at: directory.appendingPathComponent("exports.owner"), to: root.appendingPathComponent("retired.owner"))
        let newFile = try await prepare(second, snapshot).get()
        assertFailure(await prepare(first, snapshot), .unavailable)
        XCTAssertTrue(FileManager.default.fileExists(atPath: newFile.url.path))
        try await close(first).get(); try await close(second).get()
    }

    func testSymbolicLinkCannotBecomeAnOwnedExport() async throws {
        let directory = root.appendingPathComponent("cache"), exporter = try IncidentFileExporter(directory: directory)
        let snapshot = try IncidentDocumentFixture.make("absent")
        let original = try await prepare(exporter, snapshot).get()
        let outside = root.appendingPathComponent("outside.txt")
        try Data("keep".utf8).write(to: outside)
        let link = directory.appendingPathComponent("osservio-ffffffff-ffff-ffff-ffff-ffffffffffff.zip")
        try FileManager.default.createSymbolicLink(at: link, withDestinationURL: outside)
        assertFailure(await prepare(exporter, snapshot), .unavailable)
        XCTAssertEqual(try Data(contentsOf: outside), Data("keep".utf8))
        XCTAssertTrue(FileManager.default.fileExists(atPath: original.url.path))
        XCTAssertEqual(try FileManager.default.destinationOfSymbolicLink(atPath: link.path), outside.path)
        try await close(exporter).get()
    }

    func testCountAgeAndRecoveryOnlyRemoveRecognizedTemporaryFiles() async throws {
        let exporter = try IncidentFileExporter(directory: root, options: .init(fileCount: 2, maximumAgeMillis: 60_000))
        let snapshot = try IncidentDocumentFixture.make("absent")
        let first = try await prepare(exporter, snapshot).get()
        try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSinceNow: -10)], ofItemAtPath: first.url.path)
        let second = try await prepare(exporter, snapshot).get()
        let third = try await prepare(exporter, snapshot).get()
        XCTAssertFalse(FileManager.default.fileExists(atPath: first.url.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: second.url.path))
        try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSinceNow: -61)], ofItemAtPath: third.url.path)
        let partial = root.appendingPathComponent("osservio-ffffffff-ffff-ffff-ffff-ffffffffffff.partial")
        let foreign = root.appendingPathComponent("host-owned.txt")
        try Data("partial".utf8).write(to: partial); try Data("keep".utf8).write(to: foreign)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: partial.path)
        assertFailure(await prepare(exporter, snapshot), .unavailable)
        XCTAssertTrue(FileManager.default.fileExists(atPath: partial.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: third.url.path))
        XCTAssertEqual(try Data(contentsOf: foreign), Data("keep".utf8))
        try FileManager.default.removeItem(at: foreign) // Test-owned fixture only.
        _ = try await prepare(exporter, snapshot).get()
        XCTAssertFalse(FileManager.default.fileExists(atPath: partial.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: third.url.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: second.url.path))
        try await close(exporter).get()
    }

    func testByteBudgetRejectsOneOversizedFileAndEvictsWholeOlderFiles() async throws {
        let exporter = try IncidentFileExporter(directory: root, options: .init(cacheBytes: 1_048_576))
        let snapshot = try incompressibleSnapshot(includeBreadcrumbs: false)
        let first = try await prepare(exporter, snapshot).get()
        XCTAssertGreaterThan(first.byteCount, 524_288)
        let second = try await prepare(exporter, snapshot).get()
        XCTAssertFalse(FileManager.default.fileExists(atPath: first.url.path))
        assertFailure(await prepare(exporter, try incompressibleSnapshot(includeBreadcrumbs: true)), .tooLarge)
        XCTAssertTrue(FileManager.default.fileExists(atPath: second.url.path))
        XCTAssertFalse(try FileManager.default.contentsOfDirectory(atPath: root.path).contains { $0.hasSuffix(".partial") })
        try await close(exporter).get()
    }

    func testOptionsRejectUnboundedOrUnusableLimits() {
        XCTAssertThrowsError(try IncidentExportOptions(fileCount: 0))
        XCTAssertThrowsError(try IncidentExportOptions(fileCount: 17))
        XCTAssertThrowsError(try IncidentExportOptions(cacheBytes: 0))
        XCTAssertThrowsError(try IncidentExportOptions(maximumAgeMillis: 59_999))
        XCTAssertThrowsError(try IncidentFileExporter(directory: URL(fileURLWithPath: "/")))
        XCTAssertThrowsError(try IncidentFileExporter(directory: URL(string: "file://remote.example/cache")!))
    }

    private func prepare(_ exporter: IncidentFileExporter, _ snapshot: IncidentSnapshot) async -> Result<IncidentExportFile, IncidentExportError> {
        await withCheckedContinuation { continuation in exporter.prepare(snapshot) { continuation.resume(returning: $0) } }
    }
    private func close(_ exporter: IncidentFileExporter) async -> Result<Void, IncidentExportError> {
        await withCheckedContinuation { continuation in exporter.close { continuation.resume(returning: $0) } }
    }
    private func assertFailure<T>(_ result: Result<T, IncidentExportError>, _ expected: IncidentExportError, file: StaticString = #filePath, line: UInt = #line) {
        if case .failure(let error) = result { XCTAssertEqual(error, expected, file: file, line: line) }
        else { XCTFail("Expected \(expected)", file: file, line: line) }
    }
    private func incompressibleSnapshot(includeBreadcrumbs: Bool) throws -> IncidentSnapshot {
        var state: UInt64 = 0x123456789abcdef
        func text(_ count: Int) -> String {
            let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".utf8)
            return String(decoding: (0..<count).map { _ in
                state ^= state << 13; state ^= state >> 7; state ^= state << 17
                return alphabet[Int(state >> 58)]
            }, as: UTF8.self)
        }
        let logs = try (1...16).map { try LogEvent.restore(sessionID: IncidentDocumentFixture.session, sequence: Int64($0), timestampMillis: 0,
            level: .info, category: "fixture", message: text(65_529), isPrivate: false, isTruncated: false) }
        let crumbs: IncidentBreadcrumbs?
        if includeBreadcrumbs {
            let events = try (1...256).map { index in try BreadcrumbEvent.restore(id: UUID().uuidString, sessionID: IncidentDocumentFixture.session,
                sequence: Int64(index), timestampMillis: 0, sessionElapsedMillis: nil, kind: .hostMarker, label: "a", message: text(4_095),
                isPrivate: false, isTruncated: false, value: nil, retention: .memoryOnly) }
            crumbs = try .restore(events: events, sourceEventCount: 256, sourceEvictedCount: 0)
        } else { crumbs = nil }
        return try .restore(id: IncidentDocumentFixture.id, timestampMillis: 0, note: nil, environment: nil, breadcrumbs: crumbs,
            logs: .restore(events: logs, observedAtMillis: 0, sourceEventCount: 16, sourceEvictedCount: 0))
    }
}

private final class GatedExportClock: LogClock, @unchecked Sendable {
    let entered = DispatchSemaphore(value: 0), release = DispatchSemaphore(value: 0)
    private let pauseAt: Int
    private var calls = 0 // The export worker is the only clock caller.
    init(pauseAt: Int = 1) { self.pauseAt = pauseAt }
    func monotonicMillis() -> UInt64 { 0 }
    func wallTimeMillis() -> Int64 {
        calls += 1
        if calls == pauseAt { entered.signal(); _ = release.wait(timeout: .now() + 5) }
        return SystemLogClock().wallTimeMillis()
    }
}

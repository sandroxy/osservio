import Darwin
import Foundation
import OsservioCore
@testable import OsservioCrash
import OsservioCrashBackend
import XCTest

final class CrashReportDirectoryTests: XCTestCase {
    private let now: Int64 = 1_700_000_000_000
    private func fixture(_ body: (URL) throws -> Void) throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("osservio-pending-test-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: directory) }
        try body(directory)
    }
    private func open(directory: URL, nowMillis: Int64) throws -> CrashReportDirectory {
        let files = try CrashReportDirectory(directory: directory, nowMillis: nowMillis)
        _ = try files.reserveForRecording()
        return files
    }
    private func write(_ data: Data, in slot: URL, time: Int64? = nil) throws -> URL {
        let path = slot.appendingPathComponent(CrashReportDirectory.reportName)
        try data.write(to: path, options: .withoutOverwriting)
        try FileManager.default.setAttributes([.posixPermissions: 0o600, .modificationDate: Date(timeIntervalSince1970: Double(time ?? now) / 1000)], ofItemAtPath: path.path)
        return path
    }
    /// Synthetic fatal classification for file-policy checks, not actual crash-delivery evidence.
    private func report() throws -> Data {
        let config = Osservio_PLCrashReporterConfig(signalHandlerType: .BSD, symbolicationStrategy: [],
            shouldRegisterUncaughtExceptionHandler: false, basePath: NSTemporaryDirectory(), maxReportBytes: 65_536)
        let reporter = try XCTUnwrap(Osservio_PLCrashReporter(configuration: config))
        var data = try XCTUnwrap(try reporter.generateLiveReportAndReturnError())
        XCTAssertEqual(Array(data[8..<12]), [0x4a, 20, 0x08, 1]); data[11] = 0
        return data
    }
    func testExclusiveOwnershipReopenAndInvalidReportRemainExplicit() throws {
        try fixture { root in
            var first: CrashReportDirectory? = try open(directory: root, nowMillis: now)
            let active = try XCTUnwrap(first?.activeDirectory)
            XCTAssertTrue(try XCTUnwrap(first).read(observedAtMillis: 42).diagnostics.isEmpty)
            XCTAssertThrowsError(try open(directory: root, nowMillis: now)) { XCTAssertEqual($0 as? PersistenceError, .busy) }
            let data = try report(), path = try write(data, in: active)
            XCTAssertTrue(try XCTUnwrap(first).read(observedAtMillis: 42).diagnostics.isEmpty, "Current-process slot is never recovered")
            first = nil
            var second: CrashReportDirectory? = try open(directory: root, nowMillis: now + 1)
            let batch = try XCTUnwrap(second).read(observedAtMillis: 42)
            XCTAssertEqual(batch.source, .appleSignal); XCTAssertEqual(batch.observedAtMillis, 42); XCTAssertEqual(batch.diagnostics.count, 1)
            XCTAssertEqual(batch.diagnostics.first?.appleSignal?.reportID, try CrashReportDecoder.decode(data).reportID)
            XCTAssertEqual(try Data(contentsOf: path), data)
            let badPath = try write(Data([1, 2, 3]), in: XCTUnwrap(second?.activeDirectory))
            second = nil
            let third = try open(directory: root, nowMillis: now + 2)
            let result = try third.read(observedAtMillis: 44)
            XCTAssertEqual(result.diagnostics.count, 1); XCTAssertEqual(result.rejectedCount, 1); XCTAssertEqual(result.excludedCount, 0)
            XCTAssertEqual(try Data(contentsOf: badPath), Data([1, 2, 3]), "Malformed reports are not repaired or deleted by reads")
            let repeated = try third.read(observedAtMillis: 45)
            XCTAssertEqual(repeated.diagnostics.first?.appleSignal?.reportID, result.diagnostics.first?.appleSignal?.reportID)
            XCTAssertTrue(try root.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == true)
        }
    }
    func testCapacityAndAgeReserveOneSlotAndDeclareLoss() throws {
        try fixture { root in
            var paths: [URL] = []
            for index in 0..<4 {
                let files = try open(directory: root, nowMillis: now)
                XCTAssertEqual(files.excludedCount, 0)
                paths.append(try write(Data([UInt8(index)]), in: XCTUnwrap(files.activeDirectory), time: now + Int64(index * 1000)))
            }
            var rotated: CrashReportDirectory? = try open(directory: root, nowMillis: now + 4000)
            XCTAssertEqual(rotated?.excludedCount, 1); XCTAssertTrue(rotated?.capacityReached == true)
            XCTAssertFalse(FileManager.default.fileExists(atPath: paths[0].path))
            for path in paths.dropFirst() { XCTAssertTrue(FileManager.default.fileExists(atPath: path.path)) }
            let batch = try XCTUnwrap(rotated).read(observedAtMillis: 42)
            XCTAssertEqual(batch.rejectedCount, 3); XCTAssertEqual(batch.excludedCount, 1); XCTAssertTrue(batch.sourceLimitReached)
            rotated = nil
            let expired = try open(directory: root, nowMillis: now + 4000 + CrashReportDirectory.maximumAgeMillis)
            XCTAssertEqual(expired.excludedCount, 3); XCTAssertFalse(expired.capacityReached)
            XCTAssertTrue(try expired.read(observedAtMillis: 43).diagnostics.isEmpty)
            for path in paths { XCTAssertFalse(FileManager.default.fileExists(atPath: path.path)) }
        }
        try fixture { root in
            var first: CrashReportDirectory? = try open(directory: root, nowMillis: now)
            let path = try write(Data([1]), in: XCTUnwrap(first?.activeDirectory)); first = nil
            var boundary: CrashReportDirectory? = try open(directory: root, nowMillis: now + CrashReportDirectory.maximumAgeMillis)
            XCTAssertEqual(boundary?.excludedCount, 0); XCTAssertTrue(FileManager.default.fileExists(atPath: path.path)); boundary = nil
            let expired = try open(directory: root, nowMillis: now + CrashReportDirectory.maximumAgeMillis + 1)
            XCTAssertEqual(expired.excludedCount, 1); XCTAssertFalse(FileManager.default.fileExists(atPath: path.path))
        }
    }
    func testForeignDirectoriesUnsupportedFormatsAndLargeFilesAreNotReset() throws {
        try fixture { root in
            let sentinel = root.appendingPathComponent("host-owned.txt"); try Data([7]).write(to: sentinel)
            XCTAssertThrowsError(try open(directory: root, nowMillis: now)) { XCTAssertEqual($0 as? PersistenceError, .unsupportedSchema) }
            XCTAssertEqual(try Data(contentsOf: sentinel), Data([7])); XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent("owner").path))
        }
        try fixture { root in
            var files: CrashReportDirectory? = try open(directory: root, nowMillis: now)
            let path = try write(Data(count: 65_537), in: XCTUnwrap(files?.activeDirectory)); files = nil
            XCTAssertThrowsError(try open(directory: root, nowMillis: now + CrashReportDirectory.maximumAgeMillis + 1)) {
                XCTAssertEqual($0 as? PersistenceError, .invalidRecord)
            }
            XCTAssertEqual(try Data(contentsOf: path).count, 65_537, "Even expired oversized material must not be silently reset")
        }
        try fixture { root in
            var files: CrashReportDirectory? = try open(directory: root, nowMillis: now)
            XCTAssertNotNil(files); files = nil
            let format = root.appendingPathComponent("format"), future = Data("Osservio crash files/2\n".utf8)
            try future.write(to: format)
            XCTAssertThrowsError(try open(directory: root, nowMillis: now)) { XCTAssertEqual($0 as? PersistenceError, .unsupportedSchema) }
            XCTAssertEqual(try Data(contentsOf: format), future)
        }
    }
    func testAgeCleanupPreservesTheSubsecondModificationTime() throws {
        try fixture { root in
            var writer: CrashReportDirectory? = try open(directory: root, nowMillis: now)
            let path = try write(Data([1]), in: XCTUnwrap(writer?.activeDirectory), time: now + 500)
            writer = nil
            var reader: CrashReportDirectory? = try CrashReportDirectory(directory: root,
                nowMillis: now + CrashReportDirectory.maximumAgeMillis + 400)
            XCTAssertEqual(reader?.excludedCount, 0); XCTAssertTrue(FileManager.default.fileExists(atPath: path.path))
            reader = nil
            let expired = try CrashReportDirectory(directory: root,
                nowMillis: now + CrashReportDirectory.maximumAgeMillis + 600)
            XCTAssertEqual(expired.excludedCount, 1); XCTAssertFalse(FileManager.default.fileExists(atPath: path.path))
        }
    }
    func testSymlinksHardlinksPermissionsAndNonRegularFilesFailWithoutFollowingThem() throws {
        for kind in ["symlink", "hardlink", "permissions", "fifo", "unknown"] {
            try fixture { root in
                let directory = root.appendingPathComponent("reports")
                var files: CrashReportDirectory? = try open(directory: directory, nowMillis: now)
                let slot = try XCTUnwrap(files?.activeDirectory), path = slot.appendingPathComponent(CrashReportDirectory.reportName)
                files = nil
                let sentinel = root.appendingPathComponent("protected.bin"); try Data([7]).write(to: sentinel)
                try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: sentinel.path)
                switch kind {
                case "symlink": try FileManager.default.createSymbolicLink(at: path, withDestinationURL: sentinel)
                case "hardlink": try FileManager.default.linkItem(at: sentinel, to: path)
                case "permissions":
                    try Data([1]).write(to: path); try FileManager.default.setAttributes([.posixPermissions: 0o644], ofItemAtPath: path.path)
                case "fifo": XCTAssertEqual(mkfifo(path.path, 0o600), 0)
                default: try Data([1]).write(to: slot.appendingPathComponent("unknown"))
                }
                XCTAssertThrowsError(try open(directory: directory, nowMillis: now + CrashReportDirectory.maximumAgeMillis + 1), kind)
                XCTAssertEqual(try Data(contentsOf: sentinel), Data([7]), kind)
            }
        }
    }
    func testReadCannotAllocateFromAChangedFileSize() throws {
        try fixture { root in
            var first: CrashReportDirectory? = try open(directory: root, nowMillis: now)
            let path = try write(Data([1]), in: XCTUnwrap(first?.activeDirectory)); first = nil
            let reader = try open(directory: root, nowMillis: now + 1)
            let changed = Data(count: 65_537); try changed.write(to: path)
            XCTAssertThrowsError(try reader.read(observedAtMillis: 42)) { XCTAssertEqual($0 as? PersistenceError, .invalidRecord) }
            XCTAssertEqual(try Data(contentsOf: path), changed)
        }
    }

    func testReadingAllFourSlotsDoesNotReserveSpaceOrEvictEvidence() throws {
        try fixture { root in
            var paths: [URL] = []
            for index in 0..<4 {
                let writer = try open(directory: root, nowMillis: now)
                paths.append(try write(Data([UInt8(index)]), in: XCTUnwrap(writer.activeDirectory), time: now + Int64(index * 1000)))
            }
            let reader = try CrashReportDirectory(directory: root, nowMillis: now + 4000)
            XCTAssertNil(reader.activeDirectory)
            let batch = try reader.read(observedAtMillis: 42)
            XCTAssertEqual(batch.rejectedCount, 4); XCTAssertEqual(batch.excludedCount, 0); XCTAssertFalse(batch.sourceLimitReached)
            for path in paths { XCTAssertTrue(FileManager.default.fileExists(atPath: path.path)) }
            _ = try reader.reserveForRecording()
            let reserved = try reader.read(observedAtMillis: 43)
            XCTAssertEqual(reserved.rejectedCount, 3); XCTAssertEqual(reserved.excludedCount, 1); XCTAssertTrue(reserved.sourceLimitReached)
        }
    }

    #if os(iOS)
    func testNewFilesInheritThePendingDirectoryProtection() throws {
        try fixture { root in
            let directory = root.appendingPathComponent("pending")
            let files = try open(directory: directory, nowMillis: now)
            let active = try XCTUnwrap(files.activeDirectory)
            let path = active.appendingPathComponent(CrashReportDirectory.reportName)
            let descriptor = Darwin.open(path.path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0o600)
            XCTAssertGreaterThanOrEqual(descriptor, 0)
            if descriptor >= 0 { Darwin.close(descriptor) }
            for url in [directory, active, path] {
                let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
                #if targetEnvironment(simulator)
                try XCTSkipIf(attributes[.protectionKey] == nil,
                              "This simulator does not expose file protection. Verify inheritance on a physical iOS device.")
                #endif
                XCTAssertEqual(attributes[.protectionKey] as? String, FileProtectionType.completeUntilFirstUserAuthentication.rawValue,
                               "Protection attribute: \(String(describing: attributes[.protectionKey]))")
            }
        }
    }
    #endif
}

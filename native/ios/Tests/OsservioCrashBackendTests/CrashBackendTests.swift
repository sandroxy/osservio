import Foundation
import OsservioCrashBackend
import OsservioCrashSupport
import XCTest

final class CrashBackendTests: XCTestCase {
    override func setUp() { super.setUp(); OsservioCrashCaptureSetArmed(true) }
    override func tearDown() { OsservioCrashCaptureSetArmed(false); OsservioCrashCaptureUpdate(false, 0); super.tearDown() }

    func testPermissionStopsAndExpiresWithoutCallingSwiftFromTheHandler() {
        OsservioCrashCaptureUpdate(false, 0)
        XCTAssertFalse(OsservioCrashCaptureAllowsRecording())
        OsservioCrashCaptureUpdate(true, 0)
        XCTAssertTrue(OsservioCrashCaptureAllowsRecording())
        OsservioCrashCaptureUpdate(false, deadline(after: 10_000))
        XCTAssertTrue(OsservioCrashCaptureAllowsRecording())
        OsservioCrashCaptureUpdate(false, 0)
        XCTAssertFalse(OsservioCrashCaptureAllowsRecording())
        OsservioCrashCaptureUpdate(false, deadline(after: 1))
        Thread.sleep(forTimeInterval: 0.01)
        XCTAssertFalse(OsservioCrashCaptureAllowsRecording())
        OsservioCrashCaptureUpdate(true, deadline(after: 1))
        Thread.sleep(forTimeInterval: 0.01)
        XCTAssertTrue(OsservioCrashCaptureAllowsRecording())
    }

    func testAnAlreadyElapsedAbsoluteDeadlineCannotGrantFreshTime() {
        let elapsed = deadline(after: 0)
        Thread.sleep(forTimeInterval: 0.01)
        OsservioCrashCaptureUpdate(false, elapsed)
        XCTAssertFalse(OsservioCrashCaptureAllowsRecording())
    }

    private func deadline(after milliseconds: UInt64) -> UInt64 {
        DispatchTime.now().uptimeNanoseconds / 1_000_000 + milliseconds
    }

    func testSignalNumberFallbackPreservesSignedBoundariesWithinItsFixedBuffer() throws {
        for value: Int32 in [Int32.min, -42, -1, 0, 1, 42, Int32.max] {
            var bytes = [CChar](repeating: 0x7f, count: 15)
            bytes.withUnsafeMutableBufferPointer { buffer in
                OsservioCrashFormatSignalNumber(value, buffer.baseAddress!.advanced(by: 1))
            }
            XCTAssertEqual(bytes.first, 0x7f)
            XCTAssertEqual(bytes.last, 0x7f)
            let end = try XCTUnwrap(bytes[1..<14].firstIndex(of: 0))
            XCTAssertEqual(String(decoding: bytes[1..<end].map { UInt8(bitPattern: $0) }, as: UTF8.self), "#\(value)")
        }
    }

    func testLiveReportExcludesRawContentAndPreservesAnAddressStack() throws {
        let (data, report) = try liveReport()
        XCTAssertLessThanOrEqual(data.count, 65_536)
        var profile = Data("OsservioCrash/1".utf8)
        profile.append(OsservioCrashImageFlags())
        XCTAssertEqual(report.customData, profile)
        XCTAssertFalse(report.hasProcessInfo)
        XCTAssertFalse(report.hasExceptionInfo)
        XCTAssertNotNil(report.uuidRef)
        XCTAssertTrue(report.hasReportInfo)
        XCTAssertTrue(report.userRequested)
        XCTAssertEqual(report.threads.count, 1)
        let thread = try XCTUnwrap(report.threads.first as? Osservio_PLCrashReportThreadInfo)
        XCTAssertTrue(thread.crashed)
        XCTAssertEqual(thread.registers.count, 0)
        XCTAssertFalse(thread.stackFrames.isEmpty)
        XCTAssertLessThanOrEqual(thread.stackFrames.count, 128)
        for case let frame as Osservio_PLCrashReportStackFrameInfo in thread.stackFrames {
            XCTAssertNil(frame.symbolInfo)
        }
        XCTAssertFalse(report.images.isEmpty)
        XCTAssertLessThanOrEqual(report.images.count, 512)
        for case let image as Osservio_PLCrashReportBinaryImageInfo in report.images {
            XCTAssertEqual(image.imageName, "")
        }
        XCTAssertNil(data.range(of: Data("PRIVATE_CRASH_CANARY".utf8)))
    }

    func testDecoderRejectsAnIncompleteReport() throws {
        let (data, _) = try liveReport()
        for count in [0, 1, 7, 8, data.count / 2] {
            XCTAssertThrowsError(try Osservio_PLCrashReport(data: Data(data.prefix(count))))
        }
        var invalid = data
        invalid[0] = 0
        XCTAssertThrowsError(try Osservio_PLCrashReport(data: invalid))
        var oversized = data
        oversized.append(Data(count: 65_537 - data.count))
        XCTAssertThrowsError(try Osservio_PLCrashReport(data: oversized))
        // A small file can claim a four-gigabyte nested message. Reject it
        // without accepting that declared length as an allocation budget.
        var impossibleLength = Data(data.prefix(8))
        impossibleLength.append(contentsOf: [0x0a, 0xff, 0xff, 0xff, 0xff, 0x0f])
        XCTAssertThrowsError(try Osservio_PLCrashReport(data: impossibleLength))
    }

    private func liveReport() throws -> (Data, Osservio_PLCrashReport) {
        let config = Osservio_PLCrashReporterConfig(signalHandlerType: .BSD, symbolicationStrategy: [],
            shouldRegisterUncaughtExceptionHandler: false, basePath: NSTemporaryDirectory(), maxReportBytes: 65_536)
        let reporter = try XCTUnwrap(Osservio_PLCrashReporter(configuration: config))
        reporter.customData = Data("PRIVATE_CRASH_CANARY".utf8)
        // Generating a live test report installs no signal or exception handler.
        let data = try XCTUnwrap(try reporter.generateLiveReportAndReturnError())
        return (data, try XCTUnwrap(try Osservio_PLCrashReport(data: data)))
    }
}

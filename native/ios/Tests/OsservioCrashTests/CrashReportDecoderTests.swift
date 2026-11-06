import Foundation
import OsservioCore
@testable import OsservioCrash
import OsservioCrashBackend
import XCTest

final class CrashReportDecoderTests: XCTestCase {
    func testMapsOnlyOriginalReviewedAddressEvidence() throws {
        let data = try fixture()
        let raw = try XCTUnwrap(try Osservio_PLCrashReport(data: data))
        let value = try CrashReportDecoder.decode(data)
        XCTAssertEqual(value.reportID, (CFUUIDCreateString(nil, raw.uuidRef) as String).lowercased())
        XCTAssertEqual(value.timestampMillis, Int64(raw.systemInfo.timestamp.timeIntervalSince1970) * 1_000)
        XCTAssertEqual(value.signal.rawValue, raw.signalInfo.name)
        XCTAssertEqual(value.code, raw.signalInfo.code)
        XCTAssertEqual(value.faultAddress, raw.signalInfo.address)
        let thread = try XCTUnwrap(raw.threads.first as? Osservio_PLCrashReportThreadInfo)
        XCTAssertEqual(value.threadNumber, UInt32(thread.threadNumber))
        XCTAssertEqual(value.frames, thread.stackFrames.map { ($0 as! Osservio_PLCrashReportStackFrameInfo).instructionPointer })
        XCTAssertEqual(value.images.count, raw.images.count)
        for (mapped, entry) in zip(value.images, raw.images) {
            let image = try XCTUnwrap(entry as? Osservio_PLCrashReportBinaryImageInfo)
            XCTAssertEqual(mapped.baseAddress, image.imageBaseAddress)
            XCTAssertEqual(mapped.size, image.imageSize)
            XCTAssertEqual(mapped.uuid, image.imageUUID)
        }
        XCTAssertEqual(value.metadata[.osVersion]?.value, raw.systemInfo.operatingSystemVersion)
        XCTAssertTrue(value.metadata.values.allSatisfy { !$0.isTruncated })
    }

    func testRequestedReportsUnknownProfilesAndCorruptionAreRejected() throws {
        XCTAssertThrowsError(try CrashReportDecoder.decode(fixture(fatal: false))) {
            XCTAssertEqual($0 as? CrashReportDecodingError, .requestedReport)
        }
        let data = try fixture(), marker = Data("OsservioCrash/1".utf8)
        let range = try XCTUnwrap(data.range(of: marker))
        var revision = data; revision[range.upperBound - 1] = Character("2").asciiValue!
        var reservedFlags = data; reservedFlags[range.upperBound] = 0x80
        for value in [revision, reservedFlags] {
            XCTAssertThrowsError(try CrashReportDecoder.decode(value)) {
                XCTAssertEqual($0 as? CrashReportDecodingError, .unsupportedProfile)
            }
        }
        for value in [Data(), Data(data.prefix(7)), Data(data.prefix(data.count / 2)), Data(count: 65_537)] {
            XCTAssertThrowsError(try CrashReportDecoder.decode(value)) {
                XCTAssertEqual($0 as? CrashReportDecodingError, .invalidReport)
            }
        }
    }

    func testAValidProtobufReportCannotBypassThePrivateRecordingProfile() throws {
        let data = try fixture()
        // Independently specified proto2 suffixes: an extra thread, exception,
        // process-info message, or image with a path. The upstream decoder accepts
        // each complete message; the Osservio profile must reject it.
        let suffixes: [[UInt8]] = [
            [0x1a, 4, 0x08, 127, 0x18, 0],
            [0x2a, 6, 0x0a, 1, 65, 0x12, 1, 66],
            [0x3a, 6, 0x10, 1, 0x28, 1, 0x30, 1],
            [0x22, 7, 0x08, 0, 0x10, 1, 0x1a, 1, 88]
        ]
        for suffix in suffixes {
            let changed = data + Data(suffix)
            XCTAssertNotNil(try Osservio_PLCrashReport(data: changed))
            XCTAssertThrowsError(try CrashReportDecoder.decode(changed)) {
                XCTAssertEqual($0 as? CrashReportDecodingError, .invalidReport)
            }
        }
    }

    /// Synthetic classification only: generate a real live encoding, then change
    /// the known first report-info boolean. Actual fatal reports are checked by
    /// the isolated-process fixture; this helper is not fatal-delivery evidence.
    private func fixture(fatal: Bool = true) throws -> Data {
        let config = Osservio_PLCrashReporterConfig(signalHandlerType: .BSD, symbolicationStrategy: [],
            shouldRegisterUncaughtExceptionHandler: false, basePath: NSTemporaryDirectory(), maxReportBytes: 65_536)
        let reporter = try XCTUnwrap(Osservio_PLCrashReporter(configuration: config))
        var data = try XCTUnwrap(try reporter.generateLiveReportAndReturnError())
        guard data.count > 12, Array(data[8..<12]) == [0x4a, 20, 0x08, 1] else {
            throw CrashReportDecodingError.invalidReport
        }
        if fatal { data[11] = 0 }
        return data
    }
}

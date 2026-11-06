import Foundation
import OsservioCore
import XCTest

final class AppleSignalCrashTests: XCTestCase {
    func testSharedRestorationBoundsAndFieldInvariants() throws {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let fixture = try String(contentsOf: root.appendingPathComponent("testing/apple-signal-cases.tsv"), encoding: .utf8)
        for line in fixture.split(separator: "\n") {
            let row = line.split(separator: "\t").map(String.init)
            XCTAssertEqual(row.count, 4)
            do {
                _ = try make(field: row[1], value: row[2])
                XCTAssertEqual(row[3], "valid", row[0])
            } catch { XCTAssertEqual(row[3], "invalid", row[0]) }
        }
    }

    func testExactAddressesMissingEvidenceAndCompletenessFlags() throws {
        let value = try make(field: "frames", value: "128")
        XCTAssertEqual(value.faultAddress, UInt64.max)
        XCTAssertEqual(value.frames.last, UInt64.max)
        XCTAssertEqual(value.timestampMillis, 1_700_000_000_000)
        XCTAssertTrue(value.frameLimitReached)
        XCTAssertFalse(value.imageLimitReached)
        XCTAssertTrue(value.imageCacheIncomplete)
        XCTAssertEqual(value.images.first?.cpuSubtype, UInt64.max)
        XCTAssertEqual(value.textByteCount, 81)
        let unavailable = try make(field: "missing_stack", value: "-")
        XCTAssertNil(unavailable.threadNumber)
        XCTAssertTrue(unavailable.frames.isEmpty)
        XCTAssertFalse(unavailable.frameLimitReached)
        XCTAssertTrue(try make(field: "images", value: "512").imageLimitReached)
    }

    private func make(field: String, value: String) throws -> AppleSignalCrash {
        func unsigned(_ text: String) throws -> UInt64 {
            guard let result = UInt64(text) else { throw PersistenceError.invalidRecord }; return result
        }
        let pair = field == "signal_code" ? value.split(separator: ":").map(String.init) : ["SIGABRT", field == "code" ? value : "#0"]
        guard pair.count == 2, let signal = AppleSignalCrash.Signal(rawValue: pair[0]),
              let time = Int64(field == "time" ? value : "1700000000000") else { throw PersistenceError.invalidRecord }
        let noThread = field == "missing_stack" || (field == "thread" && value == "-")
        let thread: UInt32?
        if noThread { thread = nil }
        else {
            guard let number = UInt32(field == "thread" ? value : "4294967295") else { throw PersistenceError.invalidRecord }
            thread = number
        }
        let noCPU = field == "missing_cpu"
        let image = try AppleSignalCrash.Image.restore(baseAddress: 4096,
            size: field == "image_size" ? unsigned(value) : 4096,
            uuid: field == "image_uuid" ? (value == "-" ? nil : value) : "12345678123412341234abcdef123456",
            cpuType: noCPU || (field == "cpu_type" && value == "-") ? nil : 16_777_228,
            cpuSubtype: noCPU ? nil : UInt64.max)
        let text = field == "metadata" ? (value == "nul" ? "before\0after" : String(repeating: "x", count: 257)) : "😀"
        return try .restore(reportID: field == "id" ? value : "12345678-1234-1234-1234-abcdef123456", timestampMillis: time,
            signal: signal, code: pair[1], faultAddress: field == "address" ? unsigned(value) : UInt64.max,
            threadNumber: thread, frames: Array(repeating: UInt64.max, count: field == "frames" ? Int(value)! : (field == "missing_stack" ? 0 : 2)),
            images: Array(repeating: image, count: field == "images" ? Int(value)! : 1),
            metadata: [.appVersion: try .capture(publicValue: text)], imageCacheIncomplete: true)
    }
}

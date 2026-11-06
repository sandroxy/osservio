import Foundation
import OsservioCore
import XCTest

final class AndroidTombstoneTests: XCTestCase {
    func testSharedSourceAndReadStateInvariants() throws {
        for line in try IncidentDetailFixture.shared("android-tombstone-states.tsv").split(separator: "\n") {
            let row = line.split(separator: "\t").map(String.init)
            do {
                let value = try SystemDiagnostic.androidExit(timestampMillis: 1000, reasonCode: Int32(row[1])!, status: 6, processID: 123,
                    importance: 100, pssKiB: nil, rssKiB: nil, tombstoneState: SystemDiagnostic.TombstoneState(rawValue: row[2])!,
                    tombstone: row[3] == "true" ? make(field: "frames", value: "2") : nil)
                XCTAssertEqual(row[4], "valid", row[0]); XCTAssertEqual(value.textByteCount, row[3] == "true" ? 80 : 0)
            } catch { XCTAssertEqual(row[4], "invalid", row[0]) }
        }
        XCTAssertEqual(try SystemDiagnostic.androidExit(timestampMillis: 1000, reasonCode: 5, status: 6, processID: 123,
            importance: 100, pssKiB: nil, rssKiB: nil).androidExit?.tombstoneState, .notRequested)
        XCTAssertEqual(try SystemDiagnostic.androidExit(timestampMillis: 1000, reasonCode: 6, status: 0, processID: 123,
            importance: 100, pssKiB: nil, rssKiB: nil).androidExit?.tombstoneState, .notApplicable)
    }
    func testSharedBoundsAndEvidenceInvariants() throws {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let fixture = try String(contentsOf: root.appendingPathComponent("testing/android-tombstone-cases.tsv"), encoding: .utf8)
        for line in fixture.split(separator: "\n") {
            let row = line.split(separator: "\t").map(String.init)
            XCTAssertEqual(row.count, 4)
            do { _ = try make(field: row[1], value: row[2]); XCTAssertEqual(row[3], "valid", row[0]) }
            catch { XCTAssertEqual(row[3], "invalid", row[0]) }
        }
    }
    func testExactAddressesAndCompletenessFlags() throws {
        let value = try make(field: "frames", value: "128")
        XCTAssertEqual(value.faultAddress, UInt64.max)
        XCTAssertEqual(value.frames.last?.programCounter, UInt64.max)
        XCTAssertEqual(value.frames.last?.relativeProgramCounter, UInt64.max)
        XCTAssertTrue(value.frameLimitReached)
        XCTAssertEqual(value.textByteCount, 5120)
        XCTAssertFalse(try make(field: "frames", value: "0").frameLimitReached)
        XCTAssertEqual(try make(field: "frames", value: "0").textByteCount, 0)
        XCTAssertNil(try make(field: "address", value: "-").faultAddress)
        XCTAssertNil(try make(field: "build_id", value: "-").frames.first?.buildID)
    }
    private func make(field: String, value: String) throws -> AndroidTombstone {
        func unsigned(_ text: String) throws -> UInt64 {
            guard let result = UInt64(text), String(result) == text else { throw PersistenceError.invalidRecord }
            return result
        }
        var build: String? = "0123456789abcdef0123456789abcdef01234567"
        if field == "build_id" {
            build = value == "-" ? nil : value == "empty" ? "" :
                ["128", "130"].contains(value) ? String(repeating: "a", count: Int(value)!) : value
        }
        let frame = try AndroidTombstone.Frame.restore(programCounter: field == "pc" ? unsigned(value) : .max,
            relativeProgramCounter: field == "relative_pc" ? unsigned(value) : .max, buildID: build)
        return try .restore(architectureCode: Int32(field == "architecture" ? value : "1")!,
            signalNumber: Int32(field == "signal" ? value : "6")!, signalCode: Int32(field == "code" ? value : "-6")!,
            threadID: Int32(field == "thread" ? value : "1234")!,
            faultAddress: field == "address" ? (value == "-" ? nil : unsigned(value)) : .max,
            frames: Array(repeating: frame, count: field == "frames" ? Int(value)! : 2))
    }
}

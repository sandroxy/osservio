import Foundation
import OsservioCore
@testable import OsservioStore
import XCTest

final class SQLiteConnectionTests: XCTestCase {
    private func fixture(_ body: (URL) throws -> Void) throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("osservio-sqlite-test-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: directory) }
        try body(directory.appendingPathComponent("test.sqlite"))
    }
    func testBoundTextAndTypedReadsPreserveNulUnicodeAndLargeIntegers() throws {
        try fixture { url in
            let db = try SQLiteConnection(url: url)
            defer { db.close() }
            try db.execute("CREATE TABLE sample (text_value TEXT, integer_value INTEGER, flag INTEGER)")
            try db.execute("INSERT INTO sample VALUES (?, ?, ?)", [.text("a\u{0}例"), .integer(Int64.max), .integer(1)])
            try db.statement("SELECT text_value, integer_value, flag FROM sample") { row in
                XCTAssertTrue(try row.step())
                XCTAssertEqual(try row.text(0, byteLimit: 5), "a\u{0}例")
                XCTAssertEqual(try row.integer(1), Int64.max)
                XCTAssertTrue(try row.flag(2))
                XCTAssertThrowsError(try row.text(0, byteLimit: 4))
                XCTAssertThrowsError(try row.integer(0))
                XCTAssertFalse(try row.step())
            }
            XCTAssertThrowsError(try db.execute("INSERT INTO sample VALUES (?, ?, ?)", [.null]))
        }
    }
    func testTransactionRollbackAndReopenPreserveCommittedRows() throws {
        try fixture { url in
            let db = try SQLiteConnection(url: url)
            try db.execute("CREATE TABLE sample (value INTEGER NOT NULL)")
            try db.transaction { try db.execute("INSERT INTO sample VALUES (?)", [.integer(1)]) }
            XCTAssertThrowsError(try db.transaction {
                try db.execute("INSERT INTO sample VALUES (?)", [.integer(2)])
                throw PersistenceError.invalidRecord
            })
            db.close()
            let reopened = try SQLiteConnection(url: url)
            defer { reopened.close() }
            try reopened.statement("SELECT value FROM sample") { row in
                XCTAssertTrue(try row.step()); XCTAssertEqual(try row.integer(0), 1); XCTAssertFalse(try row.step())
            }
        }
    }
    func testContentionAndInvalidTypesAreExplicitFailures() throws {
        try fixture { url in
            let writer = try SQLiteConnection(url: url)
            let contender = try SQLiteConnection(url: url)
            defer { contender.close(); writer.close() }
            try writer.execute("CREATE TABLE sample (value)")
            try writer.execute("BEGIN IMMEDIATE")
            XCTAssertThrowsError(try contender.execute("INSERT INTO sample VALUES (1)")) { XCTAssertEqual($0 as? PersistenceError, .busy) }
            try writer.execute("ROLLBACK")
            try writer.execute("INSERT INTO sample VALUES ('1'), (2), (NULL)")
            try writer.statement("SELECT value FROM sample ORDER BY rowid") { row in
                XCTAssertTrue(try row.step()); XCTAssertThrowsError(try row.integer(0))
                XCTAssertTrue(try row.step()); XCTAssertThrowsError(try row.flag(0))
                XCTAssertTrue(try row.step()); XCTAssertTrue(row.isNull(0)); XCTAssertThrowsError(try row.text(0, byteLimit: 16))
            }
        }
    }
}

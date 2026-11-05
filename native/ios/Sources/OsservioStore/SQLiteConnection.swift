import Foundation
import OsservioCore
import SQLite3

enum SQLiteValue { case integer(Int64), text(String), null }

/// Owned by one serial worker. No statement or connection escapes into a capture hook or callback.
final class SQLiteConnection {
    private var handle: OpaquePointer?
    init(url: URL) throws {
        var opened: OpaquePointer?
        let result = sqlite3_open_v2(url.path, &opened, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_NOMUTEX, nil)
        guard result == SQLITE_OK, let opened else {
            if let opened { sqlite3_close_v2(opened) }
            throw Self.error(result)
        }
        handle = opened
        sqlite3_extended_result_codes(opened, 1)
        sqlite3_busy_timeout(opened, 0)
    }
    deinit { close() }
    func close() { if let handle { sqlite3_close_v2(handle); self.handle = nil } }
    func statement<T>(_ sql: String, _ values: [SQLiteValue] = [], body: (SQLiteStatement) throws -> T) throws -> T {
        guard let handle else { throw PersistenceError.closed }
        var prepared: OpaquePointer?
        let result = sqlite3_prepare_v2(handle, sql, -1, &prepared, nil)
        guard result == SQLITE_OK, let prepared else {
            if let prepared { sqlite3_finalize(prepared) }
            throw Self.error(result)
        }
        defer { sqlite3_finalize(prepared) }
        guard sqlite3_bind_parameter_count(prepared) == values.count else { throw PersistenceError.invalidRecord }
        for (offset, value) in values.enumerated() {
            let index = Int32(offset + 1)
            let bound: Int32
            switch value {
            case .integer(let number): bound = sqlite3_bind_int64(prepared, index, number)
            case .null: bound = sqlite3_bind_null(prepared, index)
            case .text(let string):
                let bytes = string.utf8CString
                guard bytes.count - 1 <= Int32.max else { throw PersistenceError.invalidRecord }
                bound = bytes.withUnsafeBufferPointer { buffer in
                    // An explicit byte length preserves embedded NULs. SQLite copies before this buffer leaves scope.
                    sqlite3_bind_text(prepared, index, buffer.baseAddress, Int32(buffer.count - 1), unsafeBitCast(-1, to: sqlite3_destructor_type.self))
                }
            }
            guard bound == SQLITE_OK else { throw Self.error(bound) }
        }
        return try body(SQLiteStatement(handle: prepared))
    }
    func execute(_ sql: String, _ values: [SQLiteValue] = []) throws {
        try statement(sql, values) { statement in
            guard try !statement.step() else { throw PersistenceError.invalidRecord }
        }
    }
    func transaction<T>(_ body: () throws -> T) throws -> T {
        try execute("BEGIN IMMEDIATE")
        do {
            let result = try body()
            try execute("COMMIT")
            return result
        } catch {
            // The caller treats a failed batch as failed persistence and closes the connection.
            try? execute("ROLLBACK")
            throw error
        }
    }
    static func error(_ code: Int32) -> PersistenceError {
        switch code & 0xff {
        case SQLITE_BUSY, SQLITE_LOCKED: return .busy
        case SQLITE_FULL: return .storageFull
        case SQLITE_CORRUPT, SQLITE_NOTADB: return .corrupt
        case SQLITE_CONSTRAINT, SQLITE_MISMATCH: return .invalidRecord
        default: return .unavailable
        }
    }
}

/// Typed reads reject coercion and bound text before allocating a Swift string.
struct SQLiteStatement {
    let handle: OpaquePointer
    func step() throws -> Bool {
        let code = sqlite3_step(handle)
        if code == SQLITE_ROW { return true }
        if code == SQLITE_DONE { return false }
        throw SQLiteConnection.error(code)
    }
    func isNull(_ index: Int32) -> Bool { sqlite3_column_type(handle, index) == SQLITE_NULL }
    func integer(_ index: Int32) throws -> Int64 {
        guard sqlite3_column_type(handle, index) == SQLITE_INTEGER else { throw PersistenceError.invalidRecord }
        return sqlite3_column_int64(handle, index)
    }
    func flag(_ index: Int32) throws -> Bool {
        let value = try integer(index)
        guard value == 0 || value == 1 else { throw PersistenceError.invalidRecord }
        return value == 1
    }
    func text(_ index: Int32, byteLimit: Int) throws -> String {
        guard sqlite3_column_type(handle, index) == SQLITE_TEXT else { throw PersistenceError.invalidRecord }
        let count = Int(sqlite3_column_bytes(handle, index))
        guard count <= byteLimit else { throw PersistenceError.invalidRecord }
        if count == 0 { return "" }
        guard let pointer = sqlite3_column_text(handle, index),
              let text = String(bytes: UnsafeBufferPointer(start: pointer, count: count), encoding: .utf8) else {
            throw PersistenceError.invalidRecord
        }
        return text
    }
}

import Foundation
import OsservioCore

/// Shared recovery limits, ownership checks, and transaction policy for private evidence databases.
enum EvidenceSQLite {
    static func open(url: URL, byteLimit: Int, applicationID: Int64, revision: Int64, schema: [String],
                     migrations: [Int64: [String]] = [:]) throws -> SQLiteConnection {
        for (file, limit) in [(url, Int64(byteLimit)),
                              (URL(fileURLWithPath: url.path + "-journal"), 2 * Int64(byteLimit) + 65_536)]
            where FileManager.default.fileExists(atPath: file.path) {
            let attributes = try FileManager.default.attributesOfItem(atPath: file.path)
            guard attributes[.type] as? FileAttributeType == .typeRegular,
                  let bytes = attributes[.size] as? NSNumber, bytes.int64Value <= limit else { throw PersistenceError.storageFull }
        }
        let connection = try SQLiteConnection(url: url)
        func scalar(_ sql: String) throws -> Int64 {
            try connection.statement(sql) { row in
                guard try row.step() else { throw PersistenceError.corrupt }
                return try row.integer(0)
            }
        }
        do {
            let owner = try scalar("PRAGMA application_id"), version = try scalar("PRAGMA user_version")
            let empty = try scalar("SELECT count(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'") == 0
            let create = owner == 0 && version == 0 && empty
            let canMigrate = owner == applicationID && version > 0 && version < revision
                && (version..<revision).allSatisfy { migrations[$0] != nil }
            guard create || (owner == applicationID && (version == revision || canMigrate)) else { throw PersistenceError.unsupportedSchema }
            try connection.statement("PRAGMA journal_mode=DELETE") { row in
                guard try row.step(), try row.text(0, byteLimit: 16) == "delete" else { throw PersistenceError.unavailable }
            }
            try connection.execute("PRAGMA synchronous=FULL")
            try connection.execute("PRAGMA foreign_keys=ON")
            try connection.execute("PRAGMA temp_store=MEMORY")
            try connection.execute("PRAGMA cache_size=-256")
            let pageSize = try scalar("PRAGMA page_size")
            guard (512...65_536).contains(pageSize) else { throw PersistenceError.corrupt }
            let pages = Int64(byteLimit) / pageSize
            guard try scalar("PRAGMA max_page_count=\(pages)") <= pages else { throw PersistenceError.storageFull }
            if create {
                try connection.transaction {
                    for sql in schema { try connection.execute(sql) }
                    try connection.execute("PRAGMA application_id=\(applicationID)")
                    try connection.execute("PRAGMA user_version=\(revision)")
                }
            } else if canMigrate {
                // Schema changes and revision advancement commit together; any failure preserves the old revision.
                try connection.transaction {
                    for previous in version..<revision {
                        guard let statements = migrations[previous] else { throw PersistenceError.unsupportedSchema }
                        for sql in statements { try connection.execute(sql) }
                    }
                    try connection.execute("PRAGMA user_version=\(revision)")
                }
            }
            return connection
        } catch { connection.close(); throw error }
    }
}

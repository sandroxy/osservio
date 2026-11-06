import Foundation
import OsservioCore

/// Worker-owned Android address evidence. The enclosing incident transaction owns writes and retention.
final class TombstoneDatabase {
    private let connection: SQLiteConnection
    init(connection: SQLiteConnection) { self.connection = connection }
    static let schema = [
        "ALTER TABLE incident_diagnostics ADD COLUMN tombstone_state TEXT",
        """
        CREATE TABLE android_tombstones (incident_id TEXT PRIMARY KEY NOT NULL REFERENCES incident_diagnostics(incident_id) ON DELETE CASCADE,
            architecture INTEGER NOT NULL, signal_number INTEGER NOT NULL, signal_code INTEGER NOT NULL, thread_id INTEGER NOT NULL,
            fault_address TEXT, frame_count INTEGER NOT NULL)
        """,
        """
        CREATE TABLE android_tombstone_frames (incident_id TEXT NOT NULL REFERENCES android_tombstones(incident_id) ON DELETE CASCADE,
            position INTEGER NOT NULL, pc TEXT NOT NULL, relative_pc TEXT NOT NULL, build_id TEXT, PRIMARY KEY(incident_id, position))
        """
    ]
    func save(_ value: AndroidTombstone, id: String) throws {
        try connection.execute("""
            INSERT INTO android_tombstones(incident_id,architecture,signal_number,signal_code,thread_id,fault_address,frame_count) VALUES (?,?,?,?,?,?,?)
            """, [.text(id), .integer(Int64(value.architectureCode)), .integer(Int64(value.signalNumber)), .integer(Int64(value.signalCode)),
                  .integer(Int64(value.threadID)), value.faultAddress.map { .text(String($0)) } ?? .null, .integer(Int64(value.frames.count))])
        for (index, frame) in value.frames.enumerated() {
            try connection.execute("INSERT INTO android_tombstone_frames(incident_id,position,pc,relative_pc,build_id) VALUES (?,?,?,?,?)",
                [.text(id), .integer(Int64(index)), .text(String(frame.programCounter)), .text(String(frame.relativeProgramCounter)),
                 frame.buildID.map(SQLiteValue.text) ?? .null])
        }
    }
    func requireAbsent(id: String) throws {
        for table in ["android_tombstones", "android_tombstone_frames"] {
            try connection.statement("SELECT 1 FROM \(table) WHERE incident_id=? LIMIT 1", [.text(id)]) { row in
                guard try !row.step() else { throw PersistenceError.invalidRecord }
            }
        }
    }
    func read(id: String) throws -> AndroidTombstone {
        try connection.statement("""
            SELECT architecture,signal_number,signal_code,thread_id,fault_address,frame_count
            FROM android_tombstones WHERE incident_id=? LIMIT 2
            """, [.text(id)]) { row in
            guard try row.step() else { throw PersistenceError.invalidRecord }
            let count = try row.integer(5)
            guard (0...128).contains(count) else { throw PersistenceError.invalidRecord }
            let value = try AndroidTombstone.restore(architectureCode: int32(row, 0), signalNumber: int32(row, 1),
                signalCode: int32(row, 2), threadID: int32(row, 3), faultAddress: row.isNull(4) ? nil : unsigned(row, 4),
                frames: readFrames(id: id, expected: Int(count)))
            guard try !row.step() else { throw PersistenceError.invalidRecord }
            return value
        }
    }
    private func readFrames(id: String, expected: Int) throws -> [AndroidTombstone.Frame] {
        try connection.statement("SELECT position,pc,relative_pc,build_id FROM android_tombstone_frames WHERE incident_id=? ORDER BY position LIMIT 129", [.text(id)]) { row in
            var frames: [AndroidTombstone.Frame] = []
            while try row.step() {
                guard frames.count < expected, try row.integer(0) == frames.count else { throw PersistenceError.invalidRecord }
                frames.append(try .restore(programCounter: unsigned(row, 1), relativeProgramCounter: unsigned(row, 2),
                    buildID: row.isNull(3) ? nil : row.text(3, byteLimit: 128)))
            }
            guard frames.count == expected else { throw PersistenceError.invalidRecord }
            return frames
        }
    }
    private func unsigned(_ row: SQLiteStatement, _ column: Int32) throws -> UInt64 {
        let text = try row.text(column, byteLimit: 20)
        guard let value = UInt64(text), String(value) == text else { throw PersistenceError.invalidRecord }
        return value
    }
    private func int32(_ row: SQLiteStatement, _ column: Int32) throws -> Int32 {
        guard let value = Int32(exactly: try row.integer(column)) else { throw PersistenceError.invalidRecord }
        return value
    }
}

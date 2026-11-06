import Foundation
import OsservioCore

/// Worker-owned normalized address evidence. The enclosing incident transaction owns all writes and retention.
final class SignalCrashDatabase {
    private let connection: SQLiteConnection
    init(connection: SQLiteConnection) { self.connection = connection }
    static let schema = [
        """
        CREATE TABLE signal_crashes (incident_id TEXT PRIMARY KEY NOT NULL REFERENCES incident_diagnostics(incident_id) ON DELETE CASCADE,
            report_id TEXT NOT NULL, signal_name TEXT NOT NULL, signal_code TEXT NOT NULL, fault_address TEXT NOT NULL,
            thread_number INTEGER, frame_count INTEGER NOT NULL, image_count INTEGER NOT NULL, image_cache_incomplete INTEGER NOT NULL)
        """,
        """
        CREATE TABLE signal_crash_frames (incident_id TEXT NOT NULL REFERENCES signal_crashes(incident_id) ON DELETE CASCADE,
            position INTEGER NOT NULL, address TEXT NOT NULL, PRIMARY KEY(incident_id, position))
        """,
        """
        CREATE TABLE signal_crash_images (incident_id TEXT NOT NULL REFERENCES signal_crashes(incident_id) ON DELETE CASCADE,
            position INTEGER NOT NULL, base_address TEXT NOT NULL, size TEXT NOT NULL, uuid TEXT, cpu_type TEXT, cpu_subtype TEXT,
            PRIMARY KEY(incident_id, position))
        """
    ]
    func save(_ crash: AppleSignalCrash, id: String) throws {
        try connection.execute("""
            INSERT INTO signal_crashes(incident_id,report_id,signal_name,signal_code,fault_address,thread_number,frame_count,image_count,image_cache_incomplete)
            VALUES (?,?,?,?,?,?,?,?,?)
            """, [.text(id), .text(crash.reportID), .text(crash.signal.rawValue), .text(crash.code), .text(String(crash.faultAddress)),
                    crash.threadNumber.map { .integer(Int64($0)) } ?? .null, .integer(Int64(crash.frames.count)),
                    .integer(Int64(crash.images.count)), .integer(crash.imageCacheIncomplete ? 1 : 0)])
        for (position, address) in crash.frames.enumerated() {
            try connection.execute("INSERT INTO signal_crash_frames(incident_id,position,address) VALUES (?,?,?)",
                [.text(id), .integer(Int64(position)), .text(String(address))])
        }
        for (position, image) in crash.images.enumerated() {
            try connection.execute("INSERT INTO signal_crash_images(incident_id,position,base_address,size,uuid,cpu_type,cpu_subtype) VALUES (?,?,?,?,?,?,?)",
                [.text(id), .integer(Int64(position)), .text(String(image.baseAddress)), .text(String(image.size)),
                 image.uuid.map(SQLiteValue.text) ?? .null, image.cpuType.map { .text(String($0)) } ?? .null,
                 image.cpuSubtype.map { .text(String($0)) } ?? .null])
        }
    }
    func requireAbsent(id: String) throws {
        for table in ["signal_crashes", "signal_crash_frames", "signal_crash_images"] {
            try connection.statement("SELECT 1 FROM \(table) WHERE incident_id=? LIMIT 1", [.text(id)]) { row in
                guard try !row.step() else { throw PersistenceError.invalidRecord }
            }
        }
    }
    func read(id: String, timestampMillis: Int64, metadata: [SystemDiagnostic.MetadataKey: SystemDiagnostic.Text]) throws -> AppleSignalCrash {
        try connection.statement("""
            SELECT report_id,signal_name,signal_code,fault_address,thread_number,frame_count,image_count,image_cache_incomplete
            FROM signal_crashes WHERE incident_id=? LIMIT 2
            """, [.text(id)]) { row in
            guard try row.step(), let signal = AppleSignalCrash.Signal(rawValue: try row.text(1, byteLimit: 16)) else {
                throw PersistenceError.invalidRecord
            }
            let frameCount = try row.integer(5), imageCount = try row.integer(6)
            guard (0...128).contains(frameCount), (0...512).contains(imageCount) else { throw PersistenceError.invalidRecord }
            let thread: UInt32?
            if row.isNull(4) { thread = nil }
            else {
                guard let number = UInt32(exactly: try row.integer(4)) else { throw PersistenceError.invalidRecord }
                thread = number
            }
            let result = try AppleSignalCrash.restore(reportID: row.text(0, byteLimit: 36), timestampMillis: timestampMillis,
                signal: signal, code: row.text(2, byteLimit: 16), faultAddress: unsigned(row, 3), threadNumber: thread,
                frames: readFrames(id: id, expected: Int(frameCount)), images: readImages(id: id, expected: Int(imageCount)),
                metadata: metadata, imageCacheIncomplete: row.flag(7))
            guard try !row.step() else { throw PersistenceError.invalidRecord }
            return result
        }
    }
    private func readFrames(id: String, expected: Int) throws -> [UInt64] {
        try connection.statement("SELECT position,address FROM signal_crash_frames WHERE incident_id=? ORDER BY position LIMIT 129", [.text(id)]) { row in
            var frames: [UInt64] = []
            while try row.step() {
                guard frames.count < expected, try row.integer(0) == frames.count else { throw PersistenceError.invalidRecord }
                frames.append(try unsigned(row, 1))
            }
            guard frames.count == expected else { throw PersistenceError.invalidRecord }
            return frames
        }
    }
    private func readImages(id: String, expected: Int) throws -> [AppleSignalCrash.Image] {
        try connection.statement("""
            SELECT position,base_address,size,uuid,cpu_type,cpu_subtype FROM signal_crash_images
            WHERE incident_id=? ORDER BY position LIMIT 513
            """, [.text(id)]) { row in
            var images: [AppleSignalCrash.Image] = []
            while try row.step() {
                guard images.count < expected, try row.integer(0) == images.count else { throw PersistenceError.invalidRecord }
                images.append(try .restore(baseAddress: unsigned(row, 1), size: unsigned(row, 2),
                    uuid: row.isNull(3) ? nil : row.text(3, byteLimit: 32),
                    cpuType: row.isNull(4) ? nil : unsigned(row, 4), cpuSubtype: row.isNull(5) ? nil : unsigned(row, 5)))
            }
            guard images.count == expected else { throw PersistenceError.invalidRecord }
            return images
        }
    }
    private func unsigned(_ row: SQLiteStatement, _ column: Int32) throws -> UInt64 {
        let text = try row.text(column, byteLimit: 20)
        guard let value = UInt64(text), String(value) == text else { throw PersistenceError.invalidRecord }
        return value
    }
}

import Darwin
import Foundation
import OsservioCore

/// Worker-owned private pending files. No operation in this type runs in a fatal handler.
final class CrashReportDirectory {
    static let reportName = "live_report.plcrash"
    static let maximumReportBytes = 65_536
    static let maximumAgeMillis: Int64 = 604_800_000
    private static let marker = Data("Osservio crash files/1\n".utf8)
    private let directoryURL: URL
    var activeDirectory: URL? { active.map { directoryURL.appendingPathComponent("slot-\($0)", isDirectory: true) } }
    private(set) var excludedCount: Int
    private(set) var capacityReached = false
    private let root: Int32
    private let lease: Int32
    private let slots: [Int32]
    private var active: Int?

    init(directory: URL, nowMillis: Int64) throws {
        guard directory.isFileURL, directory.path != "/", !directory.path.isEmpty,
              directory.path.utf8.prefix(4_097).count <= 4_096 else { throw PersistenceError.unavailable }
        let manager = FileManager.default
        var attributes: [FileAttributeKey: Any] = [.posixPermissions: 0o700]
        #if os(iOS)
        attributes[.protectionKey] = FileProtectionType.completeUntilFirstUserAuthentication
        #endif
        try manager.createDirectory(at: directory, withIntermediateDirectories: true, attributes: attributes)
        let descriptor = Darwin.open(directory.path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)
        guard descriptor >= 0 else { throw PersistenceError.unavailable }
        var acquiredLease: Int32 = -1, opened: [Int32] = []
        do {
            try Self.validate(descriptor, directory: true)
            let names = try Self.entries(descriptor, limit: 6)
            let known = Set(["format", "owner"] + (0..<4).map { "slot-\($0)" })
            guard names.isSubset(of: known), names.contains("format") || names.isSubset(of: ["owner"]) else {
                throw PersistenceError.unsupportedSchema
            }
            acquiredLease = openat(descriptor, "owner", O_CREAT | O_RDWR | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC, 0o600)
            guard acquiredLease >= 0 else { throw PersistenceError.unavailable }
            try Self.validate(acquiredLease, directory: false)
            var leaseInfo = stat()
            guard fstat(acquiredLease, &leaseInfo) == 0, leaseInfo.st_size == 0 else { throw PersistenceError.invalidRecord }
            guard flock(acquiredLease, LOCK_EX | LOCK_NB) == 0 else { throw PersistenceError.busy }
            if names.contains("format") {
                guard try Self.readFile(descriptor, name: "format", limit: Self.marker.count) == Self.marker else {
                    throw PersistenceError.unsupportedSchema
                }
            } else {
                let marker = openat(descriptor, "format", O_CREAT | O_EXCL | O_WRONLY | O_NOFOLLOW | O_CLOEXEC, 0o600)
                guard marker >= 0 else { throw PersistenceError.unavailable }
                defer { Darwin.close(marker) }
                let count = Self.marker.withUnsafeBytes { Darwin.write(marker, $0.baseAddress, $0.count) }
                guard count == Self.marker.count, fsync(marker) == 0 else { throw PersistenceError.unavailable }
            }
            var backupURL = directory
            var values = URLResourceValues(); values.isExcludedFromBackup = true
            try backupURL.setResourceValues(values)
            var times: [Int64?] = []
            for index in 0..<4 {
                let name = "slot-\(index)"
                if mkdirat(descriptor, name, 0o700) != 0 && errno != EEXIST { throw PersistenceError.unavailable }
                let slot = openat(descriptor, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)
                guard slot >= 0 else { throw PersistenceError.unavailable }
                opened.append(slot); try Self.validate(slot, directory: true)
                guard try Self.entries(slot, limit: 1).isSubset(of: [Self.reportName]) else { throw PersistenceError.unsupportedSchema }
                times.append(try Self.reportTime(slot))
            }
            // Validate every slot before any retention change. Unknown entries, links,
            // permissions and oversize files fail closed without deleting evidence.
            var excluded = 0
            let cutoff = nowMillis.subtractingReportingOverflow(Self.maximumAgeMillis)
            if !cutoff.overflow {
                for index in 0..<4 {
                    if let time = times[index], time < cutoff.partialValue {
                        try Self.removeReport(opened[index]); times[index] = nil; excluded += 1
                    }
                }
            }
            root = descriptor; lease = acquiredLease; slots = opened
            directoryURL = directory; excludedCount = excluded
        } catch {
            for slot in opened { Darwin.close(slot) }
            if acquiredLease >= 0 { Darwin.close(acquiredLease) }
            Darwin.close(descriptor)
            throw error
        }
    }

    /// Called only after installation preflight. Readers alone do not evict a
    /// fourth report to reserve a slot for a recorder that cannot be installed.
    func reserveForRecording() throws -> URL {
        if let activeDirectory { return activeDirectory }
        var times: [Int64?] = []
        for slot in slots {
            guard try Self.entries(slot, limit: 1).isSubset(of: [Self.reportName]) else { throw PersistenceError.unsupportedSchema }
            times.append(try Self.reportTime(slot))
        }
        let selected: Int
        if let empty = times.firstIndex(where: { $0 == nil }) { selected = empty }
        else {
            selected = (0..<4).min { times[$0]! < times[$1]! }!
            try Self.removeReport(slots[selected]); excludedCount += 1; capacityReached = true
        }
        active = selected
        return directoryURL.appendingPathComponent("slot-\(selected)", isDirectory: true)
    }

    func read(observedAtMillis: Int64) throws -> SystemDiagnosticBatch {
        var diagnostics: [SystemDiagnostic] = [], rejected = 0
        for index in 0..<4 where index != active {
            guard let data = try Self.readFile(slots[index], name: Self.reportName, limit: Self.maximumReportBytes) else { continue }
            do { diagnostics.append(.appleSignal(try CrashReportDecoder.decode(data))) }
            catch { rejected += 1 }
        }
        return try SystemDiagnosticBatch(source: .appleSignal, observedAtMillis: observedAtMillis,
            diagnostics: diagnostics, rejectedCount: rejected, excludedCount: excludedCount, sourceLimitReached: capacityReached)
    }

    deinit {
        for descriptor in slots { Darwin.close(descriptor) }
        Darwin.close(lease); Darwin.close(root)
    }

    private static func validate(_ descriptor: Int32, directory: Bool) throws {
        var value = stat()
        guard fstat(descriptor, &value) == 0 else { throw PersistenceError.unavailable }
        guard value.st_mode & S_IFMT == (directory ? S_IFDIR : S_IFREG), value.st_uid == geteuid(),
              value.st_mode & 0o077 == 0, directory || value.st_nlink == 1 else { throw PersistenceError.invalidRecord }
    }

    private static func reportTime(_ directory: Int32) throws -> Int64? {
        let descriptor = openat(directory, reportName, O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC)
        if descriptor < 0 {
            if errno == ENOENT { return nil }
            throw PersistenceError.unavailable
        }
        defer { Darwin.close(descriptor) }
        try validate(descriptor, directory: false)
        var value = stat()
        guard fstat(descriptor, &value) == 0 else { throw PersistenceError.unavailable }
        let millis = Int64(value.st_mtimespec.tv_sec).multipliedReportingOverflow(by: 1000)
        guard value.st_size >= 0, value.st_size <= maximumReportBytes, !millis.overflow,
              (0..<1_000_000_000).contains(value.st_mtimespec.tv_nsec) else { throw PersistenceError.invalidRecord }
        let timestamp = millis.partialValue.addingReportingOverflow(Int64(value.st_mtimespec.tv_nsec) / 1_000_000)
        guard !timestamp.overflow else { throw PersistenceError.invalidRecord }
        return timestamp.partialValue
    }

    private static func removeReport(_ directory: Int32) throws {
        guard unlinkat(directory, reportName, 0) == 0 else { throw PersistenceError.unavailable }
    }

    private static func readFile(_ directory: Int32, name: String, limit: Int) throws -> Data? {
        let descriptor = openat(directory, name, O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC)
        if descriptor < 0 {
            if errno == ENOENT { return nil }
            throw PersistenceError.unavailable
        }
        defer { Darwin.close(descriptor) }
        try validate(descriptor, directory: false)
        // Read at most limit + 1 even if a file grows after its metadata check.
        var bytes = [UInt8](repeating: 0, count: limit + 1), count = 0
        while count < bytes.count {
            let amount = bytes.withUnsafeMutableBytes { Darwin.read(descriptor, $0.baseAddress!.advanced(by: count), $0.count - count) }
            guard amount >= 0 else { throw PersistenceError.unavailable }
            if amount == 0 { break }
            count += amount
        }
        guard count <= limit else { throw PersistenceError.invalidRecord }
        return Data(bytes.prefix(count))
    }

    private static func entries(_ descriptor: Int32, limit: Int) throws -> Set<String> {
        let copied = dup(descriptor)
        guard copied >= 0 else { throw PersistenceError.unavailable }
        guard let directory = fdopendir(copied) else { Darwin.close(copied); throw PersistenceError.unavailable }
        defer { closedir(directory) }
        rewinddir(directory)
        var result = Set<String>()
        while true {
            errno = 0
            guard let entry = readdir(directory) else {
                guard errno == 0 else { throw PersistenceError.unavailable }; break
            }
            let count = Int(entry.pointee.d_namlen)
            let name = withUnsafePointer(to: &entry.pointee.d_name) {
                String(bytes: UnsafeBufferPointer(start: UnsafeRawPointer($0).assumingMemoryBound(to: UInt8.self), count: count), encoding: .utf8)
            }
            guard let name else { throw PersistenceError.invalidRecord }
            if name == "." || name == ".." { continue }
            guard result.count < limit else { throw PersistenceError.unsupportedSchema }
            result.insert(name)
        }
        return result
    }
}

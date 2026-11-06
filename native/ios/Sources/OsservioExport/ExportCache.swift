import Darwin
import Foundation
import OsservioCore

/// Worker-confined private cache. Unrecognized entries are preserved and make preparation fail.
final class ExportCache {
    let url: URL
    private let options: IncidentExportOptions
    private var descriptor: Int32 = -1
    private var directoryDevice: dev_t = 0
    private var directoryInode: ino_t = 0

    init(url: URL, options: IncidentExportOptions) throws {
        self.url = url; self.options = options
        var attributes: [FileAttributeKey: Any] = [.posixPermissions: 0o700]
        #if os(iOS)
        attributes[.protectionKey] = FileProtectionType.completeUntilFirstUserAuthentication
        #endif
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true, attributes: attributes)
        var info = stat()
        guard lstat(url.path, &info) == 0, info.st_mode & S_IFMT == S_IFDIR,
              info.st_uid == geteuid(), info.st_mode & 0o077 == 0 else { throw IncidentExportError.unavailable }
        directoryDevice = info.st_dev; directoryInode = info.st_ino
        #if os(iOS)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: url.path)
        #endif
        var directory = url, resources = URLResourceValues(); resources.isExcludedFromBackup = true
        try directory.setResourceValues(resources)
        descriptor = Darwin.open(url.appendingPathComponent("exports.owner").path, O_CREAT | O_RDWR | O_NOFOLLOW, 0o600)
        guard descriptor >= 0 else { throw IncidentExportError.unavailable }
        guard fstat(descriptor, &info) == 0, info.st_mode & S_IFMT == S_IFREG,
              info.st_uid == geteuid(), info.st_mode & 0o077 == 0, info.st_nlink == 1 else {
            close(); throw IncidentExportError.unavailable
        }
        guard flock(descriptor, LOCK_EX | LOCK_NB) == 0 else { close(); throw IncidentExportError.busy }
    }

    func newPartial() -> URL { url.appendingPathComponent("osservio-\(UUID().uuidString.lowercased()).partial") }

    /// At most 16 old files and one partial are produced by the SDK. The directory is dedicated to this cache.
    func clean(now: Int64, incomingBytes: Int64 = 0, preserving partial: URL? = nil) throws {
        guard incomingBytes <= options.cacheBytes else { throw IncidentExportError.tooLarge }
        var root = stat(), opened = stat(), named = stat()
        guard lstat(url.path, &root) == 0, root.st_mode & S_IFMT == S_IFDIR,
              root.st_dev == directoryDevice, root.st_ino == directoryInode, root.st_uid == geteuid(), root.st_mode & 0o077 == 0,
              fstat(descriptor, &opened) == 0, lstat(url.appendingPathComponent("exports.owner").path, &named) == 0,
              named.st_mode & S_IFMT == S_IFREG, named.st_uid == geteuid(), named.st_mode & 0o077 == 0, named.st_nlink == 1,
              named.st_dev == opened.st_dev, named.st_ino == opened.st_ino else { throw IncidentExportError.unavailable }
        var failed = false
        guard let entries = FileManager.default.enumerator(at: url, includingPropertiesForKeys: nil,
            options: [.skipsSubdirectoryDescendants], errorHandler: { _, _ in failed = true; return false }) else {
            throw IncidentExportError.unavailable
        }
        var files: [(url: URL, bytes: Int64, modified: Double, partial: Bool)] = []
        var count = 0
        while let item = entries.nextObject() {
            count += 1
            guard count <= 32, let file = item as? URL else { throw IncidentExportError.unavailable }
            let name = file.lastPathComponent
            if name == "exports.owner" { continue }
            let isPartial = name.hasSuffix(".partial"), suffix = isPartial ? ".partial" : ".zip"
            guard name.hasPrefix("osservio-"), name.hasSuffix(suffix), name.utf8.count == 9 + 36 + suffix.utf8.count,
                  UUID(uuidString: String(name.dropFirst(9).dropLast(suffix.count))) != nil else { throw IncidentExportError.unavailable }
            var info = stat()
            guard lstat(file.path, &info) == 0, info.st_mode & S_IFMT == S_IFREG,
                  info.st_uid == geteuid(), info.st_mode & 0o077 == 0, info.st_nlink == 1, info.st_size >= 0 else {
                throw IncidentExportError.unavailable
            }
            files.append((file, info.st_size, Double(info.st_mtimespec.tv_sec) * 1_000 + Double(info.st_mtimespec.tv_nsec) / 1_000_000, isPartial))
        }
        guard !failed else { throw IncidentExportError.unavailable }
        var retained: [(url: URL, bytes: Int64, modified: Double, partial: Bool)] = []
        for file in files {
            // Foundation can enumerate /private/var while generated URLs use /var. This is a
            // validated flat directory; the internally generated partial has one unique filename.
            if file.url.lastPathComponent == partial?.lastPathComponent { continue }
            if file.partial || file.bytes == 0 || file.bytes > IncidentExportOptions.maximumFileBytes
                || Double(now) - file.modified >= Double(options.maximumAgeMillis) {
                try FileManager.default.removeItem(at: file.url)
            } else { retained.append(file) }
        }
        retained.sort { $0.modified == $1.modified ? $0.url.lastPathComponent < $1.url.lastPathComponent : $0.modified < $1.modified }
        var bytes = retained.reduce(Int64(0)) { $0 + $1.bytes }
        let incomingCount = incomingBytes == 0 ? 0 : 1
        while retained.count + incomingCount > options.fileCount || bytes + incomingBytes > options.cacheBytes {
            let file = retained.removeFirst()
            try FileManager.default.removeItem(at: file.url); bytes -= file.bytes
        }
    }

    func commit(_ partial: URL, bytes: Int64, now: Int64) throws -> IncidentExportFile {
        try clean(now: now, incomingBytes: bytes, preserving: partial)
        let final = partial.deletingPathExtension().appendingPathExtension("zip")
        try FileManager.default.moveItem(at: partial, to: final)
        return try IncidentExportFile(url: final, byteCount: bytes)
    }
    func close() {
        if descriptor >= 0 { flock(descriptor, LOCK_UN); Darwin.close(descriptor); descriptor = -1 }
    }
    deinit { close() }
}

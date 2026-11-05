import Darwin
import Foundation
import OsservioCore

/// Private SDK directory and an exclusive process/instance lease. All operations run on the storage worker.
final class EvidenceDirectory {
    enum Contents: String { case breadcrumbs, incidents }
    let databaseURL: URL
    private var descriptor: Int32 = -1
    init(url: URL, contents: Contents = .breadcrumbs) throws {
        let manager = FileManager.default
        var attributes: [FileAttributeKey: Any] = [.posixPermissions: 0o700]
        #if os(iOS)
        attributes[.protectionKey] = FileProtectionType.completeUntilFirstUserAuthentication
        #endif
        try manager.createDirectory(at: url, withIntermediateDirectories: true, attributes: attributes)
        let actual = try manager.attributesOfItem(atPath: url.path)
        guard actual[.type] as? FileAttributeType == .typeDirectory,
              let permissions = actual[.posixPermissions] as? NSNumber, permissions.intValue & 0o077 == 0 else {
            throw PersistenceError.unavailable
        }
        #if os(iOS)
        try manager.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: url.path)
        #endif
        var directory = url
        var resources = URLResourceValues(); resources.isExcludedFromBackup = true
        try directory.setResourceValues(resources)
        databaseURL = url.appendingPathComponent(contents.rawValue + ".sqlite")
        let lease = url.appendingPathComponent(contents.rawValue + ".owner")
        descriptor = Darwin.open(lease.path, O_CREAT | O_RDWR | O_NOFOLLOW, 0o600)
        guard descriptor >= 0 else { throw PersistenceError.unavailable }
        guard flock(descriptor, LOCK_EX | LOCK_NB) == 0 else {
            Darwin.close(descriptor); descriptor = -1
            throw PersistenceError.busy
        }
    }
    func close() {
        if descriptor >= 0 { flock(descriptor, LOCK_UN); Darwin.close(descriptor); descriptor = -1 }
    }
    deinit { close() }
}

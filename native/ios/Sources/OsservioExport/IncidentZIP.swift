import Foundation
import OsservioCore
import ZIPFoundation

/// Uses a maintained ZIP implementation; only two fixed entries and already bounded documents are admitted.
enum IncidentZIP {
    static func write(_ documents: IncidentDocuments, to url: URL, task: ExportTask) throws -> Int64 {
        let contents = [("incident.json", documents.metadataJSON), ("payloads.json", documents.payloadsJSON)]
        try create(contents, at: url, task: task)
        try task.check()
        let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        guard let size = attributes[.size] as? NSNumber, size.int64Value > 0,
              size.int64Value <= IncidentExportOptions.maximumFileBytes else { throw IncidentExportError.tooLarge }
        // Reopen after the writer closes. ZIPFoundation's final fflush/fclose does not surface every
        // filesystem error; readback verifies entry sizes, content, and CRC before exposing a file.
        let archive = try Archive(url: url, accessMode: .read)
        guard Array(archive).count == 2 else { throw IncidentExportError.unavailable }
        for (name, data) in contents {
            guard let entry = archive[name], entry.type == .file, entry.uncompressedSize == Int64(data.count) else {
                throw IncidentExportError.unavailable
            }
            var offset = 0
            let checksum = try archive.extract(entry, bufferSize: 16_384) { chunk in
                try task.check()
                guard chunk.count <= data.count - offset, chunk == data.subdata(in: offset..<(offset + chunk.count)) else {
                    throw IncidentExportError.unavailable
                }
                offset += chunk.count
            }
            guard offset == data.count, checksum == entry.checksum else { throw IncidentExportError.unavailable }
        }
        return size.int64Value
    }
    private static func create(_ contents: [(String, Data)], at url: URL, task: ExportTask) throws {
        let archive = try Archive(url: url, accessMode: .create)
        var attributes: [FileAttributeKey: Any] = [.posixPermissions: 0o600]
        #if os(iOS)
        attributes[.protectionKey] = FileProtectionType.completeUntilFirstUserAuthentication
        #endif
        try FileManager.default.setAttributes(attributes, ofItemAtPath: url.path)
        for (name, data) in contents {
            try task.check()
            try archive.addEntry(with: name, type: .file, uncompressedSize: Int64(data.count),
                modificationDate: Date(timeIntervalSince1970: 978_307_200), permissions: 0o600,
                compressionMethod: .deflate, bufferSize: 16_384) { position, size in
                try task.check()
                return data.subdata(in: Int(position)..<(Int(position) + size))
            }
        }
    }
}

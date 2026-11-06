import CryptoKit
import Foundation
import OsservioCore

/// Private acknowledgement format, independent of incident identity and portable export revisions.
enum DiagnosticFingerprint {
    static func make(_ value: SystemDiagnostic) -> String {
        let exit = value.androidExit, metric = value.metricKit
        var fields: [String?] = [value.source.rawValue, value.kind.rawValue, String(value.intervalStartMillis), String(value.intervalEndMillis),
            exit.map { String($0.reasonCode) }, exit.map { String($0.status) }, exit.map { String($0.processID) }, exit.map { String($0.importance) },
            exit?.pssKiB.map(String.init), exit?.rssKiB.map(String.init), metric?.signal.map(String.init),
            metric?.exceptionType.map(String.init), metric?.exceptionCode, metric?.hangDurationMillis.map(String.init)]
        for key in SystemDiagnostic.MetadataKey.allCases {
            fields.append(metric?.metadata[key]?.value)
            fields.append(metric?.metadata[key].map { $0.isTruncated ? "1" : "0" })
        }
        var digest = SHA256()
        digest.update(data: Data("osservio.system-diagnostic/1\n".utf8))
        for field in fields {
            if let field {
                let bytes = Data(field.utf8)
                digest.update(data: Data("\(bytes.count):".utf8)); digest.update(data: bytes)
                digest.update(data: Data([10]))
            } else { digest.update(data: Data("-\n".utf8)) }
        }
        return digest.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

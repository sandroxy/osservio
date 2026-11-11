import Foundation

/// Checks persisted evidence without repairing it or reclassifying raw input as public.
enum EvidenceValidation {
    static func id(_ value: String) -> Bool {
        value.utf8.prefix(37).count == 36 && UUID(uuidString: value) != nil
    }
    static func text(_ value: String, limit: Int) -> Bool { value.utf8.prefix(limit + 1).count <= limit }
    static func label(_ value: String) -> Bool {
        let bytes = value.utf8.prefix(65)
        return !bytes.isEmpty && bytes.count <= 64 && bytes.allSatisfy {
            (65...90).contains($0) || (97...122).contains($0) || (48...57).contains($0) || [45, 46, 95].contains($0)
        }
    }
    static func url(_ value: String) -> Bool {
        if value == "[unavailable]" { return true }
        guard text(value, limit: 4_128),
              !value.unicodeScalars.contains(where: { $0.value < 0x20 || $0.value == 0x7F }),
              let parts = URLComponents(string: value), let scheme = parts.scheme?.lowercased(),
              ["http", "https", "ws", "wss"].contains(scheme), let host = parts.host, !host.isEmpty,
              host.utf8.allSatisfy({ $0 < 128 }), parts.port == nil || (1...65_535).contains(parts.port!) else { return false }
        return true
    }
    static func method(_ value: String) -> Bool { token(value, limit: 16) }
    static func headerName(_ value: String) -> Bool { token(value, limit: 64) }
    private static func token(_ value: String, limit: Int) -> Bool {
        let bytes = value.utf8.prefix(limit + 1)
        return !bytes.isEmpty && bytes.count <= limit && bytes.allSatisfy {
            (65...90).contains($0) || (97...122).contains($0) || (48...57).contains($0)
                || [33, 35, 36, 37, 38, 39, 42, 43, 45, 46, 94, 95, 96, 124, 126].contains($0)
        }
    }
    static func headers(_ values: [HTTPHeader]) -> Bool {
        values.count <= 32 && values.allSatisfy {
            headerName($0.name) && $0.name == $0.name.lowercased() && text($0.value, limit: 16_384)
        }
    }
    static func httpBodyID(_ value: String) -> Bool {
        guard value.utf8.prefix(46).count <= 45,
              value.hasSuffix(".request") || value.hasSuffix(".response") else { return false }
        let identifier = String(value.prefix(36))
        return id(identifier) && (value == identifier + ".request" || value == identifier + ".response")
    }
}

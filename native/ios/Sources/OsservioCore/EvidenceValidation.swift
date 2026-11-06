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
        // Capture limits raw URLs to 4,096 bytes; redaction can add a short placeholder.
        guard text(value, limit: 4_128),
              !value.unicodeScalars.contains(where: { $0.value < 0x20 || $0.value == 0x7F }),
              let parts = URLComponents(string: value), let scheme = parts.scheme,
              ["http", "https", "ws", "wss"].contains(scheme), let host = parts.host, !host.isEmpty,
              host.utf8.allSatisfy({ $0 < 128 }), parts.user == nil, parts.password == nil,
              parts.fragment == nil, parts.port == nil || (1...65_535).contains(parts.port!) else { return false }
        if let query = value.firstIndex(of: "?") { return value[value.index(after: query)...] == "[redacted]" }
        return true
    }
    static func headers(_ values: [HTTPHeader]) -> Bool {
        guard values.count <= 32 else { return false }
        let secretNames: Set<String> = ["authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key"]
        return values.allSatisfy { header in
            let name = header.name.utf8.prefix(65)
            return !name.isEmpty && name.count <= 64
                && name.allSatisfy({ (97...122).contains($0) || (48...57).contains($0) || $0 == 45 })
                && text(header.value, limit: 512)
                && (!secretNames.contains(header.name) || header.value == "[redacted]")
        }
    }
    static func httpBodyID(_ value: String) -> Bool {
        guard value.utf8.prefix(46).count <= 45,
              value.hasSuffix(".request") || value.hasSuffix(".response") else { return false }
        let identifier = String(value.prefix(36))
        return id(identifier) && (value == identifier + ".request" || value == identifier + ".response")
    }
}

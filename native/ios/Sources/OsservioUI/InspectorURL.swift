import Foundation

/// A display-only projection. Never use a decoded label as a request or export URL.
struct InspectorURL {
    let origin: String
    let path: String
    let queryHidden: Bool
    let pathHidden: Bool
    let available: Bool

    init(_ source: String) {
        guard let parts = URLComponents(string: source), let host = parts.host,
              let scheme = parts.scheme, !host.isEmpty else {
            origin = ""; path = source; queryHidden = false; pathHidden = false; available = false; return
        }
        let port = parts.port
        let defaultPort = ["https", "wss"].contains(scheme.lowercased()) ? 443 : 80
        let hostname = (host.contains(":") && !host.hasPrefix("[") ? "[" + host + "]" : host).lowercased()
        origin = hostname + (port != nil && port != defaultPort ? ":\(port!)" : "")
        let rawPath = parts.percentEncodedPath.isEmpty ? "/" : parts.percentEncodedPath
        pathHidden = Self.mask(rawPath) == "/[redacted]"
        queryHidden = parts.percentEncodedQuery.map { Self.mask($0) == "[redacted]" } ?? false
        path = Self.readable(rawPath) + (queryHidden ? "" : parts.percentEncodedQuery.map { "?" + Self.readable($0) } ?? "")
        available = true
    }

    private static func mask(_ text: String) -> String {
        text.replacingOccurrences(of: "%5B", with: "[", options: .caseInsensitive)
            .replacingOccurrences(of: "%5D", with: "]", options: .caseInsensitive)
    }

    /// Decode unreserved ASCII and visible UTF-8 only. Encoded separators, percent
    /// signs, controls and format characters keep their spelling; there is no second pass.
    static func readable(_ text: String) -> String {
        let bytes = Array(text.utf8)
        var output: [UInt8] = []; output.reserveCapacity(bytes.count)
        var index = 0
        func hex(_ byte: UInt8) -> UInt8? {
            if (48...57).contains(byte) { return byte - 48 }
            if (65...70).contains(byte) { return byte - 55 }
            if (97...102).contains(byte) { return byte - 87 }
            return nil
        }
        func escaped(_ offset: Int) -> UInt8? {
            guard offset + 2 < bytes.count, bytes[offset] == 37,
                  let high = hex(bytes[offset + 1]), let low = hex(bytes[offset + 2]) else { return nil }
            return high * 16 + low
        }
        while index < bytes.count {
            guard let first = escaped(index) else { output.append(bytes[index]); index += 1; continue }
            if (65...90).contains(first) || (97...122).contains(first) || (48...57).contains(first) || [45, 46, 95, 126].contains(first) {
                output.append(first); index += 3; continue
            }
            let length = (0xC2...0xDF).contains(first) ? 2 : (0xE0...0xEF).contains(first) ? 3 : (0xF0...0xF4).contains(first) ? 4 : 0
            if length > 0 {
                let sequence = (0..<length).compactMap { escaped(index + $0 * 3) }
                if sequence.count == length, let value = String(bytes: sequence, encoding: .utf8), value.unicodeScalars.count == 1,
                   let scalar = value.unicodeScalars.first,
                   ![.control, .format, .lineSeparator, .paragraphSeparator, .surrogate, .unassigned].contains(scalar.properties.generalCategory) {
                    output += sequence; index += length * 3; continue
                }
            }
            output += bytes[index..<index + 3]; index += 3
        }
        return String(decoding: output, as: UTF8.self)
    }
}

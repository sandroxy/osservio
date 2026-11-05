import Foundation

public enum HTTPBodyPolicy: Sendable {
    case omit
    /// The host has reviewed every body handled by this recorder as safe to retain and display.
    case publicUTF8
}

public struct HTTPPrivacyPolicy: Sendable {
    public let publicURLPath: Bool
    public let publicHeaderNames: Set<String>
    public let bodyPolicy: HTTPBodyPolicy
    public let bodyByteLimit: Int

    public init(publicURLPath: Bool = false, publicHeaderNames: Set<String> = [],
                bodyPolicy: HTTPBodyPolicy = .omit, bodyByteLimit: Int = 16_384) throws {
        guard publicHeaderNames.count <= 16, (1...65_536).contains(bodyByteLimit) else {
            throw CaptureConfigurationError.invalidLimit
        }
        var names = Set<String>()
        for name in publicHeaderNames {
            guard Self.validHeaderName(name), !Self.secretHeaders.contains(name.lowercased()) else {
                throw CaptureConfigurationError.invalidLimit
            }
            names.insert(name.lowercased())
        }
        self.publicURLPath = publicURLPath; self.publicHeaderNames = names
        self.bodyPolicy = bodyPolicy; self.bodyByteLimit = bodyByteLimit
    }

    private static let secretHeaders: Set<String> = ["authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key"]
    private static let knownHeaders: Set<String> = secretHeaders.union([
        "content-type", "content-length", "content-encoding", "accept", "accept-encoding", "cache-control",
        "connection", "host", "user-agent", "location", "referer", "origin", "etag", "if-none-match"
    ])

    func url(_ raw: String) -> String {
        guard raw.utf8.prefix(4_097).count <= 4_096,
              !raw.unicodeScalars.contains(where: { $0.value < 0x20 || $0.value == 0x7F }),
              let parts = URLComponents(string: raw), let scheme = parts.scheme?.lowercased(),
              ["http", "https", "ws", "wss"].contains(scheme), let host = parts.host, !host.isEmpty,
              host.utf8.allSatisfy({ $0 < 128 }), parts.port == nil || (1...65_535).contains(parts.port!) else {
            return "[unavailable]"
        }
        let origin = scheme + "://" + host.lowercased() + (parts.port.map { ":\($0)" } ?? "")
        let path = publicURLPath ? (parts.percentEncodedPath.isEmpty ? "/" : parts.percentEncodedPath) : "/[redacted]"
        return origin + path + (parts.percentEncodedQuery == nil ? "" : "?[redacted]")
    }

    func method(_ raw: String) -> String {
        guard raw.utf8.prefix(17).count <= 16 else { return "OTHER" }
        let value = raw.uppercased()
        return ["GET", "HEAD", "POST", "PUT", "DELETE", "CONNECT", "OPTIONS", "TRACE", "PATCH"].contains(value) ? value : "OTHER"
    }

    func headers(_ raw: [HTTPHeader]) -> (values: [HTTPHeader], limited: Bool) {
        var result: [HTTPHeader] = []
        var limited = raw.count > 32
        for header in raw.prefix(32) {
            guard Self.validHeaderName(header.name) else { limited = true; continue }
            let name = header.name.lowercased()
            guard Self.knownHeaders.contains(name) || publicHeaderNames.contains(name) else { limited = true; continue }
            var value = "[redacted]"
            if name == "content-type" { value = Self.mediaType(header.value) ?? "[unavailable]" }
            else if name == "content-length", header.value.utf8.prefix(20).count <= 19,
                    header.value.utf8.allSatisfy({ (48...57).contains($0) }), let length = Int64(header.value), length >= 0 {
                value = String(length)
            } else if publicHeaderNames.contains(name) {
                value = Self.prefixText(header.value, limit: 512).text
                if header.value.utf8.prefix(513).count > 512 { limited = true }
            }
            result.append(HTTPHeader(name: name, value: value))
        }
        return (result, limited)
    }

    func supportsBody(_ headers: [HTTPHeader], decoded: Bool) -> Bool {
        guard bodyPolicy == .publicUTF8,
              let contentType = headers.prefix(32).first(where: { Self.validHeaderName($0.name) && $0.name.lowercased() == "content-type" }),
              let type = Self.mediaType(contentType.value) else { return false }
        let parameters = contentType.value.lowercased().split(separator: ";").dropFirst()
        for parameter in parameters {
            let pair = parameter.split(separator: "=", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespaces) }
            if pair.first == "charset" {
                guard pair.count == 2, ["utf-8", "us-ascii", "\"utf-8\"", "\"us-ascii\""].contains(pair[1]) else { return false }
            }
        }
        let encoding = headers.prefix(32).first(where: { Self.validHeaderName($0.name) && $0.name.lowercased() == "content-encoding" })?.value
        if !decoded, let encoding = encoding {
            guard encoding.utf8.prefix(9).count <= 8, encoding.lowercased() == "identity" else { return false }
        }
        return type.hasPrefix("text/") || type == "application/json" || type.hasSuffix("+json")
            || type == "application/xml" || type.hasSuffix("+xml") || type == "application/x-www-form-urlencoded"
    }

    static func mediaType(_ raw: String) -> String? {
        guard raw.utf8.prefix(257).count <= 256 else { return nil }
        let type = raw.split(separator: ";", maxSplits: 1).first.map(String.init)?.trimmingCharacters(in: .whitespaces).lowercased() ?? ""
        let parts = type.split(separator: "/", omittingEmptySubsequences: false)
        guard parts.count == 2, parts.allSatisfy({ !$0.isEmpty }),
              type.utf8.allSatisfy({ (97...122).contains($0) || (48...57).contains($0) || [45, 46, 43, 47].contains($0) }) else { return nil }
        return type
    }

    private static func validHeaderName(_ value: String) -> Bool {
        let bytes = value.utf8.prefix(65)
        return !bytes.isEmpty && bytes.count <= 64 && bytes.allSatisfy {
            (65...90).contains($0) || (97...122).contains($0) || (48...57).contains($0) || $0 == 45
        }
    }

    static func prefixText(_ raw: String, limit: Int) -> (text: String, truncated: Bool) {
        var bytes = Array(raw.utf8.prefix(limit + 1))
        let truncated = bytes.count > limit
        if truncated { bytes.removeLast() }
        for _ in 0..<4 {
            if let text = String(bytes: bytes, encoding: .utf8) { return (text, truncated) }
            if !bytes.isEmpty { bytes.removeLast() }
        }
        return ("", true)
    }
}

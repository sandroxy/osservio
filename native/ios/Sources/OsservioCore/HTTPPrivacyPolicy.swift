import Foundation

public enum HTTPBodyPolicy: Sendable {
    case omit
    /// Capture supported UTF-8 content within the configured budget and host text policy.
    case publicUTF8
}

public struct HTTPPrivacyPolicy: Sendable {
    public let publicURLPath: Bool
    public let publicURLQuery: Bool
    /// Nil includes every valid header. An explicit set selects values; other headers retain an omission marker.
    public let publicHeaderNames: Set<String>?
    public let bodyPolicy: HTTPBodyPolicy
    public let bodyByteLimit: Int
    public let headerValueByteLimit: Int
    public let textPolicy: DiagnosticTextPolicy

    public init(publicURLPath: Bool = true, publicHeaderNames: Set<String>? = nil,
                bodyPolicy: HTTPBodyPolicy = .publicUTF8, bodyByteLimit: Int = 16_384,
                publicURLQuery: Bool = true, headerValueByteLimit: Int = 4_096,
                textPolicy: DiagnosticTextPolicy = .unchanged) throws {
        guard (publicHeaderNames?.count ?? 0) <= 32, (1...65_536).contains(bodyByteLimit),
              (1...16_384).contains(headerValueByteLimit) else { throw CaptureConfigurationError.invalidLimit }
        var names: Set<String>?
        if let publicHeaderNames {
            names = []
            for name in publicHeaderNames {
                guard Self.validHeaderName(name) else { throw CaptureConfigurationError.invalidLimit }
                names?.insert(name.lowercased())
            }
        }
        self.publicURLPath = publicURLPath; self.publicURLQuery = publicURLQuery; self.publicHeaderNames = names
        self.bodyPolicy = bodyPolicy; self.bodyByteLimit = bodyByteLimit
        self.headerValueByteLimit = headerValueByteLimit; self.textPolicy = textPolicy
    }

    func url(_ raw: String) -> String {
        guard EvidenceValidation.text(raw, limit: 4_096), EvidenceValidation.url(raw),
              var parts = URLComponents(string: raw), raw != "[unavailable]" else { return "[unavailable]" }
        if !publicURLPath { parts.percentEncodedPath = "/%5Bredacted%5D" }
        if !publicURLQuery, parts.percentEncodedQuery != nil { parts.percentEncodedQuery = "%5Bredacted%5D" }
        let selected = publicURLPath && publicURLQuery ? raw : (parts.string ?? "[unavailable]")
        guard EvidenceValidation.text(selected, limit: 4_128), let result = textPolicy.apply(selected, field: .url, limit: 4_128), EvidenceValidation.url(result) else {
            return "[unavailable]"
        }
        return result
    }

    func method(_ raw: String) -> String { EvidenceValidation.method(raw) ? raw : "OTHER" }

    func headers(_ raw: [HTTPHeader]) -> (values: [HTTPHeader], limited: Bool) {
        var result: [HTTPHeader] = []
        var limited = raw.count > 32
        for header in raw.prefix(32) {
            guard Self.validHeaderName(header.name) else { limited = true; continue }
            let name = header.name.lowercased()
            let bounded = Self.prefixText(header.value, limit: headerValueByteLimit)
            limited = limited || bounded.truncated
            let permitted = publicHeaderNames.map { $0.contains(name) } ?? true
            let value = permitted ? textPolicy.apply(bounded.text, field: .header(name), truncated: bounded.truncated,
                                                    limit: headerValueByteLimit) : nil
            result.append(HTTPHeader(name: name, value: value ?? "[redacted]"))
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

    private static func validHeaderName(_ value: String) -> Bool { EvidenceValidation.headerName(value) }

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

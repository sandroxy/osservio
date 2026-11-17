import Foundation

/// Raw facts read synchronously only after capture admission. No error object or user-info map is retained.
public struct NetworkErrorInput: Sendable {
    public let domain: String?, code: String?, type: String?, message: String?
    public init(domain: String? = nil, code: String? = nil, type: String? = nil, message: String? = nil) {
        self.domain = domain; self.code = code; self.type = type; self.message = message
    }
}

/// Bounded, host-processed error facts; these supplement the portable transport failure category.
public struct NetworkError: Sendable {
    public let domain: String?, code: String?, type: String?, message: String?
    /// A field was truncated, malformed, or unavailable from the source.
    public let isLimited: Bool
    /// Host processing changed or omitted at least one field.
    public let isProcessed: Bool
    public let textByteCount: Int
    private init(domain: String?, code: String?, type: String?, message: String?, limited: Bool, processed: Bool) {
        self.domain = domain; self.code = code; self.type = type; self.message = message
        isLimited = limited; isProcessed = processed
        textByteCount = [domain, code, type, message].compactMap { $0 }.reduce(0) { $0 + $1.utf8.count }
    }
    static func capture(_ source: (() throws -> NetworkErrorInput?)?, policy: DiagnosticTextPolicy) -> NetworkError? {
        guard let source else { return nil }
        let input: NetworkErrorInput
        do { guard let value = try source() else { return nil }; input = value }
        catch { return NetworkError(domain: nil, code: nil, type: nil, message: nil, limited: true, processed: false) }
        let raw = [input.domain, input.code, input.type, input.message], limits = [256, 64, 256, 4096]
        let fields: [DiagnosticTextPolicy.Field] = [.networkErrorDomain, .networkErrorCode, .networkErrorType, .networkErrorMessage]
        var values = [String?](repeating: nil, count: 4), limited = false, processed = false
        for index in raw.indices {
            guard let text = raw[index] else { continue }
            guard let bounded = BoundedText.prefix(text, bytes: limits[index]) else { limited = true; continue }
            values[index] = policy.apply(bounded.text, field: fields[index], truncated: bounded.truncated, limit: limits[index])
            limited = limited || bounded.truncated; processed = processed || values[index] != bounded.text
        }
        guard limited || processed || values.contains(where: { $0 != nil }) else { return nil }
        return NetworkError(domain: values[0], code: values[1], type: values[2], message: values[3], limited: limited, processed: processed)
    }
    public static func restore(domain: String?, code: String?, type: String?, message: String?, isLimited: Bool, isProcessed: Bool) throws -> NetworkError {
        let values = [domain, code, type, message], limits = [256, 64, 256, 4096]
        guard zip(values, limits).allSatisfy({ value, limit in value.map { EvidenceValidation.text($0, limit: limit) } ?? true }),
              isLimited || isProcessed || values.contains(where: { $0 != nil }) else { throw PersistenceError.invalidRecord }
        return NetworkError(domain: domain, code: code, type: type, message: message, limited: isLimited, processed: isProcessed)
    }
}

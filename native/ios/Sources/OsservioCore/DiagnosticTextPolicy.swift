import Foundation

/// Host-owned processing of bounded diagnostic text before it reaches any history or snapshot.
/// Return nil to omit a value. Throwing or returning oversized text also omits it; raw text is never a fallback.
/// Callbacks run on the observing thread and must be fast, nonblocking, and free of I/O or capture reentry.
public struct DiagnosticTextPolicy: Sendable {
    public enum Field: Sendable, Equatable {
        case url, header(String), requestBody, responseBody, webSocketMessage
    }
    public struct Input: Sendable {
        public let field: Field
        public let text: String
        public let isTruncated: Bool
    }
    private let process: @Sendable (Input) throws -> String?
    public init(_ process: @escaping @Sendable (Input) throws -> String?) { self.process = process }
    public static let unchanged = DiagnosticTextPolicy { $0.text }

    func apply(_ text: String, field: Field, truncated: Bool = false, limit: Int) -> String? {
        do {
            guard let result = try process(Input(field: field, text: text, isTruncated: truncated)),
                  EvidenceValidation.text(result, limit: limit) else { return nil }
            return result
        } catch { return nil }
    }
}

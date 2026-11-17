import Foundation

/// Application close text, distinct from the numeric close code and transport outcome.
public struct WebSocketCloseReason: Sendable {
    public let text: String?
    public let isLimited: Bool, isProcessed: Bool
    public let textByteCount: Int
    private init(text: String?, limited: Bool, processed: Bool) {
        self.text = text; isLimited = limited; isProcessed = processed; textByteCount = text?.utf8.count ?? 0
    }
    /// The source copies at most the requested prefix from bytes the host already owns; never reads a stream.
    static func capture(_ source: ((Int) throws -> Data?)?, policy: DiagnosticTextPolicy) -> WebSocketCloseReason? {
        guard let source else { return nil }
        let input: Data
        do { guard let value = try source(513) else { return nil }; input = value }
        catch { return WebSocketCloseReason(text: nil, limited: true, processed: false) }
        guard input.count <= 513 else { return WebSocketCloseReason(text: nil, limited: true, processed: false) }
        let limited = input.count > 512
        let prefix = Data(input.prefix(512))
        let bytes = limited ? BoundedText.completeUTF8Prefix(prefix) : prefix
        guard let text = String(data: bytes, encoding: .utf8) else { return WebSocketCloseReason(text: nil, limited: true, processed: false) }
        let value = policy.apply(text, field: .webSocketCloseReason, truncated: limited, limit: 512)
        return WebSocketCloseReason(text: value, limited: limited, processed: value != text)
    }
    public static func restore(text: String?, isLimited: Bool, isProcessed: Bool) throws -> WebSocketCloseReason {
        guard text.map({ EvidenceValidation.text($0, limit: 512) }) ?? (isLimited || isProcessed) else { throw PersistenceError.invalidRecord }
        return WebSocketCloseReason(text: text, limited: isLimited, processed: isProcessed)
    }
}

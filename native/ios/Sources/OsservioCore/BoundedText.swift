import Foundation

/// Reads at most the limit plus one UTF-8 byte and preserves scalar boundaries.
enum BoundedText {
    static func prefix(_ value: String, bytes limit: Int) -> (text: String, truncated: Bool)? {
        var bytes = Array(value.utf8.prefix(limit + 1))
        guard bytes.count > limit else { return (String(decoding: bytes, as: UTF8.self), false) }
        bytes.removeLast()
        for _ in 0..<4 {
            if let text = String(bytes: bytes, encoding: .utf8) { return (text, true) }
            bytes.removeLast()
        }
        return nil
    }
}

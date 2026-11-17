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
    static func completeUTF8Prefix(_ bytes: Data) -> Data {
        guard !bytes.isEmpty else { return bytes }
        let values = Array(bytes.suffix(4))
        var index = values.count - 1
        while index > 0 && (values[index] & 0xC0) == 0x80 { index -= 1 }
        let first = values[index]
        let expected = (0xC2...0xDF).contains(first) ? 2 : (0xE0...0xEF).contains(first) ? 3 : (0xF0...0xF4).contains(first) ? 4 : 1
        let actual = values.count - index
        if actual > 1 {
            let second = values[index + 1]
            if (first == 0xE0 && second < 0xA0) || (first == 0xED && second > 0x9F)
                || (first == 0xF0 && second < 0x90) || (first == 0xF4 && second > 0x8F) { return bytes }
        }
        return expected > actual ? Data(bytes.dropLast(actual)) : bytes
    }
}

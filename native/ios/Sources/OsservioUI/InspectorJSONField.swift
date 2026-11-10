import Foundation

/// Exact scalar lookup in a bounded document. Numbers retain their original spelling.
/// A pointer with duplicate object members is ambiguous and is never guessed.
enum InspectorJSONField {
    enum Match { case yes, no, unavailable }
    static func components(_ pointer: String) -> [String]? {
        guard pointer.utf16.count <= 256, pointer.hasPrefix("/") else { return nil }
        let parts = pointer.dropFirst().split(separator: "/", omittingEmptySubsequences: false)
        guard parts.count <= 16 else { return nil }
        var decoded: [String] = []
        for part in parts {
            var value = "", index = part.startIndex
            while index < part.endIndex {
                let character = part[index]; index = part.index(after: index)
                if character != "~" { value.append(character); continue }
                guard index < part.endIndex else { return nil }
                let escape = part[index]; index = part.index(after: index)
                guard escape == "0" || escape == "1" else { return nil }
                value.append(escape == "0" ? "~" : "/")
            }
            decoded.append(value)
        }
        return decoded
    }
    static func matches(_ source: String, pointer: String, value: String, cancelled: @escaping () -> Bool) -> Match {
        guard let parts = components(pointer), let document = try? InspectorJSONDocument.parse(source, cancelled: cancelled) else { return .unavailable }
        var index = 0
        for part in parts {
            if cancelled() { return .unavailable }
            let node = document.nodes[index]
            switch node.kind {
            case .object:
                let children = node.children.filter { child in
                    guard let range = document.nodes[child].key else { return false }
                    return decode(document.source.substring(with: range)) == part
                }
                guard children.count <= 1 else { return .unavailable }
                guard let child = children.first else { return .no }; index = child
            case .array:
                guard !part.isEmpty, part == "0" || !part.hasPrefix("0"), part.allSatisfy({ $0 >= "0" && $0 <= "9" }),
                      let offset = Int(part), offset < node.children.count else { return .no }
                index = node.children[offset]
            default: return .no
            }
        }
        let node = document.nodes[index], original = document.original(index)
        if node.kind == .object || node.kind == .array { return .no }
        guard let scalar = node.kind == .string ? decode(original) : original else { return .unavailable }
        return scalar == value ? .yes : .no
    }
    private static func decode(_ token: String) -> String? {
        try? JSONSerialization.jsonObject(with: Data(token.utf8), options: .fragmentsAllowed) as? String
    }
}

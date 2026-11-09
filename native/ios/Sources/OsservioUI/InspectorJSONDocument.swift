import Foundation

/// A bounded lexical index into sanitized text. Tokens are never converted to numbers or maps.
/// This preserves large IDs, duplicate keys, member order, escapes and the exact copy source.
struct InspectorJSONDocument {
    static let maximumUnits = 65_536
    static let maximumNodes = 4_096
    static let maximumDepth = 48
    enum Failure: Error { case notJSON, invalid, limited, cancelled }
    enum Kind { case object, array, string, number, literal }
    struct Node {
        let kind: Kind
        let parent: Int?
        let depth: Int
        let key: NSRange?
        var range: NSRange
        var children: [Int] = []
        var expandable: Bool { !children.isEmpty }
    }
    struct Row {
        let node: Int
        let closing: Bool
        var identity: Int { closing ? -node - 1 : node }
    }
    let source: NSString
    let nodes: [Node]

    static func parse(_ text: String, cancelled: @escaping () -> Bool = { false }) throws -> Self {
        // Admission precedes the UTF-16 copy and all node allocation.
        let units = Array(text.utf16.prefix(maximumUnits + 1))
        guard units.count <= maximumUnits else { throw Failure.limited }
        var parser = Parser(units: units, cancelled: cancelled)
        parser.whitespace()
        guard parser.current == 123 || parser.current == 91 else { throw Failure.notJSON }
        _ = try parser.value(parent: nil, key: nil, depth: 0)
        parser.whitespace()
        guard parser.position == units.count else { throw Failure.invalid }
        return Self(source: text as NSString, nodes: parser.nodes)
    }

    func rows(expanded: Set<Int>) -> [Row] {
        var rows: [Row] = []
        func visit(_ index: Int) {
            rows.append(Row(node: index, closing: false))
            let node = nodes[index]
            if node.expandable && (index == 0 || expanded.contains(index)) {
                for child in node.children { visit(child) }
                rows.append(Row(node: index, closing: true))
            }
        }
        visit(0)
        return rows
    }

    func ancestors(of index: Int) -> Set<Int> {
        var result: Set<Int> = [], parent = nodes[index].parent
        while let current = parent { result.insert(current); parent = nodes[current].parent }
        return result
    }

    /// Finds the deepest value or key containing an original-text search hit.
    func node(at offset: Int) -> Int {
        var found = 0
        for (index, node) in nodes.enumerated() {
            if let key = node.key, NSLocationInRange(offset, key) { return index }
            if NSLocationInRange(offset, node.range) { found = index }
        }
        return found
    }

    func original(_ index: Int) -> String { source.substring(with: nodes[index].range) }
    func comma(_ row: Row, expanded: Set<Int>) -> String {
        let node = nodes[row.node]
        guard let parent = node.parent else { return "" }
        if !row.closing && node.expandable && expanded.contains(row.node) { return "" }
        return nodes[parent].children.last == row.node ? "" : ","
    }
    func key(_ index: Int) -> String {
        nodes[index].key.map { preview($0, limit: 96) + ": " } ?? ""
    }
    func token(_ row: Row, expanded: Set<Int>) -> String {
        let node = nodes[row.node]
        if row.closing { return node.kind == .object ? "}" : "]" }
        switch node.kind {
        case .object, .array:
            let open = node.kind == .object ? "{" : "[", close = node.kind == .object ? "}" : "]"
            if node.children.isEmpty { return open + close }
            if row.node == 0 || expanded.contains(row.node) { return open }
            var parts: [String] = []
            for child in node.children.prefix(2) {
                let value = nodes[child]
                let brief: String
                switch value.kind {
                case .object: brief = value.children.isEmpty ? "{}" : "{…}"
                case .array: brief = value.children.isEmpty ? "[]" : "[…]"
                default: brief = preview(value.range, limit: 48)
                }
                parts.append(key(child) + brief)
            }
            if node.children.count > 2 { parts.append("…") }
            return open + " " + parts.joined(separator: ", ") + " " + close
        default: return preview(node.range, limit: 256)
        }
    }
    private func preview(_ range: NSRange, limit: Int) -> String {
        guard range.length > limit else { return source.substring(with: range) }
        var length = limit
        let last = source.character(at: range.location + length - 1)
        if (0xD800...0xDBFF).contains(last) { length -= 1 }
        return source.substring(with: NSRange(location: range.location, length: length)) + "…"
    }

    private struct Parser {
        let units: [UInt16]
        let cancelled: () -> Bool
        var position = 0
        var nodes: [Node] = []
        var current: UInt16 { position < units.count ? units[position] : 0 }
        mutating func step() throws {
            position += 1
            if position & 255 == 0, cancelled() { throw Failure.cancelled }
        }
        mutating func whitespace() {
            while [9, 10, 13, 32].contains(current) { position += 1 }
        }
        mutating func take(_ unit: UInt16) throws {
            guard current == unit else { throw Failure.invalid }; try step()
        }
        mutating func value(parent: Int?, key: NSRange?, depth: Int) throws -> Int {
            guard !cancelled() else { throw Failure.cancelled }
            guard depth <= maximumDepth, nodes.count < maximumNodes else { throw Failure.limited }
            whitespace()
            let start = position, kind: Kind
            switch current {
            case 123: kind = .object
            case 91: kind = .array
            case 34: kind = .string
            case 45, 48...57: kind = .number
            case 116, 102, 110: kind = .literal
            default: throw Failure.invalid
            }
            let id = nodes.count
            nodes.append(Node(kind: kind, parent: parent, depth: depth, key: key, range: NSRange(location: start, length: 0)))
            switch kind {
            case .object, .array:
                let close: UInt16 = kind == .object ? 125 : 93
                try step(); whitespace()
                if current != close {
                    while true {
                        var member: NSRange?
                        if kind == .object { member = try string(); whitespace(); try take(58) }
                        let child = try value(parent: id, key: member, depth: depth + 1)
                        nodes[id].children.append(child); whitespace()
                        if current == close { break }
                        try take(44); whitespace()
                    }
                }
                try take(close)
            case .string: _ = try string()
            case .number: try number()
            case .literal:
                let word = current == 116 ? "true" : current == 102 ? "false" : "null"
                for unit in word.utf16 { try take(unit) }
            }
            nodes[id].range.length = position - start
            return id
        }
        mutating func string() throws -> NSRange {
            let start = position; try take(34)
            while position < units.count {
                let unit = current; try step()
                if unit == 34 { return NSRange(location: start, length: position - start) }
                guard unit >= 32 else { throw Failure.invalid }
                if unit == 92 {
                    switch current {
                    case 34, 47, 92, 98, 102, 110, 114, 116: try step()
                    case 117:
                        try step()
                        for _ in 0..<4 {
                            guard (48...57).contains(current) || (65...70).contains(current) || (97...102).contains(current) else { throw Failure.invalid }
                            try step()
                        }
                    default: throw Failure.invalid
                    }
                }
            }
            throw Failure.invalid
        }
        mutating func number() throws {
            if current == 45 { try step() }
            if current == 48 { try step() }
            else { guard (49...57).contains(current) else { throw Failure.invalid }; try digits() }
            if current == 46 { try step(); try digits() }
            if current == 69 || current == 101 {
                try step(); if current == 43 || current == 45 { try step() }; try digits()
            }
        }
        mutating func digits() throws {
            guard (48...57).contains(current) else { throw Failure.invalid }
            while (48...57).contains(current) { try step() }
        }
    }
}

import Foundation

/// Ordered form fields, never a dictionary. The original source remains authoritative.
struct InspectorFormDocument {
    enum Failure: Error { case invalid, limited, cancelled }
    struct Field {
        let name: String
        let value: String
        let hasEquals: Bool
        let original: String
        let searchRange: NSRange
    }
    let fields: [Field]
    let searchText: String

    static func parse(_ source: String, cancelled: () -> Bool = { false }) throws -> Self {
        guard source.utf16.prefix(65_537).count <= 65_536 else { throw Failure.limited }
        if cancelled() { throw Failure.cancelled }
        let bytes = Array(source.utf8)
        var fields: [Field] = [], searchText = "", searchOffset = 0, start = 0
        for end in 0...bytes.count where end == bytes.count || bytes[end] == 38 {
            if cancelled() { throw Failure.cancelled }
            defer { start = end + 1 }
            guard end > start else { continue }
            guard fields.count < 256 else { throw Failure.limited }
            let equal = bytes[start..<end].firstIndex(of: 61)
            let name = try decode(bytes[start..<(equal ?? end)], cancelled: cancelled)
            let value = try decode(bytes[(equal.map { $0 + 1 } ?? end)..<end], cancelled: cancelled)
            let searchable = name + "\n" + value + "\n", length = searchable.utf16.count
            fields.append(Field(name: name, value: value, hasEquals: equal != nil,
                original: String(decoding: bytes[start..<end], as: UTF8.self), searchRange: NSRange(location: searchOffset, length: length)))
            searchText += searchable; searchOffset += length
        }
        return Self(fields: fields, searchText: searchText)
    }
    func field(at offset: Int) -> Int? { fields.firstIndex { NSLocationInRange(offset, $0.searchRange) } }
    private static func decode(_ bytes: ArraySlice<UInt8>, cancelled: () -> Bool) throws -> String {
        var result: [UInt8] = []; result.reserveCapacity(bytes.count)
        var index = bytes.startIndex
        while index < bytes.endIndex {
            if index % 256 == 0, cancelled() { throw Failure.cancelled }
            let byte = bytes[index]
            if byte == 37 {
                guard index + 2 < bytes.endIndex, let high = hex(bytes[index + 1]), let low = hex(bytes[index + 2]) else { throw Failure.invalid }
                result.append(high * 16 + low); index += 3
            } else { result.append(byte == 43 ? 32 : byte); index += 1 }
        }
        // A diagnostic reader must not silently replace malformed bytes with U+FFFD.
        guard let value = String(bytes: result, encoding: .utf8) else { throw Failure.invalid }
        return value
    }
    private static func hex(_ byte: UInt8) -> UInt8? {
        switch byte { case 48...57: return byte - 48; case 65...70: return byte - 55; case 97...102: return byte - 87; default: return nil }
    }
}

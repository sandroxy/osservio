import Foundation
import CoreFoundation
import OsservioCore

/// Reads the exact persistent UserDefaults application/suite domain through public CFPreferences APIs.
/// No synchronize, registered-default fallback, app-group discovery, mutation or change listener.
/// The native key list and one native value may be materialized by the OS before SDK bounds apply.
public final class UserDefaultsKeyValueSource: KeyValueSource {
    private let domain: String
    private let prefix: String
    public init(domainName: String, keyPrefix: String) throws {
        guard !domainName.isEmpty, domainName.utf8.prefix(1025).count <= 1024,
              keyPrefix.utf8.prefix(1025).count <= 1024 else { throw KeyValueInspection.Failure.unavailable }
        domain = domainName; prefix = keyPrefix
    }
    public func visitKeys(_ visitor: (String) -> Bool) throws -> Bool {
        guard let keys = CFPreferencesCopyKeyList(domain as CFString, kCFPreferencesCurrentUser, kCFPreferencesAnyHost) else { return true }
        var visited = 0
        for key in keys as NSArray {
            visited += 1; if visited > KeyValueInspection.maxScannedKeys { return false }
            guard let key = key as? String else { throw KeyValueInspection.Failure.unavailable }
            if key.hasPrefix(prefix), !visitor(key) { return false }
        }
        return true
    }
    public func read(key: String) throws -> KeyValueInspection.Value {
        guard key.hasPrefix(prefix), let value = CFPreferencesCopyValue(key as CFString, domain as CFString,
            kCFPreferencesCurrentUser, kCFPreferencesAnyHost) else { return .unavailable(.missing) }
        if let text = value as? String { return .text(.string, text) }
        if let number = value as? NSNumber {
            let kind: KeyValueInspection.Kind = CFGetTypeID(number) == CFBooleanGetTypeID() ? .boolean
                : CFNumberIsFloatType(unsafeBitCast(number, to: CFNumber.self)) ? .number : .integer
            return .text(kind, kind == .boolean ? (number.boolValue ? "true" : "false") : number.stringValue)
        }
        if let data = value as? Data {
            guard data.count <= KeyValueInspection.maxValueBytes / 4 * 3 else { return .unavailable(.tooLarge) }
            return .text(.data, data.base64EncodedString())
        }
        if let date = value as? Date {
            let formatter = ISO8601DateFormatter(); formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            // Keep the native reference-time number as well as a readable date, without losing sub-millisecond precision.
            return .text(.date, "{\"iso8601\":\"\(formatter.string(from: date))\",\"secondsSince2001UTC\":\(date.timeIntervalSinceReferenceDate)}")
        }
        if value is NSArray || value is NSDictionary {
            var encoder = PreferenceJSON()
            do { try encoder.append(value, depth: 0); return .text(value is NSArray ? .array : .object, encoder.text) }
            catch PreferenceJSON.Failure.limit { return .unavailable(.tooLarge) }
            catch { return .unavailable(.unsupported) }
        }
        return .unavailable(.unsupported)
    }
}

/// Bounded property-list projection, without arbitrary descriptions or full-tree JSON serialization.
private struct PreferenceJSON {
    enum Failure: Error { case limit, unsupported }
    var text = ""
    private var bytes = 0, nodes = 0
    mutating func write(_ value: String) throws {
        let size = value.utf8.count
        guard bytes <= KeyValueInspection.maxValueBytes - size else { throw Failure.limit }
        text += value; bytes += size
    }
    mutating func quoted(_ value: String) throws {
        try write("\"")
        for scalar in value.unicodeScalars {
            switch scalar.value {
            case 34: try write("\\\"")
            case 92: try write("\\\\")
            case 0..<32: try write(String(format: "\\u%04x", scalar.value))
            default: try write(String(scalar))
            }
        }
        try write("\"")
    }
    mutating func append(_ value: Any, depth: Int) throws {
        nodes += 1; guard nodes <= 1024, depth <= 16 else { throw Failure.limit }
        if let string = value as? String { try quoted(string) }
        else if let number = value as? NSNumber {
            if CFGetTypeID(number) == CFBooleanGetTypeID() { try write(number.boolValue ? "true" : "false") }
            else { guard number.doubleValue.isFinite else { throw Failure.unsupported }; try write(number.stringValue) }
        } else if let array = value as? NSArray {
            guard array.count <= 1024 - nodes else { throw Failure.limit }; try write("[")
            for (index, item) in array.enumerated() { if index > 0 { try write(",") }; try append(item, depth: depth + 1) }; try write("]")
        } else if let dictionary = value as? NSDictionary {
            guard dictionary.count <= 1024 - nodes else { throw Failure.limit }; try write("{")
            // Enumeration avoids copying and sorting arbitrarily large property-list keys.
            var first = true
            for (key, item) in dictionary {
                guard let key = key as? String else { throw Failure.unsupported }
                if !first { try write(",") }; first = false; try quoted(key); try write(":"); try append(item, depth: depth + 1)
            }; try write("}")
        } else { throw Failure.unsupported }
    }
}

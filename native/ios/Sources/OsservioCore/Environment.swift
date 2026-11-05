import Foundation

public enum EnvironmentPlatform: String, Sendable { case android, ios }
public enum EnvironmentKey: String, CaseIterable, Sendable {
    case osVersion, appIdentifier, appVersion, appBuild, manufacturer, deviceModel, architecture
    case physicalMemoryBytes, processResidentBytes, processProportionalBytes, processFootprintBytes
    case runtimeHeapBytes, runtimeHeapLimitBytes, processID, batteryPercent, lowPowerMode, thermalState
}
/// Source facts must contain only public application/system metadata, never user identifiers or arbitrary context.
public enum EnvironmentFact: Sendable {
    case text(String), number(Int64), flag(Bool), unavailable, unsupported
}
public struct EnvironmentValue: Sendable {
    public let fact: EnvironmentFact
    public let isTruncated: Bool
}
public enum EnvironmentError: Error, Sendable { case invalidFields, invalidValue, busy, unavailable }

/// An immutable source-sanitized snapshot. Known missing fields stay explicit instead of becoming zero or empty text.
public struct EnvironmentSnapshot: Sendable {
    public let platform: EnvironmentPlatform
    public let timestampMillis: Int64
    public let values: [EnvironmentKey: EnvironmentValue]

    public static func capture(platform: EnvironmentPlatform, publicFacts: [EnvironmentKey: EnvironmentFact],
                               timestampMillis: Int64 = SystemLogClock().wallTimeMillis()) throws -> EnvironmentSnapshot {
        guard publicFacts.count == EnvironmentKey.allCases.count else { throw EnvironmentError.invalidFields }
        var values: [EnvironmentKey: EnvironmentValue] = [:]
        for key in EnvironmentKey.allCases {
            guard let fact = publicFacts[key] else { throw EnvironmentError.invalidFields }
            var safe = fact
            var truncated = false
            switch fact {
            case .unavailable, .unsupported: break
            case .text(let text):
                guard !key.numeric, key != .lowPowerMode, !text.isEmpty,
                      let bounded = BoundedText.prefix(text, bytes: 256) else { throw EnvironmentError.invalidValue }
                safe = .text(bounded.text); truncated = bounded.truncated
            case .number(let number):
                guard key.numeric, number >= 0, key != .batteryPercent || number <= 100 else { throw EnvironmentError.invalidValue }
            case .flag:
                guard key == .lowPowerMode else { throw EnvironmentError.invalidValue }
            }
            values[key] = EnvironmentValue(fact: safe, isTruncated: truncated)
        }
        return EnvironmentSnapshot(platform: platform, timestampMillis: timestampMillis, values: values)
    }
}
private extension EnvironmentKey {
    var numeric: Bool {
        switch self {
        case .physicalMemoryBytes, .processResidentBytes, .processProportionalBytes, .processFootprintBytes,
             .runtimeHeapBytes, .runtimeHeapLimitBytes, .processID, .batteryPercent: return true
        default: return false
        }
    }
}
public protocol EnvironmentProvider: Sendable {
    /// One-shot asynchronous query. Completion may run on any queue, including immediately for busy requests.
    func read(completion: @escaping @Sendable (Result<EnvironmentSnapshot, EnvironmentError>) -> Void)
}

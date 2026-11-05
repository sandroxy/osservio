import Foundation

public enum BreadcrumbRecordResult: Sendable {
    case recorded, inactive, busy, invalidLabel, invalidMessage, rateLimited, tooLarge, sinkFailure, sequenceExhausted
}
public struct BreadcrumbCaptureStatus: Sendable { public let rateLimitedCount: Int64 }

/// Baseline and detailed capture admit these compact observations. Off admits neither.
public final class BreadcrumbRecorder: @unchecked Sendable {
    public let captureController: CaptureController
    private let sink: any BreadcrumbSink
    private let messageByteLimit: Int
    private let eventsPerSecond: Int
    private var sessionID = ""
    private var windowStart: UInt64 = 0
    private var windowCount = 0
    private var rateLimited: Int64 = 0

    public init(sink: any BreadcrumbSink, captureController: CaptureController,
                messageByteLimit: Int = 1_024, eventsPerSecond: Int = 20) throws {
        guard (9...4_096).contains(messageByteLimit), (1...1_000).contains(eventsPerSecond) else {
            throw CaptureConfigurationError.invalidLimit
        }
        self.sink = sink; self.captureController = captureController
        self.messageByteLimit = messageByteLimit; self.eventsPerSecond = eventsPerSecond
    }
    @discardableResult
    public func record(_ kind: BreadcrumbKind, label: String, message: LogMessage? = nil,
                       value: Int64? = nil, retention: BreadcrumbRetention = .memoryOnly) -> BreadcrumbRecordResult {
        switch captureController.record(requiresDetailed: false, { context in
            self.ingest(context, kind: kind, label: label, message: message, value: value, retention: retention)
        }) {
        case .value(let result): return result
        case .inactive: return .inactive
        case .busy: return .busy
        case .sequenceExhausted: return .sequenceExhausted
        }
    }
    public func status() -> BreadcrumbCaptureStatus? {
        captureController.inspect { _ in BreadcrumbCaptureStatus(rateLimitedCount: rateLimited) }
    }
    private func ingest(_ context: CaptureContext, kind: BreadcrumbKind, label: String, message: LogMessage?,
                        value: Int64?, retention: BreadcrumbRetention) -> BreadcrumbRecordResult {
        guard validBreadcrumbLabel(label) else { return .invalidLabel }
        if sessionID != context.sessionID { sessionID = context.sessionID; windowStart = context.sessionStartMillis; windowCount = 0 }
        if context.monotonicMillis >= windowStart, context.monotonicMillis - windowStart >= 1_000 {
            windowStart = context.monotonicMillis; windowCount = 0
        }
        guard windowCount < eventsPerSecond else { if rateLimited < Int64.max { rateLimited += 1 }; return .rateLimited }
        windowCount += 1
        var bounded: (text: String, truncated: Bool)?
        if let message {
            guard let text = BoundedText.prefix(message.text, bytes: messageByteLimit) else { return .invalidMessage }
            bounded = text
        }
        let event = BreadcrumbEvent(context: context, timestamp: captureController.clock.wallTimeMillis(), kind: kind,
            label: label, message: bounded?.text, isPrivate: message?.isPrivate ?? false,
            isTruncated: bounded?.truncated ?? false, value: value, retention: retention)
        do {
            switch try sink.append(event) {
            case .stored: return .recorded
            case .busy: return .busy
            case .tooLarge: return .tooLarge
            }
        } catch { return .sinkFailure }
    }
}

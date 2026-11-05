import Foundation

/// A finite capture session. Capture uses try-lock admission and has no async queue.
public final class LogRecorder: @unchecked Sendable {
    public let captureController: CaptureController
    private let sink: any LogSink
    private let messageByteLimit: Int
    private let eventsPerSecond: Int
    private var sessionID = ""
    private var windowStart: UInt64 = 0
    private var windowCount = 0
    private var rateLimited: Int64 = 0

    public convenience init(sink: any LogSink, messageByteLimit: Int = 4_096,
                eventsPerSecond: Int = 200, clock: any LogClock = SystemLogClock()) throws {
        try self.init(sink: sink, captureController: CaptureController(clock: clock),
                      messageByteLimit: messageByteLimit, eventsPerSecond: eventsPerSecond)
    }

    public init(sink: any LogSink, captureController: CaptureController,
                messageByteLimit: Int = 4_096, eventsPerSecond: Int = 200) throws {
        guard (9...65_536).contains(messageByteLimit), (1...10_000).contains(eventsPerSecond) else {
            throw CaptureConfigurationError.invalidLimit
        }
        self.sink = sink
        self.captureController = captureController
        self.messageByteLimit = messageByteLimit
        self.eventsPerSecond = eventsPerSecond
    }

    /// Starts detailed capture for all recorders sharing this controller, without clearing history.
    public func start(durationMillis: UInt64 = 900_000) throws {
        try captureController.startDetailed(durationMillis: durationMillis)
    }

    /// Ends shared detailed capture and restores the configured baseline or off state.
    @discardableResult
    public func stop() -> Bool {
        captureController.stopDetailed()
    }

    /// Nil means capture is currently using the state; callers should retry later.
    public func status() -> LogCaptureStatus? {
        captureController.inspect { state in
            LogCaptureStatus(isRecording: state.mode == .detailed,
                             remainingMillis: state.remainingMillis, rateLimitedCount: rateLimited)
        }
    }

    /// Category is a static ASCII label, not a place for user or request data.
    @discardableResult
    public func record(_ level: LogLevel, category: String, message: LogMessage) -> LogCaptureResult {
        switch captureController.record(requiresDetailed: true, { context in
            ingest(level, category: category, message: message, context: context)
        }) {
        case .value(let result): return result
        case .inactive: return .inactive
        case .busy: return .busy
        case .sequenceExhausted: return .sequenceExhausted
        }
    }

    private func ingest(_ level: LogLevel, category: String, message: LogMessage,
                        context: CaptureContext) -> LogCaptureResult {
        let now = context.monotonicMillis
        if sessionID != context.sessionID {
            sessionID = context.sessionID
            windowStart = context.sessionStartMillis
            windowCount = 0
        }
        guard Self.validCategory(category) else { return .invalidCategory }
        if now >= windowStart, now - windowStart >= 1_000 {
            windowStart = now
            windowCount = 0
        }
        guard windowCount < eventsPerSecond else {
            if rateLimited < Int64.max { rateLimited += 1 }
            return .rateLimited
        }
        windowCount += 1
        guard let bounded = BoundedText.prefix(message.text, bytes: messageByteLimit) else { return .invalidMessage }
        let event = LogEvent(sessionID: sessionID, sequence: context.sequence,
                             timestampMillis: captureController.clock.wallTimeMillis(), level: level,
                             category: category, message: bounded.text,
                             isPrivate: message.isPrivate, isTruncated: bounded.truncated)
        do {
            switch try sink.append(event) {
            case .stored: return .recorded
            case .busy: return .busy
            case .tooLarge: return .tooLarge
            }
        } catch {
            return .sinkFailure
        }
    }

    private static func validCategory(_ value: String) -> Bool {
        let bytes = Array(value.utf8.prefix(65))
        return !bytes.isEmpty && bytes.count <= 64 && bytes.allSatisfy {
            (65...90).contains($0) || (97...122).contains($0) || (48...57).contains($0)
                || $0 == 45 || $0 == 46 || $0 == 95
        }
    }

}

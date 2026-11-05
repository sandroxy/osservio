import Foundation

public enum LogConfigurationError: Error { case invalidLimit, invalidDuration, busy }

/// A finite capture session. Capture uses try-lock admission and has no async queue.
public final class LogRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private let sink: any LogSink
    private let clock: any LogClock
    private let messageByteLimit: Int
    private let eventsPerSecond: Int
    private var deadline: UInt64 = 0
    private var sessionID = ""
    private var sequence: Int64 = 0
    private var windowStart: UInt64 = 0
    private var windowCount = 0
    private var rateLimited: Int64 = 0

    public init(sink: any LogSink, messageByteLimit: Int = 4_096,
                eventsPerSecond: Int = 200, clock: any LogClock = SystemLogClock()) throws {
        guard (9...65_536).contains(messageByteLimit), (1...10_000).contains(eventsPerSecond) else {
            throw LogConfigurationError.invalidLimit
        }
        self.sink = sink
        self.clock = clock
        self.messageByteLimit = messageByteLimit
        self.eventsPerSecond = eventsPerSecond
    }

    /// Starts or replaces a session without clearing history. Maximum duration is one day.
    public func start(durationMillis: UInt64 = 900_000) throws {
        guard (1...86_400_000).contains(durationMillis) else { throw LogConfigurationError.invalidDuration }
        guard lock.try() else { throw LogConfigurationError.busy }
        defer { lock.unlock() }
        let now = clock.monotonicMillis()
        guard now <= UInt64.max - durationMillis else { throw LogConfigurationError.invalidDuration }
        sessionID = UUID().uuidString
        sequence = 0
        deadline = now + durationMillis
        windowStart = now
        windowCount = 0
    }

    @discardableResult
    public func stop() -> Bool {
        guard lock.try() else { return false }
        deadline = 0
        lock.unlock()
        return true
    }

    /// Nil means capture is currently using the state; callers should retry later.
    public func status() -> LogCaptureStatus? {
        guard lock.try() else { return nil }
        defer { lock.unlock() }
        let now = clock.monotonicMillis()
        return LogCaptureStatus(isRecording: now < deadline,
                                remainingMillis: now < deadline ? deadline - now : 0,
                                rateLimitedCount: rateLimited)
    }

    /// Category is a static ASCII label, not a place for user or request data.
    @discardableResult
    public func record(_ level: LogLevel, category: String, message: LogMessage) -> LogCaptureResult {
        guard lock.try() else { return .busy }
        defer { lock.unlock() }
        let now = clock.monotonicMillis()
        guard now < deadline else { return .inactive }
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
        guard sequence < Int64.max else { return .sequenceExhausted }
        sequence += 1
        guard let bounded = Self.bound(message.text, bytes: messageByteLimit) else { return .invalidMessage }
        let event = LogEvent(sessionID: sessionID, sequence: sequence,
                             timestampMillis: clock.wallTimeMillis(), level: level,
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

    private static func bound(_ value: String, bytes limit: Int) -> (text: String, truncated: Bool)? {
        var bytes = Array(value.utf8.prefix(limit + 1))
        guard bytes.count > limit else { return (String(decoding: bytes, as: UTF8.self), false) }
        bytes.removeLast()
        // A UTF-8 scalar occupies at most four bytes. Never split one or append extra bytes.
        for _ in 0..<4 {
            if let text = String(bytes: bytes, encoding: .utf8) { return (text, true) }
            bytes.removeLast()
        }
        return nil
    }
}

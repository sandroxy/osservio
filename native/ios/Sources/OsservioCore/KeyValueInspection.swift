import Foundation

/// An explicitly registered domain. Bind an immutable owner; never consult a mutable current account.
/// Reads run on the inspection worker and must not write, hydrate, migrate, expire or update access times.
public protocol KeyValueSource: AnyObject {
    /// Stop immediately when the visitor returns false. Never shorten a key.
    /// True only when enumeration reached the end of the registered scope.
    func visitKeys(_ visitor: (String) -> Bool) throws -> Bool
    func read(key: String) throws -> KeyValueInspection.Value
}

/// Optional local inspection, independent of capture and incident export. Close before changing accounts
/// and dispose the old inspector. Closing never closes or changes the host's underlying storage.
public final class KeyValueInspection: @unchecked Sendable {
    public static let maxDomains = 32, maxKeys = 2048, maxScannedKeys = 8192, maxKeyBytes = 1024,
        maxIndexBytes = 262_144, maxValueBytes = 65_536
    public enum Failure: Error, Equatable { case busy, closed, unavailable, unknownDomain, invalidKey }
    public enum Kind: String, Sendable { case string, boolean, integer, number, stringSet, array, object, data, date }
    public enum State: Sendable { case available, missing, unsupported, tooLarge }
    public struct Value: Sendable {
        public let kind: Kind?
        public let state: State
        public let text: String
        public let truncated: Bool
        public static func text(_ kind: Kind, _ text: String) -> Self {
            guard let bounded = BoundedText.prefix(text, bytes: maxValueBytes) else { return .unavailable(.unsupported) }
            return Self(kind: kind, state: .available, text: bounded.text, truncated: bounded.truncated)
        }
        public static func unavailable(_ state: State) -> Self {
            precondition(state != .available)
            return Self(kind: nil, state: state, text: "", truncated: false)
        }
    }
    public struct Domain: Sendable {
        public let id: String, name: String, backend: String, scope: String
        public init(id: String, name: String, backend: String, scope: String) throws {
            for value in [id, name, backend, scope] {
                guard !value.isEmpty, let bounded = BoundedText.prefix(value, bytes: maxKeyBytes), !bounded.truncated else { throw Failure.unavailable }
            }
            self.id = id; self.name = name; self.backend = backend; self.scope = scope
        }
    }
    public struct Registration {
        public let domain: Domain
        let source: any KeyValueSource
        public init(domain: Domain, source: any KeyValueSource) { self.domain = domain; self.source = source }
    }
    public struct Keys: Sendable {
        public let keys: [String]
        public let limited: Bool
        public let readAtMillis: Int64
    }
    public struct Read: Sendable {
        public let value: Value
        public let readAtMillis: Int64
    }
    public final class Request: @unchecked Sendable {
        private let lock = NSLock()
        private var cancelled = false
        private var onCancel: (() -> Void)?
        fileprivate init(onCancel: (() -> Void)? = nil) { self.onCancel = onCancel }
        /// Drops delivery. A platform read already in progress may finish; no later read is queued.
        public func cancel() {
            lock.lock(); cancelled = true; let action = onCancel; onCancel = nil; lock.unlock(); action?()
        }
        fileprivate var isCancelled: Bool { lock.lock(); defer { lock.unlock() }; return cancelled }
    }
    public let owner: UUID
    public let domains: [Domain]
    private let lock = NSLock()
    private var closed = false, busy = false
    private var sources: [String: any KeyValueSource]
    private var observers: [UUID: @Sendable () -> Void] = [:]
    private let queue = DispatchQueue(label: "io.github.sandroxy.osservio.storage-read", qos: .utility)
    public init(owner: UUID, registrations: [Registration]) throws {
        guard registrations.count <= Self.maxDomains, Set(registrations.map(\.domain.id)).count == registrations.count else { throw Failure.unavailable }
        self.owner = owner; domains = registrations.map(\.domain)
        sources = Dictionary(uniqueKeysWithValues: registrations.map { ($0.domain.id, $0.source) })
    }
    public var isClosed: Bool { lock.lock(); defer { lock.unlock() }; return closed }
    /// Owner invalidation may arrive on any thread. Cancel the observation when the page is hidden.
    public func observeClose(_ observer: @escaping @Sendable () -> Void) -> Request {
        let id = UUID(); lock.lock(); let notify = closed
        if !notify { precondition(observers.count < 64); observers[id] = observer }; lock.unlock()
        if notify { observer() }
        return Request { [weak self] in guard let self else { return }; self.lock.lock(); self.observers.removeValue(forKey: id); self.lock.unlock() }
    }
    /// One read at a time, with no query backlog. Completion may run on any thread, including the caller.
    @discardableResult public func keys(domain: String, completion: @escaping @Sendable (Result<Keys, Failure>) -> Void) -> Request {
        submit(domain: domain, completion: completion) { [self] source, request in
            // Swift String equality is canonically equivalent; storage keys must retain exact bytes.
            var keys: [Data: String] = [:], bytes = 0, limited = false, visited = 0
            let complete = try source.visitKeys { key in
                guard !isClosed, !request.isCancelled, !limited else { return false }
                visited += 1; guard visited <= Self.maxScannedKeys else { limited = true; return false }
                guard let bounded = BoundedText.prefix(key, bytes: Self.maxKeyBytes), !bounded.truncated else { limited = true; return false }
                let identity = Data(key.utf8)
                if keys[identity] != nil { return true }
                let size = key.utf8.count
                guard keys.count < Self.maxKeys, bytes <= Self.maxIndexBytes - size else { limited = true; return false }
                keys[identity] = key; bytes += size; return true
            }
            return Keys(keys: keys.values.sorted { $0.utf8.lexicographicallyPrecedes($1.utf8) }, limited: limited || !complete, readAtMillis: Self.now())
        }
    }
    @discardableResult public func read(domain: String, key: String, completion: @escaping @Sendable (Result<Read, Failure>) -> Void) -> Request {
        guard let bounded = BoundedText.prefix(key, bytes: Self.maxKeyBytes), !bounded.truncated else { completion(.failure(.invalidKey)); return Request() }
        return submit(domain: domain, completion: completion) { source, _ in Read(value: try source.read(key: key), readAtMillis: Self.now()) }
    }
    private func submit<T: Sendable>(domain: String, completion: @escaping @Sendable (Result<T, Failure>) -> Void,
                                    operation: @escaping (any KeyValueSource, Request) throws -> T) -> Request {
        let request = Request()
        lock.lock()
        let source = sources[domain]
        let rejection: Failure? = closed ? .closed : source == nil ? .unknownDomain : busy ? .busy : nil
        if rejection == nil { busy = true }; lock.unlock()
        if let rejection { completion(.failure(rejection)); return request }
        guard let source else { completion(.failure(.unknownDomain)); return request }
        queue.async { [self] in
            var result: Result<T, Failure>
            do {
                if isClosed || request.isCancelled { result = .failure(.closed) }
                else { result = .success(try operation(source, request)) }
            } catch { result = .failure(.unavailable) }
            lock.lock(); busy = false; if closed { result = .failure(.closed) }; lock.unlock()
            if !request.isCancelled { completion(result) }
        }
        return request
    }
    /// Invalidates pending results and releases registrations, without writing or deleting host data.
    public func close() {
        lock.lock(); closed = true; let released = sources; sources.removeAll(); let listeners = Array(observers.values); observers.removeAll(); lock.unlock()
        // Host source deinitializers must not run while the inspection lock is held.
        withExtendedLifetime(released) {}
        for listener in listeners { listener() }
    }
    private static func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}

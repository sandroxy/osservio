import Foundation
import OsservioCore

/// Observes explicitly submitted data requests on a host-owned URLSession. Installs no hooks or protocols.
public final class ObservedURLSession: @unchecked Sendable {
    public let session: URLSession
    public let recorder: HTTPRecorder
    public let collectsMetrics: Bool

    public init(session: URLSession, recorder: HTTPRecorder, collectsMetrics: Bool = true) {
        self.session = session; self.recorder = recorder; self.collectsMetrics = collectsMetrics
    }

    /// Returns the original suspended task. The host still owns resume, cancel, delegate, and session lifetime.
    public func dataTask(with request: URLRequest,
                         completionHandler: @escaping @Sendable (Data?, URLResponse?, Error?) -> Void) -> URLSessionDataTask {
        guard let observation = begin(request) else { return session.dataTask(with: request, completionHandler: completionHandler) }
        let metrics = collectsMetrics ? URLSessionMetricsDelegate(target: session.delegate as? any URLSessionTaskDelegate) : nil
        let task = session.dataTask(with: request) { [recorder] data, response, error in
            Self.finish(observation, recorder: recorder, data: data, response: response, error: error, metrics: metrics?.snapshot())
            completionHandler(data, response, error)
        }
        if #available(iOS 15.0, macOS 12.0, *), let metrics { task.delegate = metrics }
        return task
    }

    @available(iOS 15.0, macOS 12.0, *)
    public func data(for request: URLRequest, delegate: (any URLSessionTaskDelegate)? = nil) async throws -> (Data, URLResponse) {
        guard let observation = begin(request) else { return try await session.data(for: request, delegate: delegate) }
        let metrics = collectsMetrics ? URLSessionMetricsDelegate(target: delegate ?? session.delegate as? any URLSessionTaskDelegate) : nil
        do {
            let result = try await session.data(for: request, delegate: metrics ?? delegate)
            Self.finish(observation, recorder: recorder, data: result.0, response: result.1, error: nil, metrics: metrics?.snapshot())
            return result
        } catch {
            Self.finish(observation, recorder: recorder, data: nil, response: nil, error: error, metrics: metrics?.snapshot())
            throw error
        }
    }

    private struct Observation: Sendable {
        let token: HTTPExchangeToken
        let requestComplete: Bool
    }

    private func begin(_ request: URLRequest) -> Observation? {
        guard let url = request.url, let scheme = url.scheme?.lowercased(), ["http", "https"].contains(scheme),
              recorder.captureController.status()?.mode == .detailed else { return nil }
        let headers = (request.allHTTPHeaderFields ?? [:]).prefix(33).map { HTTPHeader(name: $0.key, value: $0.value) }
        guard let token = recorder.begin(method: request.httpMethod ?? "GET", url: url.absoluteString,
                                         headers: headers, source: .urlSession).token else { return nil }
        // Only the caller-provided Data is observed. Never open or consume an input stream.
        var complete = request.httpBodyStream == nil
        if let data = request.httpBody {
            complete = recorder.observeBody(token, direction: .request, byteCount: Int64(data.count)) { Data(data.prefix($0)) } == .recorded && complete
        }
        return Observation(token: token, requestComplete: complete)
    }

    private static func finish(_ observation: Observation, recorder: HTTPRecorder,
                               data: Data?, response: URLResponse?, error: Error?, metrics: HTTPMetrics?) {
        var responseComplete = error == nil
        if let http = response as? HTTPURLResponse {
            let headers = http.allHeaderFields.prefix(33).map { pair -> HTTPHeader in
                guard let name = pair.key as? String, let value = pair.value as? String else {
                    return HTTPHeader(name: "", value: "")
                }
                return HTTPHeader(name: name, value: value)
            }
            let result = recorder.receiveResponse(observation.token, statusCode: http.statusCode, headers: headers,
                                                   bodyIsDecoded: true, url: http.url?.absoluteString)
            responseComplete = result == .recorded && responseComplete
            if let data = data {
                let result = recorder.observeBody(observation.token, direction: .response, byteCount: Int64(data.count)) { Data(data.prefix($0)) }
                responseComplete = result == .recorded && responseComplete
            }
        } else if error == nil {
            recorder.stopObserving(observation.token)
            return
        }
        if let error = error {
            let native = error as NSError
            let cancelled = error is CancellationError || (native.domain == NSURLErrorDomain && native.code == NSURLErrorCancelled)
            recorder.finish(observation.token, state: cancelled ? .cancelled : .failed,
                            failure: cancelled ? nil : failureCategory(native),
                            requestComplete: observation.requestComplete, responseComplete: false, metrics: metrics)
        } else {
            recorder.finish(observation.token, state: .completed, requestComplete: observation.requestComplete,
                            responseComplete: responseComplete, metrics: metrics)
        }
    }

    private static func failureCategory(_ error: NSError) -> HTTPFailure {
        guard error.domain == NSURLErrorDomain else { return .unknown }
        switch error.code {
        case NSURLErrorTimedOut: return .timeout
        case NSURLErrorCannotFindHost, NSURLErrorCannotConnectToHost, NSURLErrorDNSLookupFailed,
             NSURLErrorNotConnectedToInternet, NSURLErrorNetworkConnectionLost: return .connection
        case NSURLErrorSecureConnectionFailed, NSURLErrorServerCertificateHasBadDate,
             NSURLErrorServerCertificateUntrusted, NSURLErrorServerCertificateHasUnknownRoot,
             NSURLErrorServerCertificateNotYetValid, NSURLErrorClientCertificateRejected,
             NSURLErrorClientCertificateRequired: return .tls
        default: return .transport
        }
    }
}

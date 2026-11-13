import Darwin
import Foundation
import OsservioCore
import OsservioStore
import XCTest

/// Opt-in synthetic load. Process measurements include the harness, not just the SDK.
final class CaptureLoadTests: XCTestCase {
    private let context = LogDetails.publicValues(arguments: [.integer(9_007_199_254_740_993), .boolean(false)],
        attributes: [LogField("operation", .string("load"))], error: LogError(type: "SyntheticError", stackTrace: "Fixture.run\nFixture.tick"))
    private let text = String(repeating: "x", count: 8_192)
    private let headers = [HTTPHeader(name: "Authorization", value: "Bearer " + String(repeating: "x", count: 1_200)),
                           HTTPHeader(name: "Content-Type", value: "text/plain")]
    private let latencyUpperUs: [UInt64] = [10, 50, 100, 500, 1_000, 5_000, 20_000]

    func testSustainedCaptureAndQueryMeasurements() throws {
        guard let argument = ProcessInfo.processInfo.environment["OSSERVIO_LOAD_SECONDS"] else {
            throw XCTSkip("Set OSSERVIO_LOAD_SECONDS explicitly for resource measurements")
        }
        let seconds = try XCTUnwrap(Int(argument))
        guard (5...900).contains(seconds) else { return XCTFail("Duration must be 5...900 seconds per mode") }
        for mode in ["off", "baseline", "detailed", "queried"] { try measure(mode: mode, seconds: seconds) }
    }

    private func measure(mode: String, seconds: Int) throws {
        let capture = CaptureController()
        let logs = try MemoryLogStore(recordLimit: 200, textByteLimit: 65_536)
        let crumbs = try MemoryBreadcrumbStore(recordLimit: 200, textByteLimit: 65_536)
        let requests = try MemoryHTTPStore(recordLimit: 100, textByteLimit: 65_536, bodyByteLimit: 32_768)
        let sockets = try MemoryWebSocketStore(recordLimit: 200, textByteLimit: 65_536, payloadByteLimit: 16_384)
        let log = try LogRecorder(sink: logs, captureController: capture)
        let breadcrumb = try BreadcrumbRecorder(sink: crumbs, captureController: capture)
        let http = try HTTPRecorder(sink: requests, captureController: capture,
            privacy: HTTPPrivacyPolicy(bodyPolicy: .publicUTF8, bodyByteLimit: 1_024))
        let ws = try WebSocketRecorder(sink: sockets, captureController: capture,
            privacy: WebSocketPrivacyPolicy(publicMessageText: true, messageByteLimit: 1_024))
        if mode != "off" { XCTAssertTrue(capture.setBaselineEnabled(true)) }
        if mode == "detailed" || mode == "queried" { try capture.startDetailed(durationMillis: UInt64(seconds + 10) * 1_000) }
        let body = Data(text.utf8)
        var outcomes: [String: Int] = [:], latency = [Int](repeating: 0, count: latencyUpperUs.count + 1)
        var memory: [[String: Any]] = []
        let started = DispatchTime.now().uptimeNanoseconds, cpu = clock()
        let deadline = started + UInt64(seconds) * 1_000_000_000
        var nextQuery = started, nextSample = started, maximumNs: UInt64 = 0
        var ticks = 0, queries = 0
        func count<T>(_ operation: String, _ result: T) {
            let value = String(describing: result), key = operation + "." + value
            outcomes[key, default: 0] += 1
            XCTAssertFalse(["invalid", "sinkFailure", "connectionLimit"].contains(value), key)
        }
        while DispatchTime.now().uptimeNanoseconds < deadline {
            // Drain Foundation temporaries as a real run-loop iteration would.
            autoreleasepool {
                for _ in 0..<5 {
                    let before = DispatchTime.now().uptimeNanoseconds
                    count("log", log.record(ticks % 50 == 0 ? .error : .info, category: "load",
                        message: ticks % 2 == 0 ? .publicText(text) : .privateText("synthetic-secret"), details: ticks % 50 == 0 ? context : nil))
                    count("breadcrumb", breadcrumb.record(.hostMarker, label: "load.tick"))
                    let request = http.begin(method: "POST", url: "https://example.com/private?secret=value", headers: headers)
                    count("http.begin", request.result)
                    if let token = request.token {
                        count("http.response", http.receiveResponse(token, statusCode: 200, headers: headers))
                        count("http.body", http.observeBody(token, direction: .response, byteCount: Int64(body.count)) { Data(body.prefix($0)) })
                        count("http.finish", http.finish(token, state: .completed))
                    }
                    let connection = ws.begin(url: "wss://example.com/private?secret=value", headers: headers)
                    count("ws.begin", connection.result)
                    if let token = connection.token {
                        count("ws.text", ws.text(token, direction: .incoming, outcome: .received, value: text))
                        count("ws.close", ws.lifecycle(token, kind: .closed, closeCode: 1000))
                    }
                    let elapsed = DispatchTime.now().uptimeNanoseconds - before
                    let bucket = latencyUpperUs.firstIndex { elapsed <= $0 * 1_000 } ?? latencyUpperUs.count
                    latency[bucket] += 1; maximumNs = max(maximumNs, elapsed); ticks += 1
                }
            }
            let now = DispatchTime.now().uptimeNanoseconds
            if mode == "queried", now >= nextQuery {
                try checkBounds(logs, crumbs, requests, sockets); queries += 1
                nextQuery = now + 250_000_000
            }
            if now >= nextSample {
                var info = task_vm_info_data_t()
                var size = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<integer_t>.size)
                let result = withUnsafeMutablePointer(to: &info) {
                    $0.withMemoryRebound(to: integer_t.self, capacity: Int(size)) {
                        task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &size)
                    }
                }
                XCTAssertEqual(result, KERN_SUCCESS)
                memory.append(["mode": mode, "elapsedMs": (now - started) / 1_000_000,
                               "residentBytes": info.resident_size, "footprintBytes": info.phys_footprint])
                nextSample = now + 1_000_000_000
            }
            // Same offered load as Android; scheduler delays are not caught up with bursts.
            Thread.sleep(forTimeInterval: 0.01)
        }
        try checkBounds(logs, crumbs, requests, sockets)
        XCTAssertEqual(ws.status()?.activeConnections, 0)
        for event in try XCTUnwrap(requests.snapshot()).events {
            XCTAssertEqual(event.state, .completed)
            XCTAssertEqual(event.url, "https://example.com/private?secret=value")
            XCTAssertEqual(event.requestHeaders.first?.value, headers.first?.value)
        }
        if mode == "off" || mode == "baseline" {
            XCTAssertTrue(logs.snapshot()?.events.isEmpty == true)
            XCTAssertTrue(requests.snapshot()?.events.isEmpty == true)
            XCTAssertTrue(sockets.snapshot()?.events.isEmpty == true)
        } else {
            XCTAssertFalse(logs.snapshot()?.events.isEmpty == true)
            XCTAssertFalse(requests.snapshot()?.events.isEmpty == true)
            XCTAssertFalse(sockets.snapshot()?.events.isEmpty == true)
            XCTAssertGreaterThan(try XCTUnwrap(logs.snapshot()).evictedCount, 0)
            XCTAssertGreaterThan(try XCTUnwrap(sockets.snapshot()).evictedEvents, 0)
        }
        XCTAssertEqual(mode == "off", crumbs.snapshot()?.events.isEmpty)
        for event in try XCTUnwrap(logs.snapshot()).events where event.isPrivate { XCTAssertEqual(event.message, "[private]") }
        let result: [String: Any] = ["mode": mode, "seconds": seconds, "ticks": ticks,
            "elapsedMs": (DispatchTime.now().uptimeNanoseconds - started) / 1_000_000,
            "processCpuMs": Double(clock() - cpu) * 1_000 / Double(CLOCKS_PER_SEC), "queryCount": queries,
            "tickLatencyUpperUs": latencyUpperUs, "tickLatencyCounts": latency,
            "tickMaximumUs": maximumNs / 1_000, "outcomes": outcomes]
        for row in [result] + memory {
            let data = try JSONSerialization.data(withJSONObject: row, options: [.sortedKeys])
            print("OsservioLoad " + String(decoding: data, as: UTF8.self))
        }
        XCTAssertTrue(capture.stopAll())
    }

    private func checkBounds(_ logs: MemoryLogStore, _ crumbs: MemoryBreadcrumbStore,
                             _ requests: MemoryHTTPStore, _ sockets: MemoryWebSocketStore) throws {
        let log = try XCTUnwrap(logs.snapshot()), breadcrumb = try XCTUnwrap(crumbs.snapshot())
        let http = try XCTUnwrap(requests.snapshot()), ws = try XCTUnwrap(sockets.snapshot())
        XCTAssertLessThanOrEqual(log.retainedDetailsBytes, 262_144)
        XCTAssertLessThanOrEqual(log.events.count, 200); XCTAssertLessThanOrEqual(log.retainedTextBytes, 65_536)
        XCTAssertLessThanOrEqual(breadcrumb.events.count, 200); XCTAssertLessThanOrEqual(breadcrumb.retainedTextBytes, 65_536)
        XCTAssertLessThanOrEqual(http.events.count, 100); XCTAssertLessThanOrEqual(http.retainedTextBytes, 65_536)
        XCTAssertLessThanOrEqual(http.retainedBodyBytes, 32_768)
        XCTAssertLessThanOrEqual(ws.events.count, 200); XCTAssertLessThanOrEqual(ws.retainedTextBytes, 65_536)
        XCTAssertLessThanOrEqual(ws.retainedPayloadBytes, 16_384)
    }
}

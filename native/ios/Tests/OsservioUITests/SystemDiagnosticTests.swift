#if canImport(UIKit)
import OsservioCore
@testable import OsservioUI
import UIKit
import XCTest

final class SystemDiagnosticTests: XCTestCase {
    @MainActor
    func testSavedDiagnosticNavigationKeepsReportAndReadTimesDistinct() async throws {
        let diagnostic = try SystemDiagnostic.metricKit(intervalStartMillis: 1_700_000_000_000, intervalEndMillis: 1_700_086_400_000,
            kind: .crash, metadata: [.appBuild: .restore(value: "historical-build", isTruncated: true)],
            signal: 11, exceptionType: .max, exceptionCode: "18446744073709551615")
        let snapshot = IncidentSnapshot.captureDiagnostic(diagnostic, observedAtMillis: 1_700_172_800_000)
        let viewer = IncidentSnapshotViewController(snapshot: snapshot)
        let navigation = UINavigationController(rootViewController: viewer)
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = navigation; window.makeKeyAndVisible(); viewer.loadViewIfNeeded()
        defer { window.isHidden = true; window.rootViewController = nil }
        XCTAssertEqual(viewer.title, RuntimePresentation.text("diagnostic_kind_crash"))
        XCTAssertNil(viewer.navigationItem.searchController, "No breadcrumb search for an absent attachment")
        let time = viewer.tableView(viewer.tableView, cellForRowAt: IndexPath(row: 0, section: 0))
        XCTAssertEqual(time.textLabel?.text, RuntimePresentation.text("diagnostic_recovered_at"))
        XCTAssertEqual(time.detailTextLabel?.text, RuntimePresentation.date(snapshot.timestampMillis))
        viewer.tableView(viewer.tableView, didSelectRowAt: IndexPath(row: 3, section: 0))
        for _ in 0..<100 {
            if navigation.transitionCoordinator == nil { break }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        let detail = try XCTUnwrap(navigation.topViewController as? SystemDiagnosticViewController); detail.loadViewIfNeeded()
        var fields: [String: String] = [:]
        for row in 0..<detail.tableView(detail.tableView, numberOfRowsInSection: 0) {
            let cell = detail.tableView(detail.tableView, cellForRowAt: IndexPath(row: row, section: 0))
            fields[try XCTUnwrap(cell.textLabel?.text)] = try XCTUnwrap(cell.detailTextLabel?.text)
        }
        XCTAssertEqual(fields[RuntimePresentation.text("diagnostic_interval_start")], RuntimePresentation.date(diagnostic.intervalStartMillis))
        XCTAssertEqual(fields[RuntimePresentation.text("diagnostic_interval_end")], RuntimePresentation.date(diagnostic.intervalEndMillis))
        XCTAssertEqual(fields[RuntimePresentation.text("diagnostic_exception_code")], "18446744073709551615")
        XCTAssertEqual(fields[RuntimePresentation.text("diagnostic_exception_type")], "9223372036854775807")
        XCTAssertEqual(fields[RuntimePresentation.text("env_appBuild")], "historical-build · " + RuntimePresentation.text("truncated"))
        XCTAssertEqual(fields[RuntimePresentation.text("env_deviceModel")], RuntimePresentation.text("runtime_unavailable"))
        // Clipboard delivery belongs to the app-hosted workflow. A package test has no app event dispatcher.
        XCTAssertTrue(SystemDiagnosticPresentation.summary(diagnostic).contains("18446744073709551615"))
        let summary = IncidentPresentation.summary(snapshot)
        XCTAssertTrue(summary.contains(RuntimePresentation.date(snapshot.timestampMillis)))
        XCTAssertTrue(summary.contains("historical-build")); XCTAssertTrue(summary.contains("18446744073709551615"))
        XCTAssertFalse(summary.contains(RuntimePresentation.text("incident_snapshot_hint")))
        XCTAssertTrue(summary.contains(RuntimePresentation.text("diagnostic_metric_hint")))
    }

    @MainActor
    func testAndroidSignalAndMissingMemoryAreNotRelabeledAsLowMemory() throws {
        let diagnostic = try SystemDiagnostic.androidExit(timestampMillis: 1_700_000_000_000, reasonCode: 2, status: 9,
            processID: 123, importance: 100, pssKiB: nil, rssKiB: 9_007_199_254_740_993)
        let fields = Dictionary(uniqueKeysWithValues: SystemDiagnosticPresentation.fields(diagnostic).map { ($0.title, $0.value) })
        XCTAssertEqual(fields[RuntimePresentation.text("diagnostic_kind")], RuntimePresentation.text("diagnostic_kind_signalTermination"))
        XCTAssertEqual(fields[RuntimePresentation.text("diagnostic_status")], "9")
        XCTAssertEqual(fields[RuntimePresentation.text("diagnostic_pss")], RuntimePresentation.text("runtime_unavailable"))
        XCTAssertEqual(fields[RuntimePresentation.text("diagnostic_rss")], "9007199254740993 KiB")
        XCTAssertNil(fields[RuntimePresentation.text("diagnostic_interval_start")])
        XCTAssertNil(fields[RuntimePresentation.text("env_appBuild")], "Do not invent the historical app build")
        XCTAssertTrue(SystemDiagnosticPresentation.summary(diagnostic).contains(RuntimePresentation.text("diagnostic_android_hint")))
    }

    @MainActor
    func testHangHasDurationWithoutCrashCodesAndManualSnapshotsKeepTheirMeaning() throws {
        let diagnostic = try SystemDiagnostic.metricKit(intervalStartMillis: 100, intervalEndMillis: 200,
            kind: .hang, metadata: [:], hangDurationMillis: 0)
        let fields = Dictionary(uniqueKeysWithValues: SystemDiagnosticPresentation.fields(diagnostic).map { ($0.title, $0.value) })
        XCTAssertEqual(fields[RuntimePresentation.text("diagnostic_hang_duration")], "0 ms")
        XCTAssertNil(fields[RuntimePresentation.text("diagnostic_signal")])
        XCTAssertNil(fields[RuntimePresentation.text("diagnostic_exception_code")])
        let manual = try IncidentSnapshot.capture(), viewer = IncidentSnapshotViewController(snapshot: manual)
        viewer.loadViewIfNeeded()
        XCTAssertEqual(viewer.title, RuntimePresentation.text("incident_manual"))
        XCTAssertEqual(viewer.tableView(viewer.tableView, numberOfRowsInSection: 0), 3)
        XCTAssertEqual(IncidentPresentation.hint(manual), RuntimePresentation.text("incident_snapshot_hint"))
    }
}
#endif

#if canImport(UIKit)
import OsservioCore
@testable import OsservioUI
import UIKit
import XCTest

final class SystemDiagnosticTests: XCTestCase {
    @MainActor
    func testAndroidTraceKeepsExactAddressesAndDistinctMissingStates() throws {
        let trace = try AndroidTombstone.restore(architectureCode: 1, signalNumber: 6, signalCode: -6, threadID: 99, faultAddress: nil,
            frames: Array(repeating: .restore(programCounter: .max, relativeProgramCounter: 9_223_372_036_854_775_808, buildID: "ab01"), count: 128))
        let diagnostic = try SystemDiagnostic.androidExit(timestampMillis: 1000, reasonCode: 5, status: 6, processID: 123,
            importance: 100, pssKiB: nil, rssKiB: nil, tombstoneState: .available, tombstone: trace)
        let fields = Dictionary(uniqueKeysWithValues: SystemDiagnosticPresentation.fields(diagnostic).map { ($0.title, $0.value) })
        XCTAssertEqual(fields[RuntimePresentation.text("env_architecture")], "ARM64")
        XCTAssertEqual(fields[RuntimePresentation.text("tombstone_state")], RuntimePresentation.text("tombstone_available"))
        XCTAssertEqual(fields[RuntimePresentation.text("tombstone_thread_id")], "99")
        XCTAssertEqual(fields[RuntimePresentation.text("crash_fault_address")], RuntimePresentation.text("runtime_unavailable"))
        XCTAssertEqual(fields[RuntimePresentation.text("crash_frame_count")], "128 · " + RuntimePresentation.text("crash_limit_reached"))
        XCTAssertNil(fields[RuntimePresentation.text("crash_report_id")])
        let overview = SystemDiagnosticViewController(diagnostic); overview.loadViewIfNeeded()
        XCTAssertEqual(overview.numberOfSections(in: overview.tableView), 2)
        XCTAssertEqual(overview.tableView(overview.tableView, numberOfRowsInSection: 1), 1)
        XCTAssertEqual(overview.tableView(overview.tableView, cellForRowAt: IndexPath(row: 0, section: 1)).accessoryType, .disclosureIndicator)
        let frames = SignalCrashViewController(trace); frames.loadViewIfNeeded()
        XCTAssertEqual(frames.tableView(frames.tableView, numberOfRowsInSection: 0), 128)
        let row = frames.copyText(at: 127)
        XCTAssertTrue(row.hasPrefix("#127\n")); XCTAssertTrue(row.contains("0xffffffffffffffff")); XCTAssertTrue(row.contains("0x8000000000000000"))
        XCTAssertTrue(row.contains(RuntimePresentation.text("tombstone_build_id") + ": ab01"))
        XCTAssertEqual(Set(SystemDiagnostic.TombstoneState.allCases.map(TombstonePresentation.state)).count, 8)
        XCTAssertEqual(TombstonePresentation.architecture(999), "#999")
    }

    @MainActor
    func testAbsentAndroidStackDoesNotInventNavigationOrZeroAddresses() throws {
        let absent = try SystemDiagnostic.androidExit(timestampMillis: 1000, reasonCode: 5, status: 6, processID: 123,
            importance: 100, pssKiB: nil, rssKiB: nil, tombstoneState: .unavailable, tombstone: nil)
        let overview = SystemDiagnosticViewController(absent); overview.loadViewIfNeeded()
        XCTAssertEqual(overview.numberOfSections(in: overview.tableView), 1)
        let trace = try AndroidTombstone.restore(architectureCode: 1, signalNumber: 11, signalCode: 1, threadID: 99, faultAddress: 0, frames: [])
        let noFrames = try SystemDiagnostic.androidExit(timestampMillis: 1000, reasonCode: 5, status: 11, processID: 123,
            importance: 100, pssKiB: nil, rssKiB: nil, tombstoneState: .available, tombstone: trace)
        let detail = SystemDiagnosticViewController(noFrames); detail.loadViewIfNeeded()
        let cell = detail.tableView(detail.tableView, cellForRowAt: IndexPath(row: 0, section: 1))
        XCTAssertEqual(cell.accessoryType, .none); XCTAssertEqual(cell.detailTextLabel?.text, RuntimePresentation.text("runtime_unavailable"))
        XCTAssertTrue(SystemDiagnosticPresentation.summary(noFrames).contains("0x0"))
    }
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
        XCTAssertEqual(viewer.title, InspectorLocalization.text("ui_problem_details"))
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
        XCTAssertEqual(viewer.title, InspectorLocalization.text("ui_problem_details"))
        XCTAssertEqual(viewer.tableView(viewer.tableView, numberOfRowsInSection: 0), 3)
        XCTAssertEqual(IncidentPresentation.hint(manual), RuntimePresentation.text("incident_snapshot_hint"))
    }

    @MainActor
    func testSignalOverviewAndAddressListsPreserveExactEvidenceAndLimits() throws {
        let crash = try AppleSignalCrash.restore(reportID: "12345678-1234-1234-1234-abcdef123456", timestampMillis: 1_700_000_000_000,
            signal: .segmentationViolation, code: "SEGV_MAPERR", faultAddress: .max, threadNumber: .max,
            frames: Array(repeating: UInt64.max, count: 128),
            images: Array(repeating: .restore(baseAddress: .max, size: 0, uuid: "12345678123412341234abcdef123456",
                                             cpuType: 16_777_228, cpuSubtype: .max), count: 512),
            metadata: [.appBuild: .capture(publicValue: "historical-build")], imageCacheIncomplete: true)
        let diagnostic = SystemDiagnostic.appleSignal(crash)
        let fields = Dictionary(uniqueKeysWithValues: SystemDiagnosticPresentation.fields(diagnostic).map { ($0.title, $0.value) })
        XCTAssertEqual(fields[RuntimePresentation.text("crash_fault_address")], "0xffffffffffffffff")
        XCTAssertEqual(fields[RuntimePresentation.text("crash_thread")], "4294967295")
        XCTAssertEqual(fields[RuntimePresentation.text("env_appBuild")], "historical-build")
        XCTAssertEqual(fields[RuntimePresentation.text("crash_frame_count")], "128 · " + RuntimePresentation.text("crash_limit_reached"))
        XCTAssertEqual(fields[RuntimePresentation.text("crash_image_cache")], RuntimePresentation.text("crash_image_cache_excluded"))
        XCTAssertNil(fields[RuntimePresentation.text("diagnostic_interval_start")])
        XCTAssertEqual(SystemDiagnosticPresentation.hint(diagnostic), RuntimePresentation.text("crash_hint"))
        let overview = SystemDiagnosticViewController(diagnostic); overview.loadViewIfNeeded()
        XCTAssertEqual(overview.numberOfSections(in: overview.tableView), 2)
        for row in 0..<2 {
            let cell = overview.tableView(overview.tableView, cellForRowAt: IndexPath(row: row, section: 1))
            XCTAssertEqual(cell.accessoryType, .disclosureIndicator)
        }
        let frames = SignalCrashViewController(crash, content: .frames); frames.loadViewIfNeeded()
        XCTAssertEqual(frames.tableView(frames.tableView, numberOfRowsInSection: 0), 128)
        XCTAssertEqual(frames.copyText(at: 127), "#127\n0xffffffffffffffff")
        let images = SignalCrashViewController(crash, content: .images); images.loadViewIfNeeded()
        XCTAssertEqual(images.tableView(images.tableView, numberOfRowsInSection: 0), 512)
        let image = images.copyText(at: 511)
        XCTAssertTrue(image.contains("#511 · 12345678123412341234abcdef123456")); XCTAssertTrue(image.contains("18446744073709551615"))
        XCTAssertTrue(image.contains(RuntimePresentation.text("crash_image_size") + ": " + RuntimePresentation.text("runtime_unavailable")))
        XCTAssertFalse(image.contains("0 B"))
    }

    @MainActor
    func testMissingSignalStackHasNoNavigationOrInventedThread() throws {
        let crash = try AppleSignalCrash.restore(reportID: "12345678-1234-1234-1234-abcdef123456", timestampMillis: 1000,
            signal: .abort, code: "#0", faultAddress: 0, threadNumber: nil, frames: [], images: [], metadata: [:], imageCacheIncomplete: false)
        let overview = SystemDiagnosticViewController(.appleSignal(crash)); overview.loadViewIfNeeded()
        for row in 0..<2 {
            let cell = overview.tableView(overview.tableView, cellForRowAt: IndexPath(row: row, section: 1))
            XCTAssertEqual(cell.accessoryType, .none); XCTAssertEqual(cell.detailTextLabel?.text, RuntimePresentation.text("runtime_unavailable"))
        }
        let fields = Dictionary(uniqueKeysWithValues: SystemDiagnosticPresentation.fields(.appleSignal(crash)).map { ($0.title, $0.value) })
        XCTAssertEqual(fields[RuntimePresentation.text("crash_thread")], RuntimePresentation.text("runtime_unavailable"))
        XCTAssertEqual(fields[RuntimePresentation.text("crash_fault_address")], "0x0")
        XCTAssertEqual(fields[RuntimePresentation.text("crash_image_cache")], RuntimePresentation.text("crash_image_cache_no_exclusion"))
    }
}
#endif

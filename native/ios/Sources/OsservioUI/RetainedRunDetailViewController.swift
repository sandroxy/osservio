#if canImport(UIKit)
import OsservioCore
import UIKit

@MainActor
final class RetainedRunDetailViewController: InspectorDetailViewController {
    private let run: RetainedRun
    init(run: RetainedRun) { self.run = run; super.init() }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated); title = RuntimePresentation.text("ui_run_details")
        inspectorPrimaryAction = inspectorCopyItem(target: self, action: #selector(copySummary), label: "archive_copy_summary")
        sections = [Section(title: RuntimePresentation.text("ui_overview"), rows: [
            Row(RuntimePresentation.text("ui_run_kind"), RuntimePresentation.text(run.isCurrent ? "archive_current" : "archive_previous_run")),
            Row(RuntimePresentation.text("ui_run_started"), InspectorValuePresentation.eventTime(run.recordingStartedAtMillis, full: true)),
            Row(RuntimePresentation.text("ui_run_last_event"), InspectorValuePresentation.eventTime(run.lastRecordedAtMillis, full: true)),
            Row(RuntimePresentation.text("ui_run_events"), String(run.eventCount)),
            Row(RuntimePresentation.text("ui_run_evicted"), String(run.evictedEvents))
        ], footer: RuntimePresentation.text("archive_snapshot")),
        Section(title: RuntimePresentation.text("ui_technical_details"), rows: [
            Row(RuntimePresentation.text("ui_platform"), run.platform.rawValue),
            Row(RuntimePresentation.text("env_processID"), String(run.processID)),
            Row(RuntimePresentation.text("ui_run_identity"), run.id)
        ])]
    }
    @objc private func copySummary() { UIPasteboard.general.string = PersistencePresentation.run(run) }
}
#endif

#if canImport(UIKit)
import OsservioCore
import UIKit

/// Reads fixed counters once per visit; viewing diagnostics never subscribes to capture.
@MainActor
final class InspectorCaptureStatisticsViewController: InspectorDetailViewController {
    private let statistics: CaptureStatistics
    private lazy var style = InspectorStyleObserver { [weak self] in self?.render() }
    private var snapshot: CaptureStatistics.Snapshot
    init(_ statistics: CaptureStatistics) {
        self.statistics = statistics; snapshot = statistics.snapshot(); super.init()
    }
    @available(*, unavailable) required init?(coder: NSCoder) { return nil }
    override func viewDidLoad() { super.viewDidLoad(); style.changed() }
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated); snapshot = statistics.snapshot(); render()
    }
    private func render() {
        title = InspectorLocalization.text("stats_title")
        var values = [Section(title: "", rows: [Row(InspectorLocalization.text("stats_window"), InspectorLocalization.date(snapshot.startedAtMillis))],
            footer: InspectorLocalization.text("stats_hint") + (snapshot.isIncomplete ? "\n" + InspectorLocalization.text("stats_incomplete") : ""))]
        let sources: [(CaptureStatistics.Source, String)] = [(.logs, "logs"), (.http, "http"), (.webSocket, "web_socket"), (.breadcrumbs, "breadcrumbs")]
        let outcomes: [(CaptureStatistics.Outcome, String)] = [(.accepted, "accepted"), (.inactive, "inactive"), (.busy, "busy"),
            (.rateLimited, "rate_limited"), (.activeLimit, "active_limit"), (.oversized, "oversized"), (.protectedCapacity, "protected_capacity"),
            (.invalid, "invalid"), (.missing, "missing"), (.stale, "stale"), (.sinkFailure, "sink_failure"), (.sequenceExhausted, "sequence_exhausted")]
        for (source, key) in sources {
            let rows = outcomes.compactMap { outcome, label -> Row? in
                let count = snapshot.count(source, outcome)
                return count > 0 ? Row(InspectorLocalization.text("stats_" + label), String(count)) : nil
            }
            if !rows.isEmpty { values.append(Section(title: InspectorLocalization.text("stats_" + key), rows: rows)) }
        }
        if values.count == 1 { values.append(Section(title: "", rows: [Row(InspectorLocalization.text("stats_empty"))])) }
        sections = values
    }
}
#endif

#if canImport(UIKit)
import OsservioCore
import UIKit

/// Frozen neighboring logs keep the source filter and reading position untouched.
@MainActor
final class LogContextViewController: UITableViewController {
    private let window: InspectorLogContext
    private let history: (any LogDetailsHistory)?
    private let saved: [String: IncidentPayload]?
    private let table = InspectorTableView(frame: .zero, style: .insetGrouped)
    private var positioned = false
    private lazy var style = InspectorStyleObserver { [weak self] in self?.relabel() }
    init(window: InspectorLogContext, history: (any LogDetailsHistory)?, saved: [String: IncidentPayload]?) {
        self.window = window; self.history = history; self.saved = saved; super.init(style: .plain)
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func loadView() { super.loadView(); tableView = table }
    override func viewDidLoad() {
        super.viewDidLoad(); table.rowHeight = UITableView.automaticDimension; table.estimatedRowHeight = 100
        style.changed()
    }
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated); relabel()
    }
    private func relabel() {
        InspectorTheme.apply(self)
        title = InspectorLocalization.text("ui_log_neighbors"); table.reloadData()
    }
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if !positioned { positioned = true; table.scrollToRow(at: IndexPath(row: window.targetIndex, section: 0), at: .middle, animated: false) }
    }
    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? { InspectorLocalization.text("ui_log_neighbors_hint") }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { window.events.count }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = table.dequeueReusableCell(withIdentifier: "log") as? InspectorEventCell ?? InspectorEventCell(style: .default, reuseIdentifier: "log")
        var row = InspectorRecordPresentation.log(window.events[indexPath.row])
        if indexPath.row == window.targetIndex { row = .init(badge: row.badge + " · " + InspectorLocalization.text("ui_selected_record"), title: row.title, subtitle: row.subtitle, time: row.time, tone: row.tone) }
        cell.configure(row); return cell
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        table.deselectRow(at: indexPath, animated: true)
        inspectorPush(LogDetailViewController(event: window.events[indexPath.row], history: history, saved: saved))
    }
}
#endif

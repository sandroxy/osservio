#if canImport(UIKit)
import OsservioCore
import UIKit

/// Baseline policy is independent of the root debug switch and inspector visibility.
@MainActor
final class InspectorCaptureSettingsViewController: UITableViewController {
    private let baseline: CaptureController?
    private let controllers: [(String, CaptureController)]
    private let baselineSwitch = UISwitch()
    private var statuses: [String] = []
    private lazy var loop = InspectorRefreshLoop { [weak self] in self?.refresh() }
    private lazy var style = InspectorStyleObserver { [weak self] in self?.relabel() }
    init(baseline: CaptureController?, controllers: [(String, CaptureController)]) {
        self.baseline = baseline; self.controllers = controllers; super.init(style: .insetGrouped)
    }
    @available(*, unavailable) required init?(coder: NSCoder) { return nil }
    override func viewDidLoad() {
        super.viewDidLoad(); tableView.rowHeight = UITableView.automaticDimension; tableView.estimatedRowHeight = 64
        baselineSwitch.addTarget(self, action: #selector(toggleBaseline), for: .valueChanged); style.changed()
    }
    private func relabel() {
        InspectorTheme.apply(self); title = InspectorLocalization.text("ui_capture_settings")
        baselineSwitch.onTintColor = InspectorTheme.filledAccent; baselineSwitch.accessibilityLabel = InspectorLocalization.text("ui_baseline_capture")
        statuses = []; refresh(); tableView.reloadData()
    }
    override func viewWillAppear(_ animated: Bool) { super.viewWillAppear(animated); loop.show(in: view) }
    override func viewDidAppear(_ animated: Bool) { super.viewDidAppear(animated); loop.show(in: view) }
    override func viewWillDisappear(_ animated: Bool) { super.viewWillDisappear(animated); loop.hide() }
    private func refresh() {
        let baselineStatus = baseline?.status(); baselineSwitch.isEnabled = baselineStatus != nil
        if let baselineStatus { baselineSwitch.setOn(baselineStatus.baselineEnabled, animated: false) }
        let values = controllers.map { _, controller in controller.status().map { InspectorDebugControl.describe($0, includeBaseline: true) } ?? InspectorLocalization.text("busy") }
        if values != statuses { statuses = values; tableView.reloadData() }
    }
    @objc private func toggleBaseline() {
        if baseline?.setBaselineEnabled(baselineSwitch.isOn) != true { inspectorNotice(InspectorLocalization.text("busy")) }
        refresh()
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        guard indexPath.section == 1 else { return }
        inspectorPush(InspectorCaptureStatisticsViewController(controllers[indexPath.row].1.statistics))
    }
    override func numberOfSections(in tableView: UITableView) -> Int { 2 }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { section == 0 ? (baseline == nil ? 0 : 1) : controllers.count }
    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? { section == 0 ? nil : InspectorLocalization.text("ui_capture_status") }
    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        section == 0 && baseline != nil ? InspectorLocalization.text("crumb_baseline_hint") : nil
    }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil); PersistencePresentation.style(cell)
        cell.backgroundColor = InspectorTheme.surface; cell.selectionStyle = .none
        if indexPath.section == 0 { cell.textLabel?.text = InspectorLocalization.text("ui_baseline_capture"); cell.accessoryView = baselineSwitch }
        else {
            cell.selectionStyle = .default; cell.accessoryType = .disclosureIndicator
            cell.accessibilityTraits.insert(.button)
            let key = controllers[indexPath.row].0
            cell.textLabel?.text = controllers.count == 1 ? "Osservio" : ["HTTP", "WebSocket"].contains(key) ? key : InspectorLocalization.text(key)
            cell.detailTextLabel?.text = indexPath.row < statuses.count ? statuses[indexPath.row] : InspectorLocalization.text("busy")
        }
        return cell
    }
}
#endif

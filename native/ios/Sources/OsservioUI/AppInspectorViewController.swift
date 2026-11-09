#if canImport(UIKit)
import OsservioCore
import UIKit

@MainActor
final class AppInspectorViewController: UITableViewController {
    private let environment: (any EnvironmentProvider)?
    private let breadcrumbs: (recorder: BreadcrumbRecorder, history: any BreadcrumbHistory)?
    private let archive: (any BreadcrumbArchive)?
    private let controllers: [(String, CaptureController)]
    private lazy var style = InspectorStyleObserver { [weak self] in self?.relabel() }
    private lazy var loop = InspectorRefreshLoop { [weak self] in self?.refreshStatus() }
    private var statuses: [String] = []
    init(environment: (any EnvironmentProvider)?, breadcrumbs: (BreadcrumbRecorder, any BreadcrumbHistory)?,
         archive: (any BreadcrumbArchive)?, controllers: [(String, CaptureController)]) {
        self.environment = environment; self.breadcrumbs = breadcrumbs; self.archive = archive; self.controllers = controllers
        super.init(style: .insetGrouped)
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func viewDidLoad() {
        super.viewDidLoad(); tableView.rowHeight = UITableView.automaticDimension; tableView.estimatedRowHeight = 62
        style.changed()
    }
    private func relabel() { InspectorTheme.apply(self); title = InspectorLocalization.text("ui_app"); statuses = []; refreshStatus() }
    override func viewWillAppear(_ animated: Bool) { super.viewWillAppear(animated); loop.show(in: view) }
    override func viewDidAppear(_ animated: Bool) { super.viewDidAppear(animated); loop.show(in: view) }
    override func viewWillDisappear(_ animated: Bool) { super.viewWillDisappear(animated); loop.hide() }
    private func refreshStatus() {
        let values = controllers.map { _, controller in controller.status().map { InspectorLocalization.text("http_" + $0.mode.rawValue) } ?? InspectorLocalization.text("busy") }
        if values != statuses { statuses = values; tableView.reloadData() }
    }
    override func numberOfSections(in tableView: UITableView) -> Int { 3 }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { section == 0 ? 3 : section == 1 ? 2 : controllers.count }
    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        InspectorLocalization.text(["ui_app_device", "ui_inspector", "ui_capture_status"][section])
    }
    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        section == 1 ? InspectorLocalization.text("ui_preferences_hint") : nil
    }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
        PersistencePresentation.style(cell); cell.backgroundColor = InspectorTheme.surface
        var available = true
        if indexPath.section == 0 {
            let keys = ["ui_environment", "ui_current_timeline", "ui_saved_runs"]
            cell.textLabel?.text = InspectorLocalization.text(keys[indexPath.row])
            cell.imageView?.image = UIImage(systemName: ["iphone", "clock", "archivebox"][indexPath.row])
            available = indexPath.row == 0 ? environment != nil : indexPath.row == 1 ? breadcrumbs != nil : archive != nil
            if !available { cell.detailTextLabel?.text = InspectorLocalization.text("ui_not_configured") }
        } else if indexPath.section == 1 {
            cell.textLabel?.text = InspectorLocalization.text(indexPath.row == 0 ? "ui_language" : "ui_appearance")
            cell.imageView?.image = UIImage(systemName: indexPath.row == 0 ? "globe" : "circle.lefthalf.fill")
            cell.detailTextLabel?.text = InspectorLocalization.text(indexPath.row == 0
                ? InspectorPreferences.shared.language == .chinese ? "ui_chinese" : "ui_english"
                : InspectorPreferences.shared.appearance == .light ? "ui_light" : "ui_dark")
        } else {
            let key = controllers[indexPath.row].0
            cell.textLabel?.text = controllers.count == 1 ? "Osservio" : ["HTTP", "WebSocket"].contains(key) ? key : InspectorLocalization.text(key)
            cell.detailTextLabel?.text = indexPath.row < statuses.count ? statuses[indexPath.row] : InspectorLocalization.text("busy")
        }
        cell.imageView?.tintColor = InspectorTheme.accent
        cell.accessoryType = available && indexPath.section < 2 ? .disclosureIndicator : .none
        cell.isUserInteractionEnabled = available && indexPath.section < 2
        cell.textLabel?.textColor = available ? .label : InspectorTheme.secondary
        cell.selectionStyle = cell.isUserInteractionEnabled ? .default : .none
        return cell
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        if indexPath.section == 0 {
            switch indexPath.row {
            case 0: if let environment { inspectorPush(EnvironmentInspectorViewController(provider: environment)) }
            case 1: if let breadcrumbs { inspectorPush(BreadcrumbInspectorViewController(recorder: breadcrumbs.recorder, history: breadcrumbs.history)) }
            default: if let archive { inspectorPush(BreadcrumbArchiveViewController(archive: archive)) }
            }
        } else if indexPath.section == 1 {
            inspectorPush(InspectorPreferenceViewController(language: indexPath.row == 0))
        }
    }
}

@MainActor
final class InspectorUnavailableViewController: InspectorDetailViewController {
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated); title = InspectorLocalization.text("incident_title")
        sections = [Section(title: InspectorLocalization.text("ui_no_archive"), rows: [Row(InspectorLocalization.text("ui_no_archive_hint"))])]
    }
}
#endif

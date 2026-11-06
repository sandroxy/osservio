#if canImport(UIKit)
import OsservioCore
import UIKit

/// Immutable saved evidence; opening it never refreshes attachments with new host observations.
@MainActor
final class IncidentSnapshotViewController: UITableViewController, UISearchResultsUpdating {
    private let snapshot: IncidentSnapshot
    private var events: [BreadcrumbEvent]
    private let search = UISearchController(searchResultsController: nil)
    private let exportStatus = UILabel()
    private var sharing: IncidentSharePresentation?
    init(snapshot: IncidentSnapshot, exporter: (any IncidentExporter)? = nil) {
        self.snapshot = snapshot; events = (snapshot.breadcrumbs?.events ?? []).reversed()
        super.init(style: .insetGrouped)
        if let exporter {
            sharing = IncidentSharePresentation(owner: self, snapshot: snapshot, exporter: exporter) { [weak self] in self?.showExportStatus($0) }
        }
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func viewDidLoad() {
        super.viewDidLoad(); title = RuntimePresentation.text("incident_manual")
        tableView.rowHeight = UITableView.automaticDimension; tableView.estimatedRowHeight = 80
        search.searchResultsUpdater = self; search.obscuresBackgroundDuringPresentation = false
        search.searchBar.placeholder = RuntimePresentation.text("crumb_search")
        navigationItem.searchController = search; navigationItem.hidesSearchBarWhenScrolling = false; definesPresentationContext = true
        let copy = UIBarButtonItem(title: RuntimePresentation.text("archive_copy_summary"), style: .plain,
            target: self, action: #selector(copySummary))
        navigationItem.rightBarButtonItems = sharing.map { [$0.button, copy] } ?? [copy]
        exportStatus.font = .preferredFont(forTextStyle: .footnote); exportStatus.adjustsFontForContentSizeCategory = true
        exportStatus.numberOfLines = 0; exportStatus.textColor = .secondaryLabel
    }
    override func viewWillDisappear(_ animated: Bool) { super.viewWillDisappear(animated); sharing?.cancel() }
    override func viewDidLayoutSubviews() { super.viewDidLayoutSubviews(); layoutExportStatus() }
    private func showExportStatus(_ message: String?) {
        exportStatus.text = message
        if message == nil { tableView.tableHeaderView = nil }
        else {
            if tableView.tableHeaderView == nil {
                let header = UIView(); header.addSubview(exportStatus); tableView.tableHeaderView = header
            }
            layoutExportStatus(); UIAccessibility.post(notification: .announcement, argument: message)
        }
    }
    private func layoutExportStatus() {
        guard let header = tableView.tableHeaderView else { return }
        let width = tableView.bounds.width, inset: CGFloat = 20
        let height = exportStatus.sizeThatFits(CGSize(width: max(0, width - inset * 2), height: .greatestFiniteMagnitude)).height + 24
        exportStatus.frame = CGRect(x: inset, y: 12, width: max(0, width - inset * 2), height: height - 24)
        if header.frame.width != width || header.frame.height != height {
            header.frame = CGRect(x: 0, y: 0, width: width, height: height); tableView.tableHeaderView = header
        }
    }
    @objc private func copySummary() { UIPasteboard.general.string = IncidentPresentation.summary(snapshot) }
    func updateSearchResults(for searchController: UISearchController) {
        let query = searchController.searchBar.text ?? ""
        events = (snapshot.breadcrumbs?.events ?? []).reversed().filter {
            query.isEmpty || $0.label.localizedCaseInsensitiveContains(query) || RuntimePresentation.kind($0).localizedCaseInsensitiveContains(query)
                || ($0.message?.localizedCaseInsensitiveContains(query) ?? false)
        }
        tableView.reloadData()
    }
    override func numberOfSections(in tableView: UITableView) -> Int { 4 }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        if section == 0 { return 3 }
        if section == 1 { return snapshot.environment == nil ? 0 : 1 }
        if section == 2 { return 3 }
        return events.count
    }
    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        if section == 0 { return RuntimePresentation.text("incident_manual") }
        if section == 1 {
            return RuntimePresentation.text("runtime_environment")
        }
        if section == 2 { return RuntimePresentation.text("incident_evidence_title") }
        return RuntimePresentation.text("runtime_breadcrumbs") + "\n" + IncidentPresentation.counts(snapshot.breadcrumbs)
    }
    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        if section == 0 { return RuntimePresentation.text("incident_snapshot_hint") }
        if section == 1 && snapshot.environment == nil { return RuntimePresentation.text("incident_not_attached") }
        if section == 3 && events.isEmpty && snapshot.breadcrumbs != nil { return RuntimePresentation.text("ws_no_matches") }
        return nil
    }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "incident-evidence") ?? UITableViewCell(style: .subtitle, reuseIdentifier: "incident-evidence")
        PersistencePresentation.style(cell); cell.selectionStyle = .none; cell.accessoryType = .none
        cell.accessibilityTraits = .staticText
        if indexPath.section == 0 {
            let titles = [RuntimePresentation.text("incident_frozen_time"), "ID", RuntimePresentation.text("incident_note")]
            let values = [RuntimePresentation.date(snapshot.timestampMillis), snapshot.id, IncidentPresentation.note(snapshot.note)]
            cell.textLabel?.text = titles[indexPath.row]; cell.detailTextLabel?.text = values[indexPath.row]
        } else if indexPath.section == 1, let environment = snapshot.environment {
            cell.textLabel?.text = RuntimePresentation.text("runtime_snapshot")
            cell.detailTextLabel?.text = RuntimePresentation.date(environment.timestampMillis) + " · " + environment.platform.rawValue
            cell.selectionStyle = .default; cell.accessoryType = .disclosureIndicator; cell.accessibilityTraits = .button
        } else if indexPath.section == 2 {
            let counts = [snapshot.logs?.events.count, snapshot.http?.events.count, snapshot.webSockets?.events.count]
            cell.textLabel?.text = [RuntimePresentation.text("logs"), "HTTP", RuntimePresentation.text("ws_title")][indexPath.row]
            cell.detailTextLabel?.text = IncidentPresentation.count(counts[indexPath.row])
            if counts[indexPath.row] != nil { cell.selectionStyle = .default; cell.accessoryType = .disclosureIndicator; cell.accessibilityTraits = .button }
        } else {
            let event = events[indexPath.row]
            cell.textLabel?.text = RuntimePresentation.kind(event) + " · " + event.label
            cell.detailTextLabel?.text = RuntimePresentation.date(event.timestampMillis) + " · #\(event.sequence)" + (event.message.map { "\n" + $0 } ?? "")
            cell.textLabel?.numberOfLines = 2; cell.detailTextLabel?.numberOfLines = 3
            cell.selectionStyle = .default; cell.accessoryType = .disclosureIndicator; cell.accessibilityTraits = .button
        }
        return cell
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        if indexPath.section == 1, let environment = snapshot.environment {
            navigationController?.pushViewController(EnvironmentSnapshotViewController(snapshot: environment), animated: true)
        }
        if indexPath.section == 2 {
            let evidence: IncidentEvidenceViewController.Evidence?
            switch indexPath.row {
            case 0: evidence = snapshot.logs.map(IncidentEvidenceViewController.Evidence.logs)
            case 1: evidence = snapshot.http.map(IncidentEvidenceViewController.Evidence.http)
            default: evidence = snapshot.webSockets.map(IncidentEvidenceViewController.Evidence.webSockets)
            }
            if let evidence { navigationController?.pushViewController(IncidentEvidenceViewController(evidence), animated: true) }
        }
        if indexPath.section == 3 { navigationController?.pushViewController(BreadcrumbDetailViewController(event: events[indexPath.row]), animated: true) }
    }
}
#endif

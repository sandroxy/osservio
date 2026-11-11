#if canImport(UIKit)
import OsservioCore
import UIKit

/// Immutable saved evidence; opening it never refreshes attachments with new host observations.
@MainActor
final class IncidentSnapshotViewController: UITableViewController {
    private let snapshot: IncidentSnapshot
    private var events: [BreadcrumbEvent]
    private let search = UISearchTextField()
    private let header = UIStackView()
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
    override func loadView() { super.loadView(); tableView = InspectorTableView(frame: .zero, style: .insetGrouped) }
    override func viewDidLoad() {
        super.viewDidLoad(); InspectorTheme.apply(self); title = InspectorLocalization.text("ui_problem_details")
        tableView.rowHeight = UITableView.automaticDimension; tableView.estimatedRowHeight = 80
        search.placeholder = RuntimePresentation.text("ui_search_breadcrumbs")
        search.font = .preferredFont(forTextStyle: .body); search.adjustsFontForContentSizeCategory = true
        search.autocorrectionType = .no; search.autocapitalizationType = .none; search.returnKeyType = .done
        search.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        search.addTarget(self, action: #selector(searchChanged), for: .editingChanged)
        search.addTarget(self, action: #selector(endSearch), for: .editingDidEndOnExit)
        header.axis = .vertical; header.spacing = 10; header.isLayoutMarginsRelativeArrangement = true
        header.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 12, leading: 20, bottom: 8, trailing: 20)
        header.addArrangedSubview(exportStatus); exportStatus.isHidden = true

        tableView.tableHeaderView = header; tableView.keyboardDismissMode = .onDrag
        let copy = UIBarButtonItem(image: UIImage(systemName: "doc.on.doc"), style: .plain,
            target: self, action: #selector(copySummary))
        copy.accessibilityLabel = RuntimePresentation.text("archive_copy_summary")
        inspectorActions = sharing.map { [$0.button, copy] } ?? [copy]
        exportStatus.font = .preferredFont(forTextStyle: .footnote); exportStatus.adjustsFontForContentSizeCategory = true
        exportStatus.numberOfLines = 0; exportStatus.textColor = .secondaryLabel
    }
    override func viewWillDisappear(_ animated: Bool) { super.viewWillDisappear(animated); sharing?.cancel(); view.endEditing(true) }
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated); InspectorTheme.apply(self)
        title = InspectorLocalization.text("ui_problem_details")
        search.placeholder = RuntimePresentation.text("ui_search_breadcrumbs")
        inspectorActions?.last?.accessibilityLabel = RuntimePresentation.text("archive_copy_summary")
        sharing?.button.accessibilityLabel = RuntimePresentation.text("incident_share"); tableView.reloadData()
    }
    private func showExportStatus(_ message: String?) {
        exportStatus.text = message; exportStatus.isHidden = message == nil
        tableView.setNeedsLayout()
        if let message { UIAccessibility.post(notification: .announcement, argument: message) }
    }
    @objc private func endSearch() { search.resignFirstResponder() }
    @objc private func copySummary() { UIPasteboard.general.string = IncidentPresentation.summary(snapshot) }
    @objc private func searchChanged() {
        guard search.markedTextRange == nil else { return }
        let query = String((search.text ?? "").prefix(512)); if search.text != query { search.text = query }
        let previousCount = events.count
        events = (snapshot.breadcrumbs?.events ?? []).reversed().filter {
            query.isEmpty || $0.label.localizedCaseInsensitiveContains(query) || RuntimePresentation.kind($0).localizedCaseInsensitiveContains(query)
                || ($0.message?.localizedCaseInsensitiveContains(query) ?? false)
        }
        // Keep the section header (and its active editor) attached while results change.
        UIView.performWithoutAnimation {
            tableView.performBatchUpdates {
                tableView.deleteRows(at: (0..<previousCount).map { IndexPath(row: $0, section: 3) }, with: .none)
                tableView.insertRows(at: events.indices.map { IndexPath(row: $0, section: 3) }, with: .none)
            }
        }
    }
    override func numberOfSections(in tableView: UITableView) -> Int { 4 }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        if section == 0 { return snapshot.systemDiagnostic == nil ? 3 : 4 }
        if section == 1 { return snapshot.environment == nil ? 0 : 1 }
        if section == 2 { return 3 }
        return events.count
    }
    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        if section == 0 { return IncidentPresentation.title(snapshot.systemDiagnostic?.kind) }
        if section == 1 {
            return RuntimePresentation.text("runtime_environment")
        }
        if section == 2 { return RuntimePresentation.text("incident_evidence_title") }
        return RuntimePresentation.text("runtime_breadcrumbs") + "\n" + IncidentPresentation.breadcrumbSummary(snapshot.breadcrumbs)
    }
    override func tableView(_ tableView: UITableView, viewForHeaderInSection section: Int) -> UIView? {
        guard section == 3, snapshot.breadcrumbs != nil else { return nil }
        let label = InspectorTheme.label(.caption1, secondary: true)
        label.text = RuntimePresentation.text("runtime_breadcrumbs") + " · " + IncidentPresentation.breadcrumbSummary(snapshot.breadcrumbs)
        let stack = UIStackView(arrangedSubviews: [label, search]); stack.axis = .vertical; stack.spacing = 10
        stack.isLayoutMarginsRelativeArrangement = true
        stack.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 20, leading: 20, bottom: 10, trailing: 20)
        return stack
    }
    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        if section == 0 { return IncidentPresentation.hint(snapshot) }
        if section == 1 && snapshot.environment == nil { return RuntimePresentation.text("incident_not_attached") }
        if section == 3 && events.isEmpty && snapshot.breadcrumbs != nil { return RuntimePresentation.text("ws_no_matches") }
        return nil
    }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "incident-evidence") ?? UITableViewCell(style: .subtitle, reuseIdentifier: "incident-evidence")
        PersistencePresentation.style(cell); cell.selectionStyle = .none; cell.accessoryType = .none
        cell.accessibilityTraits = .staticText
        if indexPath.section == 0 {
            if indexPath.row == 3, let diagnostic = snapshot.systemDiagnostic {
                cell.textLabel?.text = RuntimePresentation.text("diagnostic_title")
                cell.detailTextLabel?.text = SystemDiagnosticPresentation.kind(diagnostic.kind)
                cell.selectionStyle = .default; cell.accessoryType = .disclosureIndicator; cell.accessibilityTraits = .button
                return cell
            }
            let titles = [IncidentPresentation.timeLabel(snapshot.systemDiagnostic?.kind), RuntimePresentation.text("ui_record_identity"), RuntimePresentation.text("incident_note")]
            let values = [InspectorValuePresentation.eventTime(snapshot.timestampMillis, full: true), InspectorRecordPresentation.identity(snapshot.id), InspectorRecordPresentation.compact(IncidentPresentation.note(snapshot.note), limit: 320)]
            cell.textLabel?.text = titles[indexPath.row]; cell.detailTextLabel?.text = values[indexPath.row]
            if indexPath.row == 1 || indexPath.row == 2 && snapshot.note?.isPrivate == false { cell.selectionStyle = .default; cell.accessoryType = .disclosureIndicator; cell.accessibilityTraits = .button }
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
            cell.textLabel?.text = RuntimePresentation.breadcrumbTitle(event)
            cell.detailTextLabel?.text = InspectorValuePresentation.eventTime(event.timestampMillis) + "\n" + RuntimePresentation.breadcrumbSubtitle(event)
            cell.textLabel?.numberOfLines = 2; cell.detailTextLabel?.numberOfLines = 3
            cell.selectionStyle = .default; cell.accessoryType = .disclosureIndicator; cell.accessibilityTraits = .button
        }
        return cell
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        if indexPath.section == 0, indexPath.row == 3, let diagnostic = snapshot.systemDiagnostic {
            navigationController?.pushViewController(SystemDiagnosticViewController(diagnostic), animated: true)
        }
        if indexPath.section == 0 && (indexPath.row == 1 || indexPath.row == 2 && snapshot.note?.isPrivate == false) {
            let content = InspectorReadContent(text: indexPath.row == 1 ? snapshot.id : snapshot.note?.text ?? "",
                notice: { [snapshot] in indexPath.row == 2 && snapshot.note?.isTruncated == true ? RuntimePresentation.text("truncated") : "" },
                context: { [snapshot] in IncidentPresentation.title(snapshot.systemDiagnostic?.kind) + " · " + InspectorValuePresentation.eventTime(snapshot.timestampMillis, full: true) })
            inspectorPush(InspectorContentViewController(titleKey: indexPath.row == 1 ? "ui_record_identity" : "incident_note", value: content))
        }
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

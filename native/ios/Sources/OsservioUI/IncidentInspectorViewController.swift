#if canImport(UIKit)
import OsservioCore
import UIKit

/// Saved incident browsing and manual marking. The host owns persistence, histories, and presentation.
@MainActor
public final class IncidentInspectorViewController: UITableViewController {
    private let archive: any IncidentArchive
    private let environment: any EnvironmentProvider
    private let breadcrumbs: any BreadcrumbHistory
    private let logs: (any LogHistory)?
    private let http: (any HTTPHistory)?
    private let webSockets: (any WebSocketHistory)?
    private let exporter: (any IncidentExporter)?
    private var snapshots: [IncidentSummary] = []
    private var reading = false
    private var generation = 0
    private var updatedAt: Int64?
    private var notice = RuntimePresentation.text("incident_archive_hint")
    private var justSaved = false
    public init(archive: any IncidentArchive, environment: any EnvironmentProvider, breadcrumbs: any BreadcrumbHistory,
                logs: (any LogHistory)? = nil, http: (any HTTPHistory)? = nil, webSockets: (any WebSocketHistory)? = nil,
                exporter: (any IncidentExporter)? = nil) {
        self.archive = archive; self.environment = environment; self.breadcrumbs = breadcrumbs
        self.logs = logs; self.http = http; self.webSockets = webSockets; self.exporter = exporter
        super.init(style: .insetGrouped)
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    public override func viewDidLoad() {
        super.viewDidLoad(); title = RuntimePresentation.text("incident_title")
        tableView.rowHeight = UITableView.automaticDimension; tableView.estimatedRowHeight = 80
    }
    public override func viewWillAppear(_ animated: Bool) { super.viewWillAppear(animated); read() }
    public override func viewWillDisappear(_ animated: Bool) { super.viewWillDisappear(animated); generation += 1; reading = false }
    private func read() {
        guard !reading else { return }
        reading = true; generation += 1; let request = generation
        notice = RuntimePresentation.text("archive_reading"); tableView.reloadData()
        archive.readIncidents { [weak self] result in
            Task { @MainActor [weak self] in
                guard let self, self.generation == request else { return }
                self.reading = false
                switch result {
                case .success(let snapshots):
                    self.snapshots = snapshots; self.updatedAt = Int64(Date().timeIntervalSince1970 * 1_000)
                    self.notice = (self.justSaved ? RuntimePresentation.text("incident_saved") + "\n" : "")
                        + RuntimePresentation.text("archive_loaded") + " · " + RuntimePresentation.date(self.updatedAt!)
                    self.justSaved = false
                case .failure(let error): self.failed(error)
                }
                self.tableView.reloadData()
            }
        }
    }
    private func failed(_ error: PersistenceError) {
        notice = PersistencePresentation.error(error)
        if let updatedAt { notice += "\n" + RuntimePresentation.text("runtime_previous") + " · " + RuntimePresentation.date(updatedAt) }
    }
    public override func numberOfSections(in tableView: UITableView) -> Int { 2 }
    public override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { section == 0 ? 3 : snapshots.count }
    public override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        if section == 0 { return notice + "\n\n" + PersistencePresentation.status(archive.persistenceStatus(), formatKey: "incident_status") }
        return (snapshots.isEmpty && updatedAt != nil ? RuntimePresentation.text("incident_empty") + "\n\n" : "") + RuntimePresentation.text("incident_archive_hint")
    }
    public override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "incident") ?? UITableViewCell(style: .subtitle, reuseIdentifier: "incident")
        PersistencePresentation.style(cell); cell.accessoryType = .none; cell.accessibilityTraits = .button
        let enabled = !reading && !(indexPath.section == 0 && indexPath.row == 2 && snapshots.isEmpty)
        cell.textLabel?.textColor = enabled ? .label : .secondaryLabel; cell.isUserInteractionEnabled = enabled
        if indexPath.section == 0 {
            cell.textLabel?.text = RuntimePresentation.text(["incident_mark", "incident_refresh", "incident_delete_all"][indexPath.row])
            cell.detailTextLabel?.text = nil
            cell.textLabel?.textColor = !enabled ? .secondaryLabel : indexPath.row == 2 ? .systemRed : view.tintColor
        } else {
            let snapshot = snapshots[indexPath.row]
            cell.textLabel?.text = snapshot.diagnosticKind.map(SystemDiagnosticPresentation.kind) ?? RuntimePresentation.date(snapshot.timestampMillis)
            cell.detailTextLabel?.text = IncidentPresentation.listDetail(snapshot)
            cell.detailTextLabel?.numberOfLines = 3; cell.accessoryType = .disclosureIndicator
        }
        if !enabled { cell.accessibilityTraits.insert(.notEnabled) }
        return cell
    }
    public override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        guard !reading else { return }
        if indexPath.section == 0 {
            if indexPath.row == 0 { mark() }
            else if indexPath.row == 1 { read() }
            else { confirmDeletion(id: nil) }
        } else { open(id: snapshots[indexPath.row].id) }
    }
    private func mark() {
        do {
            let mark = try IncidentSnapshot.capture(breadcrumbs: breadcrumbs.snapshot(), breadcrumbTextByteLimit: 32_768)
            let clock = SystemLogClock()
            let logWindow = try logs?.snapshot().map { try IncidentLogs.capture($0, observedAtMillis: clock.wallTimeMillis(), textByteLimit: 16_384) }
            let httpWindow = try http.flatMap { history in
                try history.snapshot().map { try IncidentHTTP.capture($0, observedAtMillis: clock.wallTimeMillis(), history: history, textByteLimit: 16_384) }
            }
            let socketWindow = try webSockets.flatMap { history in
                try history.snapshot().map { try IncidentWebSockets.capture($0, observedAtMillis: clock.wallTimeMillis(), history: history, eventLimit: 100, textByteLimit: 16_384) }
            }
            let snapshot = try IncidentSnapshot.restore(id: mark.id, timestampMillis: mark.timestampMillis, note: nil, environment: nil,
                breadcrumbs: mark.breadcrumbs, logs: logWindow, http: httpWindow, webSockets: socketWindow)
            let editor = IncidentComposerViewController(mark: snapshot, archive: archive, provider: environment,
                httpHistory: http, webSocketHistory: webSockets) { [weak self] in
                self?.justSaved = true; self?.read()
            }
            present(UINavigationController(rootViewController: editor), animated: true)
        } catch { notice = RuntimePresentation.text("incident_mark_failed"); tableView.reloadData() }
    }
    private func open(id: String) {
        reading = true; generation += 1; let request = generation
        notice = RuntimePresentation.text("archive_reading"); tableView.reloadData()
        archive.readIncident(id: id) { [weak self] result in
            Task { @MainActor [weak self] in
                guard let self, self.generation == request else { return }
                self.reading = false
                switch result {
                case .success(let snapshot):
                    if let snapshot { self.navigationController?.pushViewController(IncidentSnapshotViewController(snapshot: snapshot, exporter: self.exporter), animated: true) }
                    else { self.notice = RuntimePresentation.text("incident_missing") }
                case .failure(let error): self.failed(error)
                }
                self.tableView.reloadData()
            }
        }
    }
    public override func tableView(_ tableView: UITableView, trailingSwipeActionsConfigurationForRowAt indexPath: IndexPath) -> UISwipeActionsConfiguration? {
        guard indexPath.section == 1, !reading else { return nil }
        let id = snapshots[indexPath.row].id
        let action = UIContextualAction(style: .destructive, title: RuntimePresentation.text("incident_delete")) { [weak self] _, _, completed in
            self?.confirmDeletion(id: id); completed(false)
        }
        let actions = UISwipeActionsConfiguration(actions: [action]); actions.performsFirstActionWithFullSwipe = false; return actions
    }
    private func confirmDeletion(id: String?) {
        guard !reading else { return }
        let alert = UIAlertController(title: RuntimePresentation.text(id == nil ? "incident_delete_all" : "incident_delete"),
            message: RuntimePresentation.text("incident_delete_confirmation"), preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: RuntimePresentation.text("archive_cancel"), style: .cancel))
        alert.addAction(UIAlertAction(title: RuntimePresentation.text("archive_delete_confirm"), style: .destructive) { [weak self] _ in self?.delete(id: id) })
        present(alert, animated: true)
    }
    private func delete(id: String?) {
        guard !reading else { return }
        reading = true; generation += 1; let request = generation
        notice = RuntimePresentation.text("archive_deleting"); tableView.reloadData()
        let completed: @Sendable (Result<Void, PersistenceError>) -> Void = { [weak self] result in
            Task { @MainActor [weak self] in
                guard let self, self.generation == request else { return }
                self.reading = false
                switch result {
                case .success: self.read()
                case .failure(let error): self.failed(error); self.tableView.reloadData()
                }
            }
        }
        if let id { archive.deleteIncident(id: id, completion: completed) }
        else { archive.deleteAllIncidents(completion: completed) }
    }
}
#endif

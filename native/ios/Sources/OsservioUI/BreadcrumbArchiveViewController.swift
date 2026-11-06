#if canImport(UIKit)
import OsservioCore
import UIKit

/// Queries committed evidence on demand. Visibility never flushes or changes capture.
@MainActor
final class BreadcrumbArchiveViewController: UITableViewController {
    private let archive: any BreadcrumbArchive
    private var runs: [RetainedRun] = []
    private var reading = false
    private var generation = 0
    private var updatedAt: Int64?
    private var notice = RuntimePresentation.text("archive_hint")
    init(archive: any BreadcrumbArchive) { self.archive = archive; super.init(style: .insetGrouped) }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func viewDidLoad() {
        super.viewDidLoad(); tableView.rowHeight = UITableView.automaticDimension; tableView.estimatedRowHeight = 76
    }
    override func viewWillAppear(_ animated: Bool) { super.viewWillAppear(animated); read() }
    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated); generation += 1; reading = false
    }
    private func read() {
        guard !reading else { return }
        reading = true; generation += 1; let request = generation
        notice = RuntimePresentation.text("archive_reading"); tableView.reloadData()
        archive.readRuns { [weak self] result in
            Task { @MainActor [weak self] in
                guard let self, self.generation == request else { return }
                self.reading = false
                switch result {
                case .success(let values):
                    self.runs = values; self.updatedAt = Int64(Date().timeIntervalSince1970 * 1_000)
                    self.notice = RuntimePresentation.text("archive_loaded") + " · " + RuntimePresentation.date(self.updatedAt!)
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
    override func numberOfSections(in tableView: UITableView) -> Int { 2 }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { section == 0 ? 2 : runs.count }
    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        if section == 0 { return notice + "\n\n" + PersistencePresentation.status(archive.persistenceStatus()) }
        return (runs.isEmpty && updatedAt != nil ? RuntimePresentation.text("archive_empty") + "\n\n" : "") + RuntimePresentation.text("archive_hint")
    }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "run") ?? UITableViewCell(style: .subtitle, reuseIdentifier: "run")
        PersistencePresentation.style(cell)
        cell.accessoryType = .none; cell.accessibilityTraits = .button; cell.isUserInteractionEnabled = !reading
        cell.textLabel?.textColor = reading ? .secondaryLabel : .label
        if indexPath.section == 0 {
            cell.textLabel?.text = RuntimePresentation.text(indexPath.row == 0 ? "archive_refresh" : "archive_delete_all"); cell.detailTextLabel?.text = nil
            cell.textLabel?.textColor = reading ? .secondaryLabel : indexPath.row == 0 ? view.tintColor : .systemRed
        } else {
            let run = runs[indexPath.row]
            cell.textLabel?.text = PersistencePresentation.title(run)
            cell.detailTextLabel?.text = PersistencePresentation.counts(run)
            cell.accessoryType = .disclosureIndicator
        }
        if reading { cell.accessibilityTraits.insert(.notEnabled) }
        return cell
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        guard !reading else { return }
        if indexPath.section == 0 {
            if indexPath.row == 0 { read() } else { confirmDeletion(id: nil) }
            return
        }
        reading = true; generation += 1; let request = generation
        notice = RuntimePresentation.text("archive_reading"); tableView.reloadData()
        archive.readRun(id: runs[indexPath.row].id) { [weak self] result in
            Task { @MainActor [weak self] in
                guard let self, self.generation == request else { return }
                self.reading = false
                switch result {
                case .success(let history):
                    if let history { self.navigationController?.pushViewController(RetainedBreadcrumbViewController(history: history), animated: true) }
                    else { self.notice = RuntimePresentation.text("archive_missing") }
                case .failure(let error): self.failed(error)
                }
                self.tableView.reloadData()
            }
        }
    }
    override func tableView(_ tableView: UITableView, trailingSwipeActionsConfigurationForRowAt indexPath: IndexPath) -> UISwipeActionsConfiguration? {
        guard indexPath.section == 1, !reading else { return nil }
        let id = runs[indexPath.row].id
        let action = UIContextualAction(style: .destructive, title: RuntimePresentation.text("archive_delete_run")) { [weak self] _, _, completed in
            self?.confirmDeletion(id: id); completed(false)
        }
        let configuration = UISwipeActionsConfiguration(actions: [action]); configuration.performsFirstActionWithFullSwipe = false
        return configuration
    }
    private func confirmDeletion(id: String?) {
        guard !reading else { return }
        let alert = UIAlertController(title: RuntimePresentation.text(id == nil ? "archive_delete_all" : "archive_delete_run"),
            message: RuntimePresentation.text("archive_delete_confirmation"), preferredStyle: .alert)
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
        if let id { archive.deleteRun(id: id, completion: completed) }
        else { archive.deleteAllRuns(completion: completed) }
    }
}

@MainActor
private final class RetainedBreadcrumbViewController: UITableViewController, UISearchResultsUpdating {
    private let history: RetainedBreadcrumbs
    private var events: [BreadcrumbEvent]
    private let search = UISearchController(searchResultsController: nil)
    init(history: RetainedBreadcrumbs) { self.history = history; events = history.events.reversed(); super.init(style: .plain) }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func viewDidLoad() {
        super.viewDidLoad(); title = RuntimePresentation.text("archive_title")
        tableView.rowHeight = UITableView.automaticDimension; tableView.estimatedRowHeight = 80
        search.searchResultsUpdater = self; search.obscuresBackgroundDuringPresentation = false
        search.searchBar.placeholder = RuntimePresentation.text("crumb_search")
        navigationItem.searchController = search; navigationItem.hidesSearchBarWhenScrolling = false; definesPresentationContext = true
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: RuntimePresentation.text("archive_copy_summary"), style: .plain, target: self, action: #selector(copySummary))
    }
    @objc private func copySummary() { UIPasteboard.general.string = PersistencePresentation.run(history.run) }
    func updateSearchResults(for searchController: UISearchController) {
        let query = searchController.searchBar.text ?? ""
        events = history.events.reversed().filter { query.isEmpty || $0.label.localizedCaseInsensitiveContains(query)
            || RuntimePresentation.kind($0).localizedCaseInsensitiveContains(query) || ($0.message?.localizedCaseInsensitiveContains(query) ?? false) }
        tableView.reloadData()
    }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { events.count }
    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? { PersistencePresentation.run(history.run) }
    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        (events.isEmpty ? RuntimePresentation.text("ws_no_matches") + "\n" : "") + RuntimePresentation.text("archive_snapshot")
    }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "saved-event") ?? UITableViewCell(style: .subtitle, reuseIdentifier: "saved-event")
        PersistencePresentation.style(cell)
        let event = events[indexPath.row]
        cell.textLabel?.text = RuntimePresentation.kind(event) + " · " + event.label
        cell.detailTextLabel?.text = RuntimePresentation.date(event.timestampMillis) + " · #\(event.sequence)" + (event.message.map { "\n" + $0 } ?? "")
        cell.textLabel?.numberOfLines = 2; cell.detailTextLabel?.numberOfLines = 3; cell.accessoryType = .disclosureIndicator
        return cell
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        navigationController?.pushViewController(BreadcrumbDetailViewController(event: events[indexPath.row]), animated: true)
    }
}

@MainActor
enum PersistencePresentation {
    static func title(_ run: RetainedRun) -> String {
        RuntimePresentation.date(run.recordingStartedAtMillis) + " · " + RuntimePresentation.text(run.isCurrent ? "archive_current" : "archive_previous_run")
    }
    static func counts(_ run: RetainedRun) -> String {
        String(format: RuntimePresentation.text("archive_counts"), run.eventCount, run.evictedEvents)
    }
    static func run(_ run: RetainedRun) -> String {
        title(run) + "\n" + counts(run) + "\n" + String(format: RuntimePresentation.text("archive_run_info"),
            run.platform.rawValue, String(run.processID), RuntimePresentation.date(run.lastRecordedAtMillis), run.id)
    }
    static func error(_ error: PersistenceError) -> String {
        let key: String
        switch error {
        case .busy: key = "busy"
        case .closed: key = "closed"
        case .unavailable: key = "unavailable"
        case .unsupportedSchema: key = "unsupported_schema"
        case .corrupt: key = "corrupt"
        case .storageFull: key = "storage_full"
        case .invalidRecord: key = "invalid_record"
        }
        return RuntimePresentation.text("archive_error_" + key)
    }
    static func status(_ status: PersistenceStatus?, formatKey: String = "archive_status") -> String {
        guard let status else { return RuntimePresentation.text("busy") }
        let key: String
        switch status.state {
        case .opening: key = "opening"
        case .ready: key = "ready"
        case .failed: key = "failed"
        case .closing: key = "closing"
        case .closed: key = "closed"
        }
        var result = String(format: RuntimePresentation.text(formatKey), RuntimePresentation.text("archive_state_" + key),
            status.queuedEvents, status.committedEvents, status.discardedEvents)
        if let failure = status.lastError { result += "\n" + error(failure) }
        return result
    }
    static func style(_ cell: UITableViewCell) {
        cell.textLabel?.font = .preferredFont(forTextStyle: .body); cell.textLabel?.adjustsFontForContentSizeCategory = true; cell.textLabel?.numberOfLines = 0
        cell.detailTextLabel?.font = .preferredFont(forTextStyle: .caption1); cell.detailTextLabel?.adjustsFontForContentSizeCategory = true; cell.detailTextLabel?.numberOfLines = 0
    }
}
#endif

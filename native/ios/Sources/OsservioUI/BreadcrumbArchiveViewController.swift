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
        super.viewDidLoad(); InspectorTheme.apply(self); tableView.rowHeight = UITableView.automaticDimension; tableView.estimatedRowHeight = 76
        inspectorPrimaryAction = UIBarButtonItem(image: UIImage(systemName: "ellipsis"), style: .plain, target: self, action: #selector(showActions))
        inspectorPrimaryAction?.accessibilityLabel = InspectorLocalization.text("ui_actions")
    }
    override func viewWillAppear(_ animated: Bool) { super.viewWillAppear(animated); InspectorTheme.apply(self); title = InspectorLocalization.text("ui_saved_runs"); read() }
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
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { section == 0 ? 1 : runs.count }
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
            cell.textLabel?.text = RuntimePresentation.text("archive_refresh"); cell.detailTextLabel?.text = nil
            cell.textLabel?.textColor = reading ? .secondaryLabel : view.tintColor
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
            read()
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
    @objc private func showActions() {
        let menu = UIAlertController(title: InspectorLocalization.text("ui_actions"), message: PersistencePresentation.status(archive.persistenceStatus()), preferredStyle: .actionSheet)
        let clear = UIAlertAction(title: InspectorLocalization.text("archive_delete_all"), style: .destructive) { [weak self] _ in self?.confirmDeletion(id: nil) }
        clear.isEnabled = !reading && !runs.isEmpty; menu.addAction(clear)
        menu.addAction(UIAlertAction(title: InspectorLocalization.text("archive_cancel"), style: .cancel))
        menu.popoverPresentationController?.barButtonItem = inspectorPrimaryAction
        present(menu, animated: true)
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
private final class RetainedBreadcrumbViewController: UITableViewController {
    private let history: RetainedBreadcrumbs
    private var events: [BreadcrumbEvent]
    private let search = UISearchTextField()
    init(history: RetainedBreadcrumbs) { self.history = history; events = history.events.reversed(); super.init(style: .plain) }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func viewDidLoad() {
        super.viewDidLoad(); InspectorTheme.apply(self); title = RuntimePresentation.text("archive_title")
        tableView.rowHeight = UITableView.automaticDimension; tableView.estimatedRowHeight = 80
        search.placeholder = RuntimePresentation.text("crumb_search")
        search.font = .preferredFont(forTextStyle: .body); search.adjustsFontForContentSizeCategory = true
        search.autocorrectionType = .no; search.autocapitalizationType = .none; search.returnKeyType = .done
        search.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        search.addTarget(self, action: #selector(searchChanged), for: .editingChanged)
        search.addTarget(self, action: #selector(endSearch), for: .editingDidEndOnExit)
        let header = UIStackView(arrangedSubviews: [search]); header.axis = .vertical; header.isLayoutMarginsRelativeArrangement = true
        header.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 12, leading: 16, bottom: 8, trailing: 16)
        tableView.tableHeaderView = header; tableView.keyboardDismissMode = .onDrag
        inspectorPrimaryAction = UIBarButtonItem(image: UIImage(systemName: "doc.on.doc"), style: .plain, target: self, action: #selector(copySummary))
    }
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated); InspectorTheme.apply(self)
        title = RuntimePresentation.text("archive_title"); search.placeholder = RuntimePresentation.text("crumb_search")
        inspectorPrimaryAction?.accessibilityLabel = RuntimePresentation.text("archive_copy_summary")
        searchChanged()
    }
    override func loadView() { super.loadView(); tableView = InspectorTableView(frame: .zero, style: .plain) }
    @objc private func endSearch() { search.resignFirstResponder() }
    @objc private func copySummary() { UIPasteboard.general.string = PersistencePresentation.run(history.run) }
    @objc private func searchChanged() {
        guard search.markedTextRange == nil else { return }
        let query = String((search.text ?? "").prefix(512)); if query != search.text { search.text = query }
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
        cell.backgroundColor = InspectorTheme.surface; cell.textLabel?.textColor = .label; cell.detailTextLabel?.textColor = InspectorTheme.secondary
        cell.textLabel?.font = .preferredFont(forTextStyle: .body); cell.textLabel?.adjustsFontForContentSizeCategory = true; cell.textLabel?.numberOfLines = 0
        cell.detailTextLabel?.font = .preferredFont(forTextStyle: .caption1); cell.detailTextLabel?.adjustsFontForContentSizeCategory = true; cell.detailTextLabel?.numberOfLines = 0
    }
}
#endif

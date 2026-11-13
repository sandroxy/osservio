#if canImport(UIKit)
import OsservioCore
import UIKit

/// Saved evidence is queried on demand. Record-time filters never claim the failure's occurrence time.
@MainActor
public final class IncidentInspectorViewController: UITableViewController {
    private let archive: any IncidentArchive
    private let environment: any EnvironmentProvider
    private let breadcrumbs: any BreadcrumbHistory
    private let logs: (any LogHistory)?
    private let http: (any HTTPHistory)?
    private let webSockets: (any WebSocketHistory)?
    private let exporter: (any IncidentExporter)?
    private let table = InspectorTableView(frame: .zero, style: .insetGrouped)
    private let header = InspectorFeedHeader(compact: true, actionLabel: "incident_delete_all")
    private let markButton = InspectorCapsuleButton(type: .system), retryButton = UIButton(type: .system)
    private lazy var style = InspectorStyleObserver { [weak self] in self?.relabel() }
    private var snapshots: [IncidentSummary] = []
    private var filtered: [IncidentSummary] = []
    private var query = InspectorQuery()
    private var reading = false
    private var generation = 0
    private var updatedAt: Int64?
    private var notice = RuntimePresentation.text("incident_archive_hint")
    private var justSaved = false
    private var retryNeeded = false
    public init(archive: any IncidentArchive, environment: any EnvironmentProvider, breadcrumbs: any BreadcrumbHistory,
                logs: (any LogHistory)? = nil, http: (any HTTPHistory)? = nil, webSockets: (any WebSocketHistory)? = nil,
                exporter: (any IncidentExporter)? = nil) {
        self.archive = archive; self.environment = environment; self.breadcrumbs = breadcrumbs
        self.logs = logs; self.http = http; self.webSockets = webSockets; self.exporter = exporter
        super.init(style: .plain)
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    public override func loadView() { super.loadView(); tableView = table }
    public override func viewDidLoad() {
        super.viewDidLoad(); table.rowHeight = UITableView.automaticDimension; table.estimatedRowHeight = 102
        table.keyboardDismissMode = .onDrag; table.tableHeaderView = header; header.pause.isHidden = true
        header.extra.addArrangedSubview(markButton); header.extra.addArrangedSubview(retryButton)
        markButton.heightAnchor.constraint(greaterThanOrEqualToConstant: 48).isActive = true
        retryButton.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        NotificationCenter.default.addObserver(self, selector: #selector(foreground), name: UIApplication.didBecomeActiveNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(foreground), name: InspectorSession.willExpand, object: nil)
        markButton.titleLabel?.font = .preferredFont(forTextStyle: .body)
        markButton.titleLabel?.adjustsFontForContentSizeCategory = true; markButton.titleLabel?.numberOfLines = 0
        markButton.addTarget(self, action: #selector(markPressed), for: .touchUpInside)
        retryButton.addTarget(self, action: #selector(refreshPressed), for: .touchUpInside)
        header.onQueryChange = { [weak self] value in self?.query = value; self?.render() }
        header.onSearch = { [weak self] value in self?.query.text = value; self?.render() }
        header.filters.addTarget(self, action: #selector(showFilters), for: .touchUpInside)
        style.changed()
    }
    private func relabel() {
        InspectorTheme.apply(self); title = InspectorLocalization.text("incident_title")
        inspectorPrimaryAction = inspectorDeleteItem(target: self, action: #selector(showActions), label: "incident_delete_all")
        header.search.placeholder = InspectorLocalization.text("ui_problem_search")
        markButton.backgroundColor = InspectorTheme.tint
        markButton.setTitle("  " + InspectorLocalization.text("ui_record_problem"), for: .normal)
        markButton.setImage(UIImage(systemName: "plus"), for: .normal)
        retryButton.setTitle(InspectorLocalization.text("ui_retry"), for: .normal); render()
    }
    public override func viewWillAppear(_ animated: Bool) { super.viewWillAppear(animated); read() }
    public override func viewWillDisappear(_ animated: Bool) { super.viewWillDisappear(animated); generation += 1; reading = false; view.endEditing(true) }
    private func read() {
        guard !reading else { return }
        reading = true; retryNeeded = false; generation += 1; let request = generation
        updateReadingState()
        archive.readIncidents { [weak self] result in
            Task { @MainActor [weak self] in
                guard let self, self.generation == request else { return }
                self.reading = false
                switch result {
                case .success(let snapshots):
                    self.snapshots = snapshots; self.updatedAt = Int64(Date().timeIntervalSince1970 * 1_000)
                    self.notice = self.justSaved ? RuntimePresentation.text("incident_saved") : ""
                    self.justSaved = false
                case .failure(let error): self.failed(error)
                }
                self.render()
            }
        }
    }
    private func failed(_ error: PersistenceError) {
        retryNeeded = true
        notice = PersistencePresentation.error(error)
        if let updatedAt { notice += "\n" + RuntimePresentation.text("runtime_previous") + " · " + RuntimePresentation.date(updatedAt) }
    }
    private func render() {
        filtered = snapshots.filter { query.matches($0, now: updatedAt ?? 0) }
        header.status.text = notice; header.relabel(paused: false, query: query)
        header.count.text = String(format: InspectorLocalization.text("ui_result_count"), filtered.count, snapshots.count)
        updateReadingState(); retryButton.isHidden = !retryNeeded
        table.reloadData()
        table.setEmptyState(filtered.isEmpty ? InspectorTheme.empty(reading ? "archive_reading" : snapshots.isEmpty ? "incident_empty" : "ui_filtered_empty") : nil)
    }
    private func updateReadingState() {
        markButton.isEnabled = !reading; retryButton.isEnabled = !reading
        inspectorPrimaryAction?.isEnabled = !reading && !snapshots.isEmpty
        header.activity.accessibilityLabel = RuntimePresentation.text("archive_reading")
        if reading { header.activity.startAnimating() } else { header.activity.stopAnimating() }
        if snapshots.isEmpty { table.setEmptyState(InspectorTheme.empty(reading ? "archive_reading" : "incident_empty")) }
    }
    @objc private func markPressed() { if !reading { mark() } }
    @objc private func refreshPressed() { read() }
    @objc private func showFilters() {
        let kinds = snapshots.map { $0.diagnosticKind?.rawValue ?? "manual" }.sorted()
        let groups = [InspectorFilterGroup(.record, values: kinds)]
        let filters = InspectorFilterViewController(query: query, groups: groups, timeLabel: "ui_record_time") { [weak self] value in self?.query = value; self?.render() }
        view.endEditing(true); inspectorPresent(filters)
    }
    @objc private func showActions() { confirmDeletion(id: nil) }
    @objc private func foreground() {
        guard isViewLoaded, view.window?.isHidden == false, navigationController?.visibleViewController === self, presentedViewController == nil else { return }
        read()
    }
    deinit { NotificationCenter.default.removeObserver(self) }
    public override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { filtered.count }
    public override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let snapshot = filtered[indexPath.row]
        let cell = table.dequeueReusableCell(withIdentifier: "incident") as? InspectorEventCell ?? InspectorEventCell(style: .default, reuseIdentifier: "incident")
        cell.configure(badge: InspectorLocalization.text(snapshot.diagnosticKind == nil ? "ui_manual" : "ui_system"), time: snapshot.timestampMillis,
            title: IncidentPresentation.listTitle(snapshot), subtitle: IncidentPresentation.listSubtitle(snapshot))
        cell.setTimestamp(IncidentPresentation.listTime(snapshot))
        cell.isUserInteractionEnabled = !reading
        return cell
    }
    public override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        table.deselectRow(at: indexPath, animated: true); if !reading { open(id: filtered[indexPath.row].id) }
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
                httpHistory: http, webSocketHistory: webSockets, logHistory: logs as? any LogDetailsHistory) { [weak self] in
                self?.justSaved = true; self?.read()
            }
            inspectorPresent(editor)
        } catch { notice = RuntimePresentation.text("incident_mark_failed"); render() }
    }
    private func open(id: String) {
        reading = true; retryNeeded = false; generation += 1; let request = generation
        updateReadingState()
        archive.readIncident(id: id) { [weak self] result in
            Task { @MainActor [weak self] in
                guard let self, self.generation == request else { return }
                self.reading = false
                switch result {
                case .success(let snapshot):
                    if let snapshot { self.navigationController?.pushViewController(IncidentSnapshotViewController(snapshot: snapshot, exporter: self.exporter, archive: self.archive), animated: true) }
                    else { self.notice = RuntimePresentation.text("incident_missing") }
                case .failure(let error): self.failed(error)
                }
                self.render()
            }
        }
    }
    public override func tableView(_ tableView: UITableView, trailingSwipeActionsConfigurationForRowAt indexPath: IndexPath) -> UISwipeActionsConfiguration? {
        guard indexPath.section == 0, !reading else { return nil }
        let id = filtered[indexPath.row].id
        let action = UIContextualAction(style: .destructive, title: RuntimePresentation.text("incident_delete")) { [weak self] _, _, completed in
            self?.confirmDeletion(id: id); completed(false)
        }
        let actions = UISwipeActionsConfiguration(actions: [action]); actions.performsFirstActionWithFullSwipe = false; return actions
    }
    private func confirmDeletion(id: String?) {
        guard !reading else { return }
        inspectorConfirm(RuntimePresentation.text(id == nil ? "incident_delete_all" : "incident_delete"),
            message: RuntimePresentation.text("incident_delete_confirmation")) { [weak self] in self?.delete(id: id) }
    }
    private func delete(id: String?) {
        guard !reading else { return }
        reading = true; generation += 1; let request = generation
        updateReadingState()
        let completed: @Sendable (Result<Void, PersistenceError>) -> Void = { [weak self] result in
            Task { @MainActor [weak self] in
                guard let self, self.generation == request else { return }
                self.reading = false
                switch result {
                case .success: self.read()
                case .failure(let error): self.failed(error); self.render()
                }
            }
        }
        if let id { archive.deleteIncident(id: id, completion: completed) }
        else { archive.deleteAllIncidents(completion: completed) }
    }
}
#endif

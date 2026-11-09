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
    private let table = InspectorTableView(frame: .zero, style: .plain)
    private let header = InspectorFeedHeader()
    private let markButton = UIButton(type: .system), refreshButton = UIButton(type: .system)
    private lazy var style = InspectorStyleObserver { [weak self] in self?.relabel() }
    private var snapshots: [IncidentSummary] = []
    private var filtered: [IncidentSummary] = []
    private var query = InspectorQuery()
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
        super.init(style: .plain)
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    public override func loadView() { super.loadView(); tableView = table }
    public override func viewDidLoad() {
        super.viewDidLoad(); table.rowHeight = UITableView.automaticDimension; table.estimatedRowHeight = 102
        table.keyboardDismissMode = .onDrag; table.tableHeaderView = header; header.pause.isHidden = true
        header.extra.addArrangedSubview(InspectorControls(buttons: [markButton, refreshButton]))
        markButton.backgroundColor = InspectorTheme.tint; markButton.layer.cornerRadius = 10
        markButton.addTarget(self, action: #selector(markPressed), for: .touchUpInside)
        refreshButton.addTarget(self, action: #selector(refreshPressed), for: .touchUpInside)
        header.onSearch = { [weak self] value in self?.query.text = value; self?.render() }
        header.filters.addTarget(self, action: #selector(showFilters), for: .touchUpInside)
        header.actions.addTarget(self, action: #selector(showActions), for: .touchUpInside)
        style.changed()
    }
    private func relabel() {
        InspectorTheme.apply(self); title = InspectorLocalization.text("incident_title")
        header.search.placeholder = InspectorLocalization.text("ui_problem_search")
        markButton.setTitle(InspectorLocalization.text("incident_mark"), for: .normal)
        refreshButton.setTitle(InspectorLocalization.text("incident_refresh"), for: .normal); render()
    }
    public override func viewWillAppear(_ animated: Bool) { super.viewWillAppear(animated); read() }
    public override func viewWillDisappear(_ animated: Bool) { super.viewWillDisappear(animated); generation += 1; reading = false; view.endEditing(true) }
    private func read() {
        guard !reading else { return }
        reading = true; generation += 1; let request = generation
        notice = RuntimePresentation.text("archive_reading"); render()
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
                self.render()
            }
        }
    }
    private func failed(_ error: PersistenceError) {
        notice = PersistencePresentation.error(error)
        if let updatedAt { notice += "\n" + RuntimePresentation.text("runtime_previous") + " · " + RuntimePresentation.date(updatedAt) }
    }
    private func render() {
        filtered = snapshots.filter { query.matches($0, now: updatedAt ?? 0) }
        header.status.text = notice; header.relabel(paused: false, query: query)
        header.count.text = String(format: InspectorLocalization.text("ui_result_count"), filtered.count, snapshots.count)
        markButton.isEnabled = !reading; refreshButton.isEnabled = !reading
        table.reloadData()
        table.setEmptyState(filtered.isEmpty ? InspectorTheme.empty(snapshots.isEmpty ? "incident_empty" : "ui_filtered_empty") : nil)
    }
    @objc private func markPressed() { if !reading { mark() } }
    @objc private func refreshPressed() { read() }
    @objc private func showFilters() {
        let kinds = ["manual", "managedCrash", "nativeCrash", "anr", "lowMemoryTermination", "signalTermination", "otherExit", "crash", "hang"]
        let groups = [InspectorFilterGroup(.record, values: kinds), InspectorFilterGroup(.source, values: ["manual", "system"])]
        let filters = InspectorFilterViewController(query: query, groups: groups, timeLabel: "ui_record_time") { [weak self] value in self?.query = value; self?.render() }
        view.endEditing(true); inspectorPresent(filters)
    }
    @objc private func showActions() {
        let menu = InspectorActionPanel(title: InspectorLocalization.text("ui_actions"), message: nil)
        menu.addAction(InspectorActionPanel.Action(title: InspectorLocalization.text("ui_capture_details"), style: .default) { [weak self] _ in
            guard let self else { return }; self.inspectorNotice(PersistencePresentation.status(self.archive.persistenceStatus(), formatKey: "incident_status"))
        })
        var clear = InspectorActionPanel.Action(title: InspectorLocalization.text("incident_delete_all"), style: .destructive) { [weak self] _ in self?.confirmDeletion(id: nil) }
        clear.isEnabled = !reading && !snapshots.isEmpty; menu.addAction(clear)
        inspectorPresent(menu)
    }
    public override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { filtered.count }
    public override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let snapshot = filtered[indexPath.row]
        let cell = table.dequeueReusableCell(withIdentifier: "incident") as? InspectorEventCell ?? InspectorEventCell(style: .default, reuseIdentifier: "incident")
        cell.configure(badge: InspectorLocalization.text(snapshot.diagnosticKind == nil ? "ui_manual" : "ui_system"), time: snapshot.timestampMillis,
            title: snapshot.note?.text ?? snapshot.diagnosticKind.map(SystemDiagnosticPresentation.kind) ?? InspectorLocalization.text("incident_manual"),
            subtitle: IncidentPresentation.timeLabel(snapshot.diagnosticKind) + " · " + InspectorLocalization.date(snapshot.timestampMillis)
                + "\n" + snapshot.id.prefix(8))
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
                httpHistory: http, webSocketHistory: webSockets) { [weak self] in
                self?.justSaved = true; self?.read()
            }
            inspectorPresent(editor)
        } catch { notice = RuntimePresentation.text("incident_mark_failed"); render() }
    }
    private func open(id: String) {
        reading = true; generation += 1; let request = generation
        notice = RuntimePresentation.text("archive_reading"); render()
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
        let alert = UIAlertController(title: RuntimePresentation.text(id == nil ? "incident_delete_all" : "incident_delete"),
            message: RuntimePresentation.text("incident_delete_confirmation"), preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: RuntimePresentation.text("archive_cancel"), style: .cancel))
        alert.addAction(UIAlertAction(title: RuntimePresentation.text("archive_delete_confirm"), style: .destructive) { [weak self] _ in self?.delete(id: id) })
        present(alert, animated: true)
    }
    private func delete(id: String?) {
        guard !reading else { return }
        reading = true; generation += 1; let request = generation
        notice = RuntimePresentation.text("archive_deleting"); render()
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

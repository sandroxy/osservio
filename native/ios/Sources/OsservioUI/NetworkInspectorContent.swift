#if canImport(UIKit)
import OsservioCore
import UIKit

/// One query spans HTTP exchanges and Socket observations. Payload work stays off the main thread.
@MainActor
final class NetworkInspectorContent: NSObject, UITableViewDataSource, UITableViewDelegate {
    typealias HTTP = (recorder: HTTPRecorder, history: any HTTPHistory)
    typealias Socket = (recorder: WebSocketRecorder, history: any WebSocketHistory)
    let table = InspectorTableView(frame: .zero, style: .insetGrouped)
    private let header = InspectorFeedHeader(compact: true, actionLabel: "ui_network_clear")
    private let http: HTTP?
    private let sockets: Socket?
    private let connectionID: String?
    private weak var owner: UIViewController?
    private lazy var loop: InspectorRefreshLoop = {
        let loop = InspectorRefreshLoop { [weak self] in self?.refresh() }
        loop.suspended = { [weak self] in self?.cancelSearch() }; return loop
    }()
    private lazy var style = InspectorStyleObserver { [weak self] in self?.relabel() }
    private var latestHTTP: HTTPSnapshot?
    private var latestSockets: WebSocketSnapshot?
    private var rows: [InspectorSearchRecord] = []
    private var result: InspectorRecordSearch.Result?
    private var task: InspectorRecordSearchTask?
    private var generation: UInt64 = 0
    private var nextSearchAt: TimeInterval = 0
    private var timeCheck: TimeInterval = 0
    private var anchor: Int64 = 0
    private var dirty = true, attemptedRead = false
    private var staleHTTP = false, staleSockets = false
    private var available = 0
    private(set) var query = InspectorQuery()
    private(set) var paused = false { didSet { table.followsLatest = !paused } }

    init(http: HTTP? = nil, sockets: Socket? = nil, connectionID: String? = nil) {
        self.http = http; self.sockets = sockets; self.connectionID = connectionID; super.init()
    }
    func attach(to owner: UIViewController) {
        self.owner = owner; table.dataSource = self; table.delegate = self
        table.rowHeight = UITableView.automaticDimension; table.estimatedRowHeight = 106
        table.keyboardDismissMode = .onDrag; table.tableHeaderView = header
        if connectionID != nil { header.extra.addArrangedSubview(InspectorTheme.label(.caption1, secondary: true)); header.actions.isHidden = true }
        header.onSearch = { [weak self] value in guard let self else { return }; var query = self.query; query.text = value; self.apply(query) }
        header.onQueryChange = { [weak self] in self?.apply($0) }
        table.followLatestButton = header.pause; table.followsLatest = true
        header.pause.addTarget(self, action: #selector(followLatest), for: .touchUpInside)
        header.filters.addTarget(self, action: #selector(showFilters), for: .touchUpInside)
        style.changed()
    }
    func show() { loop.show(in: table) }
    func hide() { loop.hide(); table.endEditing(true) }
    private func relabel() {
        guard let owner else { return }; InspectorTheme.apply(owner)
        owner.title = InspectorLocalization.text(connectionID == nil ? "network" : "ui_connection_records")
        if connectionID == nil { owner.inspectorPrimaryAction = inspectorDeleteItem(target: self, action: #selector(clear), label: "ui_network_clear") }
        header.search.placeholder = InspectorLocalization.text("ui_network_search")
        (header.extra.arrangedSubviews.first as? UILabel)?.text = [InspectorLocalization.text("ui_connection_hint"), connectionID ?? ""].joined(separator: "\n")
        display()
    }
    private func cancelSearch() { generation &+= 1; task?.cancel(); task = nil; dirty = true }
    private func apply(_ query: InspectorQuery) {
        cancelSearch(); self.query = query; result = nil; rows = []
        nextSearchAt = Date.timeIntervalSinceReferenceDate + 0.2; display(); refresh()
    }
    private func refresh() {
        guard owner?.presentedViewController == nil else { return }
        let now = Date.timeIntervalSinceReferenceDate
        if !paused && task == nil {
            anchor = Int64(Date().timeIntervalSince1970 * 1_000); http?.recorder.maintenance(); attemptedRead = true
            let requests = http?.history.snapshot(), socketEvents = sockets?.history.snapshot()
            staleHTTP = http != nil && requests == nil; staleSockets = sockets != nil && socketEvents == nil
            if let requests { dirty = dirty || latestHTTP?.revision != requests.revision; latestHTTP = requests }
            if let socketEvents { dirty = dirty || latestSockets?.revision != socketEvents.revision; latestSockets = socketEvents }
            if query.recentMinutes > 0 && now - timeCheck >= 5 { dirty = true; timeCheck = now }
        }
        if dirty && task == nil && now >= nextSearchAt { search() }
        updateStatus()
    }
    private var records: [InspectorSearchRecord] {
        let requests = connectionID == nil ? (latestHTTP?.events ?? []).map(InspectorSearchRecord.http) : []
        return (requests + (latestSockets?.events ?? []).filter { connectionID == nil || $0.connectionID == connectionID }.map(InspectorSearchRecord.socket))
            .sorted(by: InspectorSearchRecord.newestFirst)
    }
    private func search() {
        let records = records, query = query, anchor = anchor
        available = records.count
        if !query.searchesPayload {
            rows = records.filter { $0.matchesFacets(query, now: anchor) }; result = nil; dirty = false; display(); return
        }
        let requestHistory = http?.history, socketHistory = sockets?.history, current = generation
        task = InspectorRecordSearchTask.submit(search: { cancelled in
            InspectorRecordSearch.run(records, query: query, now: anchor, http: requestHistory, sockets: socketHistory, cancelled: cancelled)
        }, completion: { [weak self] result in
            guard let self, self.generation == current else { return }
            self.task = nil; self.result = result; self.rows = records.filter { result.keys.contains($0.key) }
            self.dirty = false; self.nextSearchAt = Date.timeIntervalSinceReferenceDate + 1; self.display()
        })
        if task == nil { nextSearchAt = Date.timeIntervalSinceReferenceDate + 0.5 }
    }
    private func updateStatus() {
        var notes: [String] = []
        if task != nil || dirty && query.searchesPayload { notes.append(InspectorLocalization.text("ui_searching")) }
        if let note = InspectorSearchFeedback.text(result) { notes.append(note) }
        if staleHTTP || staleSockets { notes.append(InspectorLocalization.text("ui_previous_window")) }
        if (latestHTTP?.evictedEvents ?? 0) > 0 || (latestHTTP?.evictedBodies ?? 0) > 0
            || (latestSockets?.evictedEvents ?? 0) > 0 || (latestSockets?.evictedPayloads ?? 0) > 0
            || (sockets?.recorder.status()?.rateLimitedCount ?? 0) > 0 { notes.append(InspectorLocalization.text("ui_retention_loss")) }
        header.status.text = notes.joined(separator: " · ")
        header.relabel(paused: paused, query: query); table.setNeedsLayout()
    }
    private func display() {
        table.reloadData()
        header.count.text = String(format: InspectorLocalization.text("ui_result_count"), rows.count, available)
        let active = http?.recorder.captureController.status()?.debugEnabled == true || sockets?.recorder.captureController.status()?.debugEnabled == true
        let key = dirty && query.searchesPayload ? "ui_searching" : available > 0 ? "ui_filtered_empty" : !attemptedRead ? "ui_reading"
            : staleHTTP || staleSockets ? "busy" : active ? "ui_waiting" : "ui_inactive_empty"
        table.setEmptyState(rows.isEmpty ? InspectorTheme.empty(key) : nil); updateStatus()
    }
    @objc private func followLatest() {
        cancelSearch(); nextSearchAt = 0; paused = false; refresh()
        table.showLatest()
    }
    @objc private func showFilters() {
        table.endEditing(true)
        let filters = InspectorFilterViewController(query: query,
            groups: InspectorFilterOptions.network(latestHTTP?.events ?? [], sockets: latestSockets?.events ?? [], query: query,
                includeHTTP: http != nil, includeSockets: sockets != nil), payload: true) { [weak self] in self?.apply($0) }
        owner?.inspectorPresent(filters)
    }
    @objc private func clear() {
        owner?.inspectorConfirm(InspectorLocalization.text("ui_network_clear"), message: InspectorLocalization.text("ui_clear_hint")) { [weak self] in
            guard let self else { return }; self.cancelSearch()
            self.http?.history.clear(); self.sockets?.history.clear()
            self.latestHTTP = self.http?.history.snapshot(); self.latestSockets = self.sockets?.history.snapshot()
            self.result = nil; self.rows = []; self.nextSearchAt = 0; self.search()
        }
    }
    func scrollViewWillBeginDragging(_ scrollView: UIScrollView) { paused = true; cancelSearch(); dirty = false; updateStatus() }
    func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) { if !decelerate { resumeAtTop() } }
    func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) { resumeAtTop() }
    private func resumeAtTop() { if table.contentOffset.y + table.adjustedContentInset.top <= 1 { paused = false; refresh() } }
    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { rows.count }
    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let record = rows[indexPath.row]
        let cell = table.dequeueReusableCell(withIdentifier: "network") as? InspectorEventCell ?? InspectorEventCell(style: .default, reuseIdentifier: "network")
        let preview = result?.previews[record.key].map { "\n" + $0 } ?? ""
        switch record {
        case .http(let event):
            cell.configure(badge: event.method + (event.statusCode.map { " · \($0)" } ?? ""), time: event.timestampMillis,
                title: InspectorQuery.path(event.url), subtitle: InspectorQuery.host(event.url) + " · " + HTTPPresentation.summary(event) + preview,
                color: event.state == .failed || (event.statusCode ?? 0) >= 400 ? .systemRed : InspectorTheme.accent)
        case .socket(let event):
            cell.configure(badge: "Socket · " + (event.direction.map { InspectorLocalization.text("ws_" + $0.rawValue) } ?? WebSocketPresentation.kind(event)),
                time: event.timestampMillis, title: InspectorQuery.path(event.url),
                subtitle: WebSocketPresentation.summary(event) + " · " + InspectorQuery.host(event.url) + "\n" + event.connectionID.prefix(8) + " · #\(event.sequence)" + preview,
                color: event.kind == .failed || event.outcome == .failed || event.outcome == .rejected ? .systemRed : InspectorTheme.accent)
        case .log: break
        }
        return cell
    }
    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        table.deselectRow(at: indexPath, animated: true)
        switch rows[indexPath.row] {
        case .http(let event): if let http { owner?.inspectorPush(HTTPDetailViewController(event: event, history: http.history)) }
        case .socket(let event):
            if let sockets {
                let detail = WebSocketDetailViewController(event: event, history: sockets.history)
                detail.showConnection = { [weak detail] in detail?.inspectorPush(NetworkInspectorViewController(content: NetworkInspectorContent(sockets: sockets, connectionID: event.connectionID))) }
                owner?.inspectorPush(detail)
            }
        case .log: break
        }
    }
}

@MainActor
final class NetworkInspectorViewController: UITableViewController {
    let content: NetworkInspectorContent
    init(content: NetworkInspectorContent) { self.content = content; super.init(style: .plain) }
    @available(*, unavailable) required init?(coder: NSCoder) { return nil }
    override func loadView() { super.loadView(); tableView = content.table }
    override func viewDidLoad() { super.viewDidLoad(); content.attach(to: self) }
    override func viewWillAppear(_ animated: Bool) { super.viewWillAppear(animated); content.show() }
    override func viewDidAppear(_ animated: Bool) { super.viewDidAppear(animated); content.show() }
    override func viewWillDisappear(_ animated: Bool) { super.viewWillDisappear(animated); content.hide() }
}
#endif

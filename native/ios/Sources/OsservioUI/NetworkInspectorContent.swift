#if canImport(UIKit)
import OsservioCore
import UIKit

/// One feed implementation backs the combined destination and the standalone public viewers.
/// It owns only view state; recorders, capture controllers and histories remain host-owned.
@MainActor
final class NetworkInspectorContent: NSObject, UITableViewDataSource, UITableViewDelegate {
    typealias HTTP = (recorder: HTTPRecorder, history: any HTTPHistory)
    typealias Socket = (recorder: WebSocketRecorder, history: any WebSocketHistory)
    private enum Row {
        case http(HTTPEvent), connection(InspectorConnection), event(WebSocketEvent)
        var time: Int64 {
            switch self { case .http(let value): return value.timestampMillis
            case .connection(let value): return value.first.timestampMillis
            case .event(let value): return value.timestampMillis }
        }
        var id: String {
            switch self { case .http(let value): return value.id
            case .connection(let value): return value.first.connectionID
            case .event(let value): return value.id }
        }
    }
    let table = InspectorTableView(frame: .zero, style: .plain)
    private let header = InspectorFeedHeader()
    private let scopes = InspectorScopeControl(titles: ["", "HTTP", "WebSocket"])
    private let http: HTTP?
    private let sockets: Socket?
    private let connectionID: String?
    private weak var owner: UIViewController?
    private lazy var loop = InspectorRefreshLoop { [weak self] in self?.refresh() }
    private lazy var style = InspectorStyleObserver { [weak self] in self?.relabel() }
    private var latestHTTP: HTTPSnapshot?
    private var latestSockets: WebSocketSnapshot?
    private var rows: [Row] = []
    private var connections: [InspectorConnection] = []
    private var anchor: Int64 = 0
    private var dirty = true
    private var attemptedRead = false
    private var staleHTTP = false, staleSockets = false
    private var offsets: [CGFloat] = [0, 0, 0]
    private(set) var scope: Int
    private(set) var queries = [InspectorQuery(), InspectorQuery(), InspectorQuery()]
    private(set) var paused = false
    private var query: InspectorQuery { queries[scope] }

    init(http: HTTP? = nil, sockets: Socket? = nil, connectionID: String? = nil) {
        self.http = http; self.sockets = sockets; self.connectionID = connectionID
        scope = http == nil ? 2 : sockets == nil ? 1 : 0
        super.init()
    }
    func attach(to owner: UIViewController) {
        self.owner = owner; table.dataSource = self; table.delegate = self
        table.rowHeight = UITableView.automaticDimension; table.estimatedRowHeight = 106
        table.keyboardDismissMode = .onDrag; table.tableHeaderView = header
        scopes.selectedIndex = scope
        scopes.onSelect = { [weak self] _ in self?.changeScope() }
        if http != nil && sockets != nil { header.extra.addArrangedSubview(scopes) }
        if connectionID != nil {
            let hint = InspectorTheme.label(.caption1, secondary: true)
            hint.text = InspectorLocalization.text("ui_connection_hint"); header.extra.addArrangedSubview(hint)
        }
        header.onSearch = { [weak self] value in
            guard let self else { return }; self.queries[self.scope].text = value; self.render()
        }
        header.pause.addTarget(self, action: #selector(togglePause), for: .touchUpInside)
        header.filters.addTarget(self, action: #selector(showFilters), for: .touchUpInside)
        header.actions.addTarget(self, action: #selector(showActions), for: .touchUpInside)
        style.changed()
    }
    func show() { loop.show(in: table) }
    func hide() { loop.hide(); table.endEditing(true) }
    private func relabel() {
        guard let owner else { return }; InspectorTheme.apply(owner)
        owner.title = connectionID.map { "WebSocket · " + $0.prefix(8) } ?? InspectorLocalization.text("network")
        scopes.setTitles([InspectorLocalization.text("ui_all"), "HTTP", "WebSocket"])
        header.search.placeholder = InspectorLocalization.text(connectionID == nil ? "ui_network_search" : "ws_search")
        if connectionID != nil { (header.extra.arrangedSubviews.first as? UILabel)?.text = InspectorLocalization.text("ui_connection_hint") }
        render()
    }
    private func refresh() {
        guard owner?.presentedViewController == nil else { return }
        if !paused {
            anchor = Int64(Date().timeIntervalSince1970 * 1_000)
            http?.recorder.maintenance()
            attemptedRead = true
            let requestSnapshot = http?.history.snapshot(), socketSnapshot = sockets?.history.snapshot()
            let httpFailed = http != nil && requestSnapshot == nil, socketFailed = sockets != nil && socketSnapshot == nil
            dirty = dirty || staleHTTP != httpFailed || staleSockets != socketFailed
            staleHTTP = httpFailed; staleSockets = socketFailed
            if let snapshot = requestSnapshot { dirty = dirty || latestHTTP?.revision != snapshot.revision; latestHTTP = snapshot }
            if let snapshot = socketSnapshot {
                if latestSockets?.revision != snapshot.revision { dirty = true; connections = InspectorConnection.groups(snapshot.events) }
                latestSockets = snapshot
            }
        }
        if dirty || query.recentMinutes > 0 { render() } else { updateStatus() }
    }
    private var controllers: [(String, CaptureController)] {
        var result: [(String, CaptureController)] = []
        if let http { result.append(("HTTP", http.recorder.captureController)) }
        if let sockets, result.first?.1 !== sockets.recorder.captureController { result.append(("WebSocket", sockets.recorder.captureController)) }
        return result
    }
    private func updateStatus() {
        let states = controllers.map { label, controller in
            (controllers.count > 1 ? label + ": " : "") + (controller.status().map { InspectorLocalization.text("http_" + $0.mode.rawValue) } ?? InspectorLocalization.text("busy"))
        }
        header.status.text = paused ? InspectorLocalization.text("ui_paused") : states.joined(separator: " · ")
        if scope != 2 && staleHTTP { header.status.text! += " · HTTP: " + InspectorLocalization.text(latestHTTP == nil ? "busy" : "ui_previous_window") }
        if scope != 1 && staleSockets { header.status.text! += " · WebSocket: " + InspectorLocalization.text(latestSockets == nil ? "busy" : "ui_previous_window") }
        header.relabel(paused: paused, query: query); table.setNeedsLayout()
    }
    private func render() {
        rows = []
        var available = 0
        if let connectionID {
            let events = (latestSockets?.events ?? []).filter { $0.connectionID == connectionID }
            available = events.count
            rows = events.filter { query.matches($0, now: anchor) }.map(Row.event)
        } else {
            if scope != 2 {
                available += latestHTTP?.events.count ?? 0
                rows += (latestHTTP?.events ?? []).filter { query.matches($0, now: anchor) }.map(Row.http)
            }
            if scope != 1 {
                available += connections.count; rows += connections.filter { query.matches($0, now: anchor) }.map(Row.connection)
            }
        }
        rows.sort {
            if case .event(let left) = $0, case .event(let right) = $1 {
                return InspectorQuery.timelineNewestFirst(left, right)
            }
            return $0.time == $1.time ? $0.id < $1.id : $0.time > $1.time
        }
        table.reloadData(); dirty = false
        header.count.text = String(format: InspectorLocalization.text("ui_result_count"), rows.count, available)
        let active = controllers.contains { $0.1.status()?.mode == .detailed }
        let waiting = !attemptedRead ? "ui_reading" : (scope != 2 && staleHTTP) || (scope != 1 && staleSockets) ? "busy" : active ? "ui_waiting" : "ui_inactive_empty"
        table.setEmptyState(rows.isEmpty ? InspectorTheme.empty(available > 0 ? "ui_filtered_empty" : waiting) : nil)
        updateStatus()
    }
    @objc private func changeScope() {
        // Store distance from the visible top, not UIKit's inset-adjusted origin.
        offsets[scope] = max(0, table.contentOffset.y + table.adjustedContentInset.top); scope = scopes.selectedIndex
        header.search.text = query.text; render(); table.layoutIfNeeded()
        table.setContentOffset(CGPoint(x: 0, y: offsets[scope] - table.adjustedContentInset.top), animated: false)
    }
    @objc private func togglePause() {
        paused.toggle(); dirty = true
        if !paused { table.setContentOffset(CGPoint(x: 0, y: -table.adjustedContentInset.top), animated: false) }
        refresh()
    }
    @objc private func showFilters() {
        table.endEditing(true)
        let groups = connectionID == nil ? InspectorFilterOptions.network(latestHTTP?.events ?? [], connections: connections, scope: scope, query: query)
            : InspectorFilterOptions.timeline()
        let filters = InspectorFilterViewController(query: query, groups: groups) { [weak self] value in
            guard let self else { return }; self.queries[self.scope] = value; self.render()
        }
        owner?.inspectorPresent(filters)
    }
    @objc private func showActions() {
        let menu = UIAlertController(title: InspectorLocalization.text("ui_actions"), message: nil, preferredStyle: .actionSheet)
        for (label, controller) in controllers {
            let title = (controllers.count > 1 ? label + " · " : "") + InspectorLocalization.text(controller.status()?.mode == .detailed ? "http_stop" : "http_start")
            menu.addAction(UIAlertAction(title: title, style: .default) { [weak self] _ in self?.toggleCapture(controller) })
        }
        menu.addAction(UIAlertAction(title: InspectorLocalization.text("ui_capture_details"), style: .default) { [weak self] _ in
            guard let self else { return }
            var parts: [String] = []
            if let value = self.latestHTTP, let status = self.http?.recorder.captureController.status() {
                parts.append(String(format: InspectorLocalization.text("http_summary"), InspectorLocalization.text("http_" + status.mode.rawValue), value.events.count, value.evictedEvents, value.evictedBodies))
            }
            if let value = self.latestSockets, let status = self.sockets?.recorder.captureController.status() {
                parts.append(String(format: InspectorLocalization.text("ws_summary"), InspectorLocalization.text("http_" + status.mode.rawValue), value.events.count, value.evictedEvents, value.evictedPayloads, self.sockets?.recorder.status()?.rateLimitedCount ?? 0))
            }
            self.owner?.inspectorNotice(parts.isEmpty ? InspectorLocalization.text("busy") : parts.joined(separator: "\n\n"))
        })
        menu.addAction(UIAlertAction(title: InspectorLocalization.text("ui_network_clear"), style: .destructive) { [weak self] _ in
            self?.owner?.inspectorConfirm(InspectorLocalization.text("ui_network_clear"), message: InspectorLocalization.text("ui_clear_hint")) { [weak self] in
                guard let self else { return }
                if self.scope != 2 { self.http?.history.clear(); self.latestHTTP = self.http?.history.snapshot() }
                if self.scope != 1 { self.sockets?.history.clear(); self.latestSockets = self.sockets?.history.snapshot(); self.connections = InspectorConnection.groups(self.latestSockets?.events ?? []) }
                self.render()
            }
        })
        menu.addAction(UIAlertAction(title: InspectorLocalization.text("archive_cancel"), style: .cancel))
        menu.popoverPresentationController?.sourceView = header.actions; menu.popoverPresentationController?.sourceRect = header.actions.bounds
        owner?.present(menu, animated: true)
    }
    private func toggleCapture(_ controller: CaptureController) {
        guard let status = controller.status() else { owner?.inspectorNotice(InspectorLocalization.text("busy")); return }
        if status.mode == .detailed { if !controller.stopDetailed() { owner?.inspectorNotice(InspectorLocalization.text("busy")) } }
        else { do { try controller.startDetailed() } catch { owner?.inspectorNotice(InspectorLocalization.text("busy")) } }
        dirty = true; refresh()
    }
    func scrollViewWillBeginDragging(_ scrollView: UIScrollView) { if !paused { paused = true; updateStatus() } }
    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { rows.count }
    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = table.dequeueReusableCell(withIdentifier: "network") as? InspectorEventCell ?? InspectorEventCell(style: .default, reuseIdentifier: "network")
        switch rows[indexPath.row] {
        case .http(let event):
            cell.configure(badge: event.method + (event.statusCode.map { " · \($0)" } ?? ""), time: event.timestampMillis,
                title: InspectorQuery.path(event.url), subtitle: InspectorQuery.host(event.url) + " · " + HTTPPresentation.summary(event),
                color: event.state == .failed || (event.statusCode ?? 0) >= 400 ? .systemRed : InspectorTheme.accent)
        case .connection(let connection):
            let state = InspectorFilterOptions.label(.state, connection.state)
            cell.configure(badge: "WebSocket", time: connection.first.timestampMillis, title: InspectorQuery.path(connection.latest.url),
                subtitle: InspectorQuery.host(connection.latest.url) + " · " + state + "\n" + connection.latest.connectionID.prefix(8) + " · "
                    + String(format: InspectorLocalization.text("ui_connection_count"), connection.count),
                color: connection.state == "failed" ? .systemRed : InspectorTheme.accent)
        case .event(let event):
            cell.configure(badge: event.direction.map { InspectorLocalization.text("ws_" + $0.rawValue) } ?? "WebSocket",
                time: event.timestampMillis, title: WebSocketPresentation.summary(event), subtitle: "#\(event.sequence) · " + event.id.prefix(8),
                color: event.kind == .failed || event.outcome == .failed || event.outcome == .rejected ? .systemRed : InspectorTheme.accent)
        }
        return cell
    }
    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        table.deselectRow(at: indexPath, animated: true)
        switch rows[indexPath.row] {
        case .http(let event): if let http { owner?.inspectorPush(HTTPDetailViewController(event: event, history: http.history, recorder: http.recorder)) }
        case .connection(let connection): if let sockets { owner?.inspectorPush(NetworkInspectorViewController(content: NetworkInspectorContent(sockets: sockets, connectionID: connection.latest.connectionID))) }
        case .event(let event): if let sockets { owner?.inspectorPush(WebSocketDetailViewController(event: event, history: sockets.history)) }
        }
    }
}

@MainActor
final class NetworkInspectorViewController: UITableViewController {
    let content: NetworkInspectorContent
    init(content: NetworkInspectorContent) { self.content = content; super.init(style: .plain) }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func loadView() { super.loadView(); tableView = content.table }
    override func viewDidLoad() { super.viewDidLoad(); content.attach(to: self) }
    override func viewWillAppear(_ animated: Bool) { super.viewWillAppear(animated); content.show() }
    override func viewDidAppear(_ animated: Bool) { super.viewDidAppear(animated); content.show() }
    override func viewWillDisappear(_ animated: Bool) { super.viewWillDisappear(animated); content.hide() }
}
#endif

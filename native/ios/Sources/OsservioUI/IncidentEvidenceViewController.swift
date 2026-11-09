#if canImport(UIKit)
import OsservioCore
import UIKit

/// Metadata search and filters use one frozen window. Opening bodies has no live-source dependency.
@MainActor
final class IncidentEvidenceViewController: UITableViewController {
    enum Evidence { case logs(IncidentLogs), http(IncidentHTTP), webSockets(IncidentWebSockets) }
    private let evidence: Evidence
    private let table = InspectorTableView(frame: .zero, style: .plain)
    private let header = InspectorFeedHeader()
    private var query = InspectorQuery()
    private var indices: [Int] = []
    init(_ evidence: Evidence) { self.evidence = evidence; super.init(style: .plain) }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func loadView() { super.loadView(); tableView = table }
    override func viewDidLoad() {
        super.viewDidLoad(); table.tableHeaderView = header
        table.rowHeight = UITableView.automaticDimension; table.estimatedRowHeight = 102; table.keyboardDismissMode = .onDrag
        header.pause.isHidden = true; header.actions.isHidden = true
        header.onSearch = { [weak self] value in self?.query.text = value; self?.render() }
        header.filters.addTarget(self, action: #selector(showFilters), for: .touchUpInside)
        render()
    }
    override func viewWillAppear(_ animated: Bool) { super.viewWillAppear(animated); InspectorTheme.apply(self); render() }
    private func render() {
        let count: Int
        switch evidence {
        case .logs(let value):
            title = InspectorLocalization.text("logs"); count = value.events.count
            indices = value.events.indices.reversed().filter { query.matches(value.events[$0], now: value.observedAtMillis) }
            header.status.text = IncidentPresentation.window(time: value.observedAtMillis, count: count, omitted: value.omittedEventCount, evicted: value.sourceEvictedCount)
        case .http(let value):
            title = "HTTP"; count = value.events.count
            indices = value.events.indices.reversed().filter { query.matches(value.events[$0], now: value.observedAtMillis) }
            header.status.text = IncidentPresentation.window(time: value.observedAtMillis, count: count, omitted: value.omittedEventCount, evicted: value.sourceEvictedEvents, payloadEvicted: value.sourceEvictedBodies)
        case .webSockets(let value):
            title = "WebSocket"; count = value.events.count
            indices = value.events.indices.reversed().filter { query.matches(value.events[$0], now: value.observedAtMillis) }
            header.status.text = IncidentPresentation.window(time: value.observedAtMillis, count: count, omitted: value.omittedEventCount, evicted: value.sourceEvictedEvents, payloadEvicted: value.sourceEvictedPayloads)
        }
        header.search.placeholder = InspectorLocalization.text("incident_evidence_search")
        header.relabel(paused: false, query: query)
        header.count.text = String(format: InspectorLocalization.text("ui_result_count"), indices.count, count)
            + "\n" + InspectorLocalization.text("incident_frozen_detail")
        table.reloadData()
        table.setEmptyState(indices.isEmpty ? InspectorTheme.empty(count == 0 ? "incident_evidence_empty" : "ui_filtered_empty") : nil)
    }
    @objc private func showFilters() {
        let groups: [InspectorFilterGroup]
        switch evidence {
        case .logs(let value): groups = InspectorFilterOptions.logs(value.events)
        case .http(let value): groups = InspectorFilterOptions.network(value.events, connections: [], scope: 1, query: query)
        case .webSockets: groups = InspectorFilterOptions.timeline()
        }
        let filters = InspectorFilterViewController(query: query, groups: groups) { [weak self] value in self?.query = value; self?.render() }
        view.endEditing(true); inspectorPresent(filters)
    }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { indices.count }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let index = indices[indexPath.row]
        let cell = table.dequeueReusableCell(withIdentifier: "evidence") as? InspectorEventCell ?? InspectorEventCell(style: .default, reuseIdentifier: "evidence")
        switch evidence {
        case .logs(let value):
            let event = value.events[index]
            cell.configure(badge: InspectorFilterOptions.label(.level, event.level.rawValue), time: event.timestampMillis, title: event.message, subtitle: event.category)
        case .http(let value):
            let event = value.events[index]
            cell.configure(badge: event.method, time: event.timestampMillis, title: InspectorQuery.path(event.url),
                subtitle: InspectorQuery.host(event.url) + " · " + HTTPPresentation.summary(event))
        case .webSockets(let value):
            let event = value.events[index]
            cell.configure(badge: "WebSocket", time: event.timestampMillis, title: WebSocketPresentation.summary(event), subtitle: event.url + " · " + event.connectionID.prefix(8))
        }
        return cell
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        table.deselectRow(at: indexPath, animated: true)
        let index = indices[indexPath.row], controller: UIViewController
        switch evidence {
        case .logs(let value): controller = LogDetailViewController(event: value.events[index])
        case .http(let value): controller = HTTPDetailViewController(event: value.events[index], savedPayloads: value.payloads)
        case .webSockets(let value): controller = WebSocketDetailViewController(event: value.events[index], savedPayloads: value.payloads)
        }
        inspectorPush(controller)
    }
}
#endif

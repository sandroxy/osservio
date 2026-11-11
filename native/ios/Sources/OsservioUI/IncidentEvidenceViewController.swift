#if canImport(UIKit)
import OsservioCore
import UIKit

/// Content search and filters use one frozen window. Opening bodies has no live-source dependency.
@MainActor
final class IncidentEvidenceViewController: UITableViewController {
    enum Evidence { case logs(IncidentLogs), http(IncidentHTTP), webSockets(IncidentWebSockets) }
    private let evidence: Evidence
    private let connectionID: String?
    private let table = InspectorTableView(frame: .zero, style: .insetGrouped)
    private let header = InspectorFeedHeader(compact: true)
    private var query = InspectorQuery()
    private var indices: [Int] = []
    private var result: InspectorRecordSearch.Result?
    private var searchBusy = false
    private var task: InspectorRecordSearchTask?
    private var pending: DispatchWorkItem?
    private var generation = 0
    private lazy var records: [InspectorSearchRecord] = {
        switch evidence {
        case .logs(let value): return value.events.map(InspectorSearchRecord.log)
        case .http(let value): return value.events.map(InspectorSearchRecord.http)
        case .webSockets(let value): return value.events.map(InspectorSearchRecord.socket)
        }
    }()
    private var payloads: [String: IncidentPayload] {
        switch evidence { case .logs: return [:]; case .http(let value): return value.payloads; case .webSockets(let value): return value.payloads }
    }
    private var observedAt: Int64 {
        switch evidence { case .logs(let value): return value.observedAtMillis; case .http(let value): return value.observedAtMillis; case .webSockets(let value): return value.observedAtMillis }
    }
    init(_ evidence: Evidence, connectionID: String? = nil) {
        self.evidence = evidence; self.connectionID = connectionID; super.init(style: .plain)
        if let connectionID { query.select(.connection, value: connectionID) }
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func loadView() { super.loadView(); tableView = table }
    override func viewDidLoad() {
        super.viewDidLoad(); table.tableHeaderView = header
        table.rowHeight = UITableView.automaticDimension; table.estimatedRowHeight = 102; table.keyboardDismissMode = .onDrag
        header.pause.isHidden = true; header.actions.isHidden = true
        header.onSearch = { [weak self] value in self?.query.text = value; self?.render() }
        header.filters.addTarget(self, action: #selector(showFilters), for: .touchUpInside)
        header.onQueryChange = { [weak self] in self?.query = $0; self?.render() }
        render()
    }
    override func viewWillAppear(_ animated: Bool) { super.viewWillAppear(animated); InspectorTheme.apply(self); render() }
    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated); generation += 1; task?.cancel(); task = nil; pending?.cancel()
    }
    private func render() {
        generation += 1; task?.cancel(); task = nil; pending?.cancel(); result = nil; searchBusy = false
        let records = records, query = query, now = observedAt
        indices = records.indices.reversed().filter { records[$0].matchesFacets(query, now: now) }
        if query.searchesPayload {
            indices = []; let current = generation, payloads = payloads
            let work = DispatchWorkItem { [weak self] in
                guard let self, current == self.generation else { return }
                self.task = InspectorRecordSearchTask.submit(search: { cancelled in
                    InspectorRecordSearch.run(Array(records.reversed()), query: query, now: now, http: nil, sockets: nil, savedPayloads: payloads, cancelled: cancelled)
                }, completion: { [weak self] result in
                    guard let self, current == self.generation else { return }
                    self.task = nil; self.result = result
                    self.indices = records.indices.reversed().filter { result.keys.contains(records[$0].key) }; self.display()
                })
                if self.task == nil { self.searchBusy = true; self.display() }
            }
            pending = work; DispatchQueue.main.asyncAfter(deadline: .now() + 0.2, execute: work)
        }
        display()
    }
    private func display() {
        switch evidence {
        case .logs: title = InspectorLocalization.text("logs")
        case .http: title = "HTTP"
        case .webSockets: title = connectionID == nil ? "WebSocket" : InspectorLocalization.text("ui_connection_records")
        }
        var notes = [InspectorLocalization.text("ui_evidence_saved") + " · " + InspectorValuePresentation.eventTime(observedAt, full: true)]
        if query.searchesPayload && result == nil { notes.append(InspectorLocalization.text(searchBusy ? "busy" : "ui_searching")) }
        if let note = InspectorSearchFeedback.text(result) { notes.append(note) }
        switch evidence {
        case .logs(let value): if value.omittedEventCount > 0 || value.sourceEvictedCount > 0 { notes.append(InspectorLocalization.text("ui_retention_loss")) }
        case .http(let value): if value.omittedEventCount > 0 || value.sourceEvictedEvents > 0 || value.sourceEvictedBodies > 0 { notes.append(InspectorLocalization.text("ui_retention_loss")) }
        case .webSockets(let value): if value.omittedEventCount > 0 || value.sourceEvictedEvents > 0 || value.sourceEvictedPayloads > 0 { notes.append(InspectorLocalization.text("ui_retention_loss")) }
        }
        header.status.text = notes.joined(separator: "\n")
        header.search.placeholder = InspectorLocalization.text("ui_search_saved")
        header.relabel(paused: false, query: query)
        header.count.text = String(format: InspectorLocalization.text("ui_result_count"), indices.count, records.count)
        table.reloadData()
        table.setEmptyState(indices.isEmpty ? InspectorTheme.empty(query.searchesPayload && result == nil ? searchBusy ? "busy" : "ui_searching" : records.isEmpty ? "incident_evidence_empty" : "ui_filtered_empty") : nil)
    }
    @objc private func showFilters() {
        let groups: [InspectorFilterGroup]
        switch evidence {
        case .logs(let value): groups = InspectorFilterOptions.logs(value.events)
        case .http(let value): groups = InspectorFilterOptions.network(value.events, sockets: [], query: query).filter { $0.field != .transport && !InspectorQuery.socketFields.contains($0.field) }
        case .webSockets(let value): groups = InspectorFilterOptions.network([], sockets: value.events, query: query, includeHTTP: false)
        }
        let filters = InspectorFilterViewController(query: query, groups: groups, payload: true) { [weak self] value in self?.query = value; self?.render() }
        view.endEditing(true); inspectorPresent(filters)
    }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { indices.count }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let index = indices[indexPath.row]
        let cell = table.dequeueReusableCell(withIdentifier: "evidence") as? InspectorEventCell ?? InspectorEventCell(style: .default, reuseIdentifier: "evidence")
        let record = records[index], preview = result?.previews[record.key]
        switch evidence {
        case .logs(let value): cell.configure(InspectorRecordPresentation.log(value.events[index], preview: preview))
        case .http(let value):
            let source = result?.previewSources[record.key].map(InspectorLocalization.text)
            cell.configure(InspectorRecordPresentation.http(value.events[index], preview: preview.map { (source ?? "") + ": " + $0 }))
        case .webSockets(let value):
            let event = value.events[index]
            var text = preview
            if text == nil, event.kind == .message, let id = event.payload?.blobID {
                if let payload = value.payloads[id], payload.state == .available, let body = payload.text {
                    text = body.isEmpty ? InspectorLocalization.text("ui_content_empty") : InspectorRecordSearch.preview(body, term: "")
                } else { text = IncidentPayloadPresentation.content(value.payloads[id]) }
            }
            cell.configure(InspectorRecordPresentation.socket(event, preview: text))
        }
        return cell
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        table.deselectRow(at: indexPath, animated: true)
        let index = indices[indexPath.row], controller: UIViewController
        switch evidence {
        case .logs(let value): controller = LogDetailViewController(event: value.events[index])
        case .http(let value): controller = HTTPDetailViewController(event: value.events[index], savedPayloads: value.payloads)
        case .webSockets(let value):
            let detail = WebSocketDetailViewController(event: value.events[index], savedPayloads: value.payloads)
            detail.showConnection = { [weak detail] in detail?.inspectorPush(IncidentEvidenceViewController(.webSockets(value), connectionID: value.events[index].connectionID)) }; controller = detail
        }
        inspectorPush(controller)
    }
}
#endif

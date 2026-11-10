#if canImport(UIKit)
import OsservioCore
import UIKit

/// A bounded live log feed. Reading, navigation and presentation never change capture.
@MainActor
public final class LogInspectorViewController: UITableViewController {
    private let recorder: LogRecorder
    private let history: any LogHistory
    private let table = InspectorTableView(frame: .zero, style: .plain)
    private let header = InspectorFeedHeader(compact: true)
    private lazy var refreshLoop: InspectorRefreshLoop = {
        let loop = InspectorRefreshLoop { [weak self] in self?.refresh() }
        loop.suspended = { [weak self] in self?.cancelSearch() }; return loop
    }()
    private var task: InspectorRecordSearchTask?
    private var result: InspectorRecordSearch.Result?
    private var generation: UInt64 = 0
    private var nextSearchAt: TimeInterval = 0
    private var timeCheck: TimeInterval = 0
    private lazy var style = InspectorStyleObserver { [weak self] in self?.relabel() }
    private var latest: LogSnapshot?
    private var events: [LogEvent] = []
    private var anchor: Int64 = 0
    private var dirty = true
    private var attemptedRead = false
    private var stale = false
    private(set) var query = InspectorQuery()
    private(set) var paused = false

    public init(recorder: LogRecorder, history: any LogHistory) {
        self.recorder = recorder; self.history = history; super.init(style: .plain)
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    public override func loadView() { super.loadView(); tableView = table }
    public override func viewDidLoad() {
        super.viewDidLoad(); table.tableHeaderView = header
        table.rowHeight = UITableView.automaticDimension; table.estimatedRowHeight = 100
        table.keyboardDismissMode = .onDrag
        header.onSearch = { [weak self] value in guard let self else { return }; var query = self.query; query.text = value; self.apply(query) }
        header.onQueryChange = { [weak self] in self?.apply($0) }
        header.pause.addTarget(self, action: #selector(followLatest), for: .touchUpInside)
        header.filters.addTarget(self, action: #selector(showFilters), for: .touchUpInside)
        header.actions.addTarget(self, action: #selector(clear), for: .touchUpInside)
        style.changed()
    }
    private func relabel() {
        InspectorTheme.apply(self); title = InspectorLocalization.text("logs")
        header.search.placeholder = InspectorLocalization.text("search"); display()
    }
    public override func viewWillAppear(_ animated: Bool) { super.viewWillAppear(animated); refreshLoop.show(in: view) }
    public override func viewDidAppear(_ animated: Bool) { super.viewDidAppear(animated); refreshLoop.show(in: view) }
    public override func viewWillDisappear(_ animated: Bool) { super.viewWillDisappear(animated); refreshLoop.hide(); view.endEditing(true) }
    private func refresh() {
        guard presentedViewController == nil else { return }
        if !paused && task == nil {
            anchor = Int64(Date().timeIntervalSince1970 * 1_000)
            attemptedRead = true
            let snapshot = history.snapshot(), failed = snapshot == nil
            dirty = dirty || stale != failed; stale = failed
            if let snapshot { dirty = dirty || latest?.revision != snapshot.revision; latest = snapshot }
        }
        let now = Date.timeIntervalSinceReferenceDate
        if !paused && query.recentMinutes > 0 && now - timeCheck >= 5 { dirty = true; timeCheck = now }
        if dirty && task == nil && now >= nextSearchAt { render() }
        updateStatus()
    }
    private func cancelSearch() { generation &+= 1; task?.cancel(); task = nil; dirty = true }
    private func apply(_ query: InspectorQuery) {
        cancelSearch(); self.query = query; result = nil; events = []
        nextSearchAt = Date.timeIntervalSinceReferenceDate + 0.2; display(); refresh()
    }
    private func updateStatus() {
        var notes: [String] = []
        if task != nil || dirty && query.searchesPayload { notes.append(InspectorLocalization.text("ui_searching")) }
        if let note = InspectorSearchFeedback.text(result) { notes.append(note) }
        if stale { notes.append(InspectorLocalization.text(latest == nil ? "busy" : "ui_previous_window")) }
        if (latest?.evictedCount ?? 0) > 0 || (recorder.status()?.rateLimitedCount ?? 0) > 0 { notes.append(InspectorLocalization.text("ui_retention_loss")) }
        header.status.text = notes.joined(separator: " · ")
        header.relabel(paused: paused, query: query); table.setNeedsLayout()
    }
    private func render() {
        let records = (latest?.events ?? []).reversed().map(InspectorSearchRecord.log), query = query, anchor = anchor
        if !query.searchesPayload { events = (latest?.events ?? []).reversed().filter { query.matchesFacets($0, now: anchor) }; result = nil; dirty = false; display(); return }
        let current = generation
        task = InspectorRecordSearchTask.submit(search: { cancelled in
            InspectorRecordSearch.run(records, query: query, now: anchor, http: nil, sockets: nil, cancelled: cancelled)
        }, completion: { [weak self] result in
            guard let self, self.generation == current else { return }
            self.task = nil; self.result = result
            self.events = records.compactMap { record in guard result.keys.contains(record.key), case .log(let event) = record else { return nil }; return event }
            self.dirty = false; self.nextSearchAt = Date.timeIntervalSinceReferenceDate + 1; self.display()
        })
        if task == nil { nextSearchAt = Date.timeIntervalSinceReferenceDate + 0.5 }
    }
    private func display() {
        table.reloadData()
        header.count.text = String(format: InspectorLocalization.text("ui_result_count"), events.count, latest?.events.count ?? 0)
        let key = dirty && query.searchesPayload ? "ui_searching" : latest == nil ? attemptedRead ? "busy" : "ui_reading"
            : latest?.events.isEmpty == false ? "ui_filtered_empty" : recorder.status()?.isRecording == true ? "ui_waiting" : "ui_inactive_empty"
        table.setEmptyState(events.isEmpty ? InspectorTheme.empty(key) : nil); updateStatus()
    }
    @objc private func followLatest() {
        paused = false; dirty = true
        table.setContentOffset(CGPoint(x: 0, y: -table.adjustedContentInset.top), animated: false); refresh()
    }
    @objc private func showFilters() {
        view.endEditing(true)
        inspectorPresent(InspectorFilterViewController(query: query, groups: InspectorFilterOptions.logs(latest?.events ?? []), payload: true) { [weak self] in self?.apply($0) })
    }
    @objc private func clear() {
        inspectorConfirm(InspectorLocalization.text("clear"), message: InspectorLocalization.text("ui_clear_hint")) { [weak self] in
            guard let self else { return }; self.cancelSearch(); self.history.clear(); self.latest = self.history.snapshot()
            self.result = nil; self.events = []; self.nextSearchAt = 0; self.render()
        }
    }
    public override func scrollViewWillBeginDragging(_ scrollView: UIScrollView) { paused = true; cancelSearch(); dirty = false; updateStatus() }
    public override func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) { if !decelerate { resumeAtTop() } }
    public override func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) { resumeAtTop() }
    private func resumeAtTop() { if table.contentOffset.y + table.adjustedContentInset.top <= 1 { paused = false; refresh() } }
    public override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { events.count }
    public override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let event = events[indexPath.row]
        let cell = table.dequeueReusableCell(withIdentifier: "log") as? InspectorEventCell ?? InspectorEventCell(style: .default, reuseIdentifier: "log")
        cell.configure(badge: InspectorFilterOptions.label(.level, event.level.rawValue), time: event.timestampMillis,
            title: result?.previews[InspectorSearchRecord.log(event).key] ?? String(event.message.prefix(600)), subtitle: event.category + (event.isTruncated ? " · " + InspectorLocalization.text("truncated") : ""),
            color: event.level == .error ? .systemRed : event.level == .warning ? .systemOrange : InspectorTheme.accent)
        return cell
    }
    public override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        table.deselectRow(at: indexPath, animated: true); inspectorPush(LogDetailViewController(event: events[indexPath.row]))
    }
}

@MainActor
final class LogDetailViewController: InspectorDetailViewController {
    private let event: LogEvent
    init(event: LogEvent) { self.event = event; super.init() }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated); title = event.category
        inspectorPrimaryAction = inspectorCopyItem(target: self, action: #selector(copyMessage), label: "copy")
        sections = [Section(title: InspectorLocalization.text("ui_metadata"), rows: [
            Row(InspectorLocalization.text("ui_level"), InspectorFilterOptions.label(.level, event.level.rawValue)),
            Row(InspectorLocalization.text("ui_category"), event.category),
            Row(InspectorLocalization.text("http_time"), InspectorLocalization.date(event.timestampMillis)),
            Row(InspectorLocalization.text("http_session"), event.sessionID + " · #\(event.sequence)")
        ]), Section(title: InspectorLocalization.text("ui_read_content"), rows: [
            Row(String(event.message.prefix(600)), action: { [weak self] in
                guard let self else { return }; self.read(self.event.category, content: InspectorReadContent(text: self.event.message,
                    notice: self.event.isTruncated ? InspectorLocalization.text("truncated") : "", allowsJSON: !self.event.isTruncated && !self.event.isPrivate))
            })
        ], footer: [event.isPrivate ? InspectorLocalization.text("crumb_private") : "", event.isTruncated ? InspectorLocalization.text("truncated") : ""].filter { !$0.isEmpty }.joined(separator: " · "))]
    }
    @objc private func copyMessage() { UIPasteboard.general.string = event.message }
}
#endif

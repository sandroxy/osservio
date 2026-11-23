#if canImport(UIKit)
import OsservioCore
import UIKit

@MainActor
final class BreadcrumbInspectorViewController: UITableViewController {
    private let recorder: BreadcrumbRecorder
    private let history: any BreadcrumbHistory
    private let table = InspectorTableView(frame: .zero, style: .insetGrouped)
    private let header = InspectorFeedHeader(compact: true, actionLabel: "crumb_clear")
    private let recentFilters: InspectorRecentFilters
    private var query = InspectorQuery()
    private var paused = false { didSet { table.followsLatest = !paused } }
    private var anchor: Int64 = 0
    private lazy var loop = InspectorRefreshLoop { [weak self] in self?.refresh() }
    private lazy var style = InspectorStyleObserver { [weak self] in self?.relabel() }
    private var latest: BreadcrumbSnapshot?
    private var events: [BreadcrumbEvent] = []
    private var dirty = true
    private var attemptedRead = false
    private var stale = false
    init(recorder: BreadcrumbRecorder, history: any BreadcrumbHistory, recent: InspectorRecentFilters? = nil) {
        recentFilters = recent ?? InspectorRecentFilters("breadcrumbs")
        self.recorder = recorder; self.history = history; super.init(style: .plain)
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func loadView() { super.loadView(); tableView = table }
    override func viewDidLoad() {
        super.viewDidLoad(); table.tableHeaderView = header; header.feedbackTable = table
        table.rowHeight = UITableView.automaticDimension; table.estimatedRowHeight = 102; table.keyboardDismissMode = .onDrag
        header.onQueryChange = { [weak self] value in self?.query = value; self?.render() }
        header.onSearch = { [weak self] value in self?.query.text = value; self?.render() }
        table.followLatestButton = header.pause; table.followsLatest = true
        header.pause.addTarget(self, action: #selector(followLatest), for: .touchUpInside)
        header.onFilters = { [weak self] in self?.showFilters($0) }
        style.changed()
    }
    private func relabel() {
        InspectorTheme.apply(self); title = InspectorLocalization.text("ui_current_timeline")
        inspectorPrimaryAction = inspectorDeleteItem(target: self, action: #selector(showActions), label: "crumb_clear")
        header.search.placeholder = InspectorLocalization.text("crumb_search"); render()
    }
    override func viewWillAppear(_ animated: Bool) { super.viewWillAppear(animated); loop.show(in: view) }
    override func viewDidAppear(_ animated: Bool) { super.viewDidAppear(animated); loop.show(in: view) }
    override func viewWillDisappear(_ animated: Bool) { super.viewWillDisappear(animated); loop.hide(); view.endEditing(true) }
    private func refresh() {
        guard presentedViewController == nil else { return }
        if !paused {
            anchor = Int64(Date().timeIntervalSince1970 * 1_000)
            attemptedRead = true
            let snapshot = history.snapshot(), failed = snapshot == nil
            dirty = dirty || stale != failed; stale = failed
            if let snapshot { dirty = dirty || latest?.revision != snapshot.revision; latest = snapshot }
        }
        if dirty || !paused && query.recentMinutes > 0 { render() } else { updateStatus() }
    }
    private func render() {
        table.preservingReadingPosition(count: { events.count }, key: { events[$0].id }) {
            events = (latest?.events ?? []).reversed().filter { query.matches($0, now: anchor) }
            table.reloadData(); dirty = false
            header.count.text = String(format: InspectorLocalization.text("ui_result_count"), events.count, latest?.events.count ?? 0)
            table.setFeedEmpty(events.isEmpty ? (latest == nil ? attemptedRead ? "busy" : "ui_reading" : latest?.events.isEmpty == false ? "ui_filtered_empty" : "crumb_empty") : nil, header: header, query: query)
            updateStatus()
        }
    }
    private func updateStatus() {
        var notes: [String] = []
        if stale { notes.append(InspectorLocalization.text(latest == nil ? "busy" : "ui_previous_window")) }
        if (latest?.evictedCount ?? 0) > 0 || (recorder.status()?.rateLimitedCount ?? 0) > 0 { notes.append(InspectorLocalization.text("ui_retention_loss")) }
        header.status.text = notes.joined(separator: " · ")
        header.relabel(paused: paused, query: query); table.setNeedsLayout()
    }
    @objc private func followLatest() {
        paused = false; dirty = true; refresh()
        table.showLatest()
    }
    private func showFilters(_ target: InspectorQuery.Target?) {
        let filters = InspectorFilterViewController(query: query, focus: target, recent: recentFilters,
            groups: InspectorFilterOptions.breadcrumbs(latest?.events ?? [])) { [weak self] value in self?.query = value; self?.render() }
        view.endEditing(true); inspectorPresent(filters)
    }
    func resetOwnerQuery() {
        query = InspectorQuery(); dirty = true
        if isViewLoaded { header.search.text = ""; header.expireUndo(); render() }
    }
    override func tableView(_ tableView: UITableView, contextMenuConfigurationForRowAt indexPath: IndexPath, point: CGPoint) -> UIContextMenuConfiguration? {
        guard events.indices.contains(indexPath.row) else { return nil }
        return header.recordMenu(field: .breadcrumbKind, value: events[indexPath.row].kind.rawValue, recent: recentFilters)
    }
    @objc private func showActions() {
        inspectorConfirm(InspectorLocalization.text("crumb_clear"), message: InspectorLocalization.text("ui_clear_hint")) { [weak self] in
            guard let self else { return }; self.history.clear(); self.latest = self.history.snapshot(); self.render()
        }
    }
    override func scrollViewWillBeginDragging(_ scrollView: UIScrollView) { if !paused { paused = true; updateStatus() } }
    override func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) { if !decelerate { resumeAtTop() } }
    override func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) { resumeAtTop() }
    private func resumeAtTop() { if paused && table.contentOffset.y <= -table.adjustedContentInset.top + 1 { followLatest() } }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { events.count }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let event = events[indexPath.row]
        let cell = table.dequeueReusableCell(withIdentifier: "crumb") as? InspectorEventCell ?? InspectorEventCell(style: .default, reuseIdentifier: "crumb")
        cell.configure(badge: event.kind == .hostMarker ? RuntimePresentation.kind(event) : "", time: event.timestampMillis, title: RuntimePresentation.breadcrumbTitle(event), subtitle: RuntimePresentation.breadcrumbSubtitle(event))
        return cell
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        table.deselectRow(at: indexPath, animated: true); inspectorPush(BreadcrumbDetailViewController(event: events[indexPath.row]))
    }
}

#endif

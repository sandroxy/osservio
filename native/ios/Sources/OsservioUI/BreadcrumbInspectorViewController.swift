#if canImport(UIKit)
import OsservioCore
import UIKit

@MainActor
final class BreadcrumbInspectorViewController: UITableViewController {
    private let recorder: BreadcrumbRecorder
    private let history: any BreadcrumbHistory
    private let table = InspectorTableView(frame: .zero, style: .plain)
    private let header = InspectorFeedHeader()
    private var query = InspectorQuery()
    private var paused = false
    private var anchor: Int64 = 0
    private lazy var loop = InspectorRefreshLoop { [weak self] in self?.refresh() }
    private lazy var style = InspectorStyleObserver { [weak self] in self?.relabel() }
    private var latest: BreadcrumbSnapshot?
    private var events: [BreadcrumbEvent] = []
    private var dirty = true
    private var attemptedRead = false
    private var stale = false
    init(recorder: BreadcrumbRecorder, history: any BreadcrumbHistory) {
        self.recorder = recorder; self.history = history; super.init(style: .plain)
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func loadView() { super.loadView(); tableView = table }
    override func viewDidLoad() {
        super.viewDidLoad(); table.tableHeaderView = header
        table.rowHeight = UITableView.automaticDimension; table.estimatedRowHeight = 102; table.keyboardDismissMode = .onDrag
        header.onSearch = { [weak self] value in self?.query.text = value; self?.render() }
        header.pause.addTarget(self, action: #selector(togglePause), for: .touchUpInside)
        header.filters.addTarget(self, action: #selector(showFilters), for: .touchUpInside)
        header.actions.addTarget(self, action: #selector(showActions), for: .touchUpInside)
        style.changed()
    }
    private func relabel() {
        InspectorTheme.apply(self); title = InspectorLocalization.text("ui_current_timeline")
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
        if dirty || query.recentMinutes > 0 { render() } else { updateStatus() }
    }
    private func render() {
        events = (latest?.events ?? []).reversed().filter { event in
            query.allows(.kind, event.kind.rawValue) && query.includesTime(event.timestampMillis, now: anchor)
                && query.contains(event.label, event.message ?? "", RuntimePresentation.kind(event))
        }
        table.reloadData(); dirty = false
        header.count.text = String(format: InspectorLocalization.text("ui_result_count"), events.count, latest?.events.count ?? 0)
        table.setEmptyState(events.isEmpty ? InspectorTheme.empty(latest == nil ? attemptedRead ? "busy" : "ui_reading" : latest?.events.isEmpty == false ? "ui_filtered_empty" : "crumb_empty") : nil)
        updateStatus()
    }
    private func updateStatus() {
        header.status.text = paused ? InspectorLocalization.text("ui_paused")
            : recorder.captureController.status().map { InspectorLocalization.text("http_" + $0.mode.rawValue) } ?? InspectorLocalization.text("busy")
        if stale { header.status.text! += " · " + InspectorLocalization.text(latest == nil ? "busy" : "ui_previous_window") }
        header.relabel(paused: paused, query: query); table.setNeedsLayout()
    }
    @objc private func togglePause() {
        paused.toggle(); dirty = true
        if !paused { table.setContentOffset(CGPoint(x: 0, y: -table.adjustedContentInset.top), animated: false) }
        refresh()
    }
    @objc private func showFilters() {
        let group = InspectorFilterGroup(.kind, values: (latest?.events ?? []).map { $0.kind.rawValue })
        let filters = InspectorFilterViewController(query: query, groups: [group]) { [weak self] value in self?.query = value; self?.render() }
        view.endEditing(true); inspectorPresent(filters)
    }
    @objc private func showActions() {
        let enabled = recorder.captureController.status()?.baselineEnabled == true
        let menu = UIAlertController(title: InspectorLocalization.text("ui_actions"), message: InspectorLocalization.text("crumb_baseline_hint"), preferredStyle: .actionSheet)
        menu.addAction(UIAlertAction(title: InspectorLocalization.text(enabled ? "crumb_disable_baseline" : "crumb_enable_baseline"), style: .default) { [weak self] _ in
            guard let self else { return }
            if let status = self.recorder.captureController.status(), self.recorder.captureController.setBaselineEnabled(!status.baselineEnabled) { self.updateStatus() }
            else { self.inspectorNotice(InspectorLocalization.text("busy")) }
        })
        menu.addAction(UIAlertAction(title: InspectorLocalization.text("ui_capture_details"), style: .default) { [weak self] _ in
            guard let self else { return }
            let status = self.recorder.captureController.status(), recording = self.recorder.status()
            self.inspectorNotice(String(format: InspectorLocalization.text("crumb_summary"),
                status.map { InspectorLocalization.text("http_" + $0.mode.rawValue) } ?? InspectorLocalization.text("busy"),
                self.latest?.events.count ?? 0, self.latest?.evictedCount ?? 0, recording?.rateLimitedCount ?? 0))
        })
        menu.addAction(UIAlertAction(title: InspectorLocalization.text("crumb_clear"), style: .destructive) { [weak self] _ in
            self?.inspectorConfirm(InspectorLocalization.text("crumb_clear"), message: InspectorLocalization.text("ui_clear_hint")) { [weak self] in
                guard let self else { return }; self.history.clear(); self.latest = self.history.snapshot(); self.render()
            }
        })
        menu.addAction(UIAlertAction(title: InspectorLocalization.text("archive_cancel"), style: .cancel))
        menu.popoverPresentationController?.sourceView = header.actions; menu.popoverPresentationController?.sourceRect = header.actions.bounds
        present(menu, animated: true)
    }
    override func scrollViewWillBeginDragging(_ scrollView: UIScrollView) { if !paused { paused = true; updateStatus() } }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { events.count }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let event = events[indexPath.row]
        let cell = table.dequeueReusableCell(withIdentifier: "crumb") as? InspectorEventCell ?? InspectorEventCell(style: .default, reuseIdentifier: "crumb")
        cell.configure(badge: RuntimePresentation.kind(event), time: event.timestampMillis, title: event.label, subtitle: event.message ?? "#\(event.sequence)")
        return cell
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        table.deselectRow(at: indexPath, animated: true); inspectorPush(BreadcrumbDetailViewController(event: events[indexPath.row]))
    }
}

@MainActor
final class BreadcrumbDetailViewController: InspectorContentViewController {
    init(event: BreadcrumbEvent) {
        super.init(title: RuntimePresentation.kind(event),
            content: InspectorLocalization.text("crumb_detail_hint") + "\n\n" + RuntimePresentation.breadcrumb(event), code: false)
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
}
#endif

#if canImport(UIKit)
import OsservioCore
import UIKit

/// A bounded live log feed. Reading, navigation and presentation never change capture.
@MainActor
public final class LogInspectorViewController: UITableViewController {
    private let recorder: LogRecorder
    private let history: any LogHistory
    private let table = InspectorTableView(frame: .zero, style: .plain)
    private let header = InspectorFeedHeader()
    private lazy var refreshLoop = InspectorRefreshLoop { [weak self] in self?.refresh() }
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
        header.onSearch = { [weak self] value in self?.query.text = value; self?.render() }
        header.pause.addTarget(self, action: #selector(togglePause), for: .touchUpInside)
        header.filters.addTarget(self, action: #selector(showFilters), for: .touchUpInside)
        header.actions.addTarget(self, action: #selector(showActions), for: .touchUpInside)
        style.changed()
    }
    private func relabel() {
        InspectorTheme.apply(self); title = InspectorLocalization.text("logs")
        header.search.placeholder = InspectorLocalization.text("search"); render()
    }
    public override func viewWillAppear(_ animated: Bool) { super.viewWillAppear(animated); refreshLoop.show(in: view) }
    public override func viewDidAppear(_ animated: Bool) { super.viewDidAppear(animated); refreshLoop.show(in: view) }
    public override func viewWillDisappear(_ animated: Bool) { super.viewWillDisappear(animated); refreshLoop.hide(); view.endEditing(true) }
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
    private func updateStatus() {
        let recording = recorder.status()?.isRecording == true
        header.status.text = InspectorLocalization.text(paused ? "ui_paused" : recording ? "ui_capturing" : "ui_not_capturing")
        if stale { header.status.text! += " · " + InspectorLocalization.text(latest == nil ? "busy" : "ui_previous_window") }
        header.relabel(paused: paused, query: query); table.setNeedsLayout()
    }
    private func render() {
        events = (latest?.events ?? []).reversed().filter { query.matches($0, now: anchor) }
        table.reloadData(); dirty = false
        header.count.text = String(format: InspectorLocalization.text("ui_result_count"), events.count, latest?.events.count ?? 0)
        let key = latest == nil ? attemptedRead ? "busy" : "ui_reading" : latest?.events.isEmpty == false ? "ui_filtered_empty"
            : recorder.status()?.isRecording == true ? "ui_waiting" : "ui_inactive_empty"
        table.setEmptyState(events.isEmpty ? InspectorTheme.empty(key) : nil)
        updateStatus()
    }
    @objc private func togglePause() {
        paused.toggle(); dirty = true
        if !paused { table.setContentOffset(CGPoint(x: 0, y: -table.adjustedContentInset.top), animated: false) }
        refresh()
    }
    @objc private func showFilters() {
        view.endEditing(true)
        let filters = InspectorFilterViewController(query: query, groups: InspectorFilterOptions.logs(latest?.events ?? [])) { [weak self] query in
            self?.query = query; self?.render()
        }
        inspectorPresent(filters)
    }
    @objc private func showActions() {
        let active = recorder.status()?.isRecording == true
        let menu = UIAlertController(title: InspectorLocalization.text("ui_actions"), message: nil, preferredStyle: .actionSheet)
        menu.addAction(UIAlertAction(title: InspectorLocalization.text(active ? "stopCapture" : "startCapture"), style: .default) { [weak self] _ in self?.toggleCapture() })
        menu.addAction(UIAlertAction(title: InspectorLocalization.text("ui_capture_details"), style: .default) { [weak self] _ in
            guard let self else { return }
            let status = self.recorder.status()
            self.inspectorNotice(String(format: InspectorLocalization.text("summary"), InspectorLocalization.text(status?.isRecording == true ? "capturing" : "stopped"),
                self.latest?.events.count ?? 0, self.latest?.evictedCount ?? 0, status?.rateLimitedCount ?? 0))
        })
        menu.addAction(UIAlertAction(title: InspectorLocalization.text("clear"), style: .destructive) { [weak self] _ in
            self?.inspectorConfirm(InspectorLocalization.text("clear"), message: InspectorLocalization.text("ui_clear_hint")) { [weak self] in
                guard let self else { return }; self.history.clear(); self.latest = self.history.snapshot(); self.render()
            }
        })
        menu.addAction(UIAlertAction(title: InspectorLocalization.text("archive_cancel"), style: .cancel))
        menu.popoverPresentationController?.sourceView = header.actions; menu.popoverPresentationController?.sourceRect = header.actions.bounds
        present(menu, animated: true)
    }
    private func toggleCapture() {
        guard let status = recorder.status() else { inspectorNotice(InspectorLocalization.text("busy")); return }
        if status.isRecording { if !recorder.stop() { inspectorNotice(InspectorLocalization.text("busy")) } }
        else { do { try recorder.start() } catch { inspectorNotice(InspectorLocalization.text("busy")) } }
        dirty = true; refresh()
    }
    public override func scrollViewWillBeginDragging(_ scrollView: UIScrollView) { if !paused { paused = true; updateStatus() } }
    public override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { events.count }
    public override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let event = events[indexPath.row]
        let cell = table.dequeueReusableCell(withIdentifier: "log") as? InspectorEventCell ?? InspectorEventCell(style: .default, reuseIdentifier: "log")
        cell.configure(badge: InspectorFilterOptions.label(.level, event.level.rawValue), time: event.timestampMillis,
            title: event.message, subtitle: event.category + (event.isTruncated ? " · " + InspectorLocalization.text("truncated") : ""),
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
        inspectorPrimaryAction = UIBarButtonItem(title: InspectorLocalization.text("copy"), style: .plain, target: self, action: #selector(copyMessage))
        sections = [Section(title: InspectorLocalization.text("ui_metadata"), rows: [
            Row(InspectorLocalization.text("ui_level"), InspectorFilterOptions.label(.level, event.level.rawValue)),
            Row(InspectorLocalization.text("ui_category"), event.category),
            Row(InspectorLocalization.text("http_time"), InspectorLocalization.date(event.timestampMillis)),
            Row(InspectorLocalization.text("http_session"), event.sessionID + " · #\(event.sequence)")
        ]), Section(title: InspectorLocalization.text("ui_read_content"), rows: [
            Row(String(event.message.prefix(600)), action: { [weak self] in
                guard let self else { return }; self.read(self.event.category, content: self.event.message, code: false)
            })
        ], footer: [event.isPrivate ? InspectorLocalization.text("crumb_private") : "", event.isTruncated ? InspectorLocalization.text("truncated") : ""].filter { !$0.isEmpty }.joined(separator: " · "))]
    }
    @objc private func copyMessage() { UIPasteboard.general.string = event.message }
}
#endif

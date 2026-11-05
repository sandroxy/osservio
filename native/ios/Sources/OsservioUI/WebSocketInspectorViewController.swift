#if canImport(UIKit)
import OsservioCore
import UIKit

/// Connection groups and their event timelines refresh only while visible; payload lookup is explicit.
@MainActor
public final class WebSocketInspectorViewController: UIViewController, UITableViewDataSource,
    UITableViewDelegate, UISearchResultsUpdating {
    private let recorder: WebSocketRecorder
    private let history: any WebSocketHistory
    private let connectionID: String?
    private var counts: [String: Int] = [:]
    private let table = UITableView(frame: .zero, style: .plain)
    private let summary = UILabel()
    private let filter = UISegmentedControl(items: [WebSocketPresentation.text("ws_all"),
        WebSocketPresentation.text("ws_issues"), WebSocketPresentation.text("ws_messages")])
    private let search = UISearchController(searchResultsController: nil)
    private let capture = UIButton(type: .system)
    private let pause = UIButton(type: .system)
    private var timer: Timer?
    private var latest: WebSocketSnapshot?
    private var events: [WebSocketEvent] = []
    private var paused = false
    private var dirty = true

    public init(recorder: WebSocketRecorder, history: any WebSocketHistory) {
        self.recorder = recorder; self.history = history; connectionID = nil
        super.init(nibName: nil, bundle: nil)
    }
    private init(recorder: WebSocketRecorder, history: any WebSocketHistory, connectionID: String) {
        self.connectionID = connectionID
        self.recorder = recorder; self.history = history
        super.init(nibName: nil, bundle: nil)
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }

    public override func viewDidLoad() {
        super.viewDidLoad()
        title = connectionID.map { String($0.prefix(8)) } ?? WebSocketPresentation.text("ws_title")
        view.backgroundColor = .systemBackground
        navigationItem.searchController = search
        navigationItem.hidesSearchBarWhenScrolling = false
        search.obscuresBackgroundDuringPresentation = false
        search.searchBar.placeholder = WebSocketPresentation.text("ws_search")
        search.searchResultsUpdater = self
        definesPresentationContext = true
        summary.font = .preferredFont(forTextStyle: .caption1)
        summary.adjustsFontForContentSizeCategory = true
        summary.textColor = .secondaryLabel
        summary.numberOfLines = 0
        capture.addTarget(self, action: #selector(toggleCapture), for: .touchUpInside)
        pause.setTitle(WebSocketPresentation.text("pauseView"), for: .normal)
        pause.addTarget(self, action: #selector(togglePause), for: .touchUpInside)
        let clear = UIButton(type: .system)
        clear.setTitle(WebSocketPresentation.text("ws_clear"), for: .normal)
        clear.addTarget(self, action: #selector(clearHistory), for: .touchUpInside)
        let controls = UIStackView(arrangedSubviews: [capture, pause, clear])
        controls.distribution = .fillEqually
        controls.spacing = 8
        for button in [capture, pause, clear] {
            button.titleLabel?.numberOfLines = 0
            button.titleLabel?.textAlignment = .center
            button.titleLabel?.font = .preferredFont(forTextStyle: .subheadline)
            button.titleLabel?.adjustsFontForContentSizeCategory = true
            button.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        }
        filter.selectedSegmentIndex = 0
        filter.addTarget(self, action: #selector(filterChanged), for: .valueChanged)
        let header = UIStackView(arrangedSubviews: [summary, controls, filter])
        header.axis = .vertical; header.spacing = 8
        header.isLayoutMarginsRelativeArrangement = true
        header.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 12, leading: 16, bottom: 12, trailing: 16)
        let stack = UIStackView(arrangedSubviews: [header, table])
        stack.axis = .vertical; stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            stack.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor)
        ])
        table.dataSource = self; table.delegate = self
        table.rowHeight = UITableView.automaticDimension; table.estimatedRowHeight = 88
        table.keyboardDismissMode = .onDrag; table.tableFooterView = UIView()
    }

    public override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        refresh()
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, self.timer != nil else { return }
                self.refresh()
            }
        }
    }
    public override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        timer?.invalidate(); timer = nil
    }
    deinit { timer?.invalidate() }

    private func refresh() {
        if !paused, let snapshot = history.snapshot() {
            if latest?.revision != snapshot.revision { dirty = true }
            latest = snapshot
        }
        if dirty { render() }
        if let status = recorder.captureController.status(), let socketStatus = recorder.status(), let latest {
            capture.setTitle(WebSocketPresentation.text(status.mode == .detailed ? "http_stop" : "http_start"), for: .normal)
            summary.text = String(format: WebSocketPresentation.text("ws_summary"),
                                  WebSocketPresentation.text("http_" + status.mode.rawValue), latest.events.count,
                                  latest.evictedEvents, latest.evictedPayloads, socketStatus.rateLimitedCount)
        }
    }
    private func render() {
        let query = search.searchBar.text ?? ""
        let matching = (latest?.events ?? []).reversed().filter { event in
            let matchesFilter = filter.selectedSegmentIndex == 0
                || (filter.selectedSegmentIndex == 1 && WebSocketPresentation.hasIssue(event))
                || (filter.selectedSegmentIndex == 2 && WebSocketPresentation.isMessage(event))
            return (connectionID == nil || connectionID == event.connectionID) && matchesFilter
                && (query.isEmpty || event.url.localizedCaseInsensitiveContains(query)
                    || event.connectionID.localizedCaseInsensitiveContains(query)
                    || event.id.localizedCaseInsensitiveContains(query)
                    || WebSocketPresentation.summary(event).localizedCaseInsensitiveContains(query))
        }
        counts = [:]
        events = []
        for event in matching {
            counts[event.connectionID, default: 0] += 1
            if connectionID != nil || counts[event.connectionID] == 1 { events.append(event) }
        }
        table.reloadData()
        let empty = UILabel()
        empty.text = WebSocketPresentation.text(latest == nil ? "busy" : latest?.events.isEmpty == false ? "ws_no_matches" : "ws_empty")
        empty.textColor = .secondaryLabel; empty.numberOfLines = 0; empty.textAlignment = .center
        empty.font = .preferredFont(forTextStyle: .body); empty.adjustsFontForContentSizeCategory = true
        table.backgroundView = events.isEmpty ? empty : nil
        dirty = false
    }
    public func updateSearchResults(for searchController: UISearchController) { filterChanged() }
    @objc private func filterChanged() { dirty = true; refresh() }
    @objc private func togglePause() {
        paused.toggle()
        pause.setTitle(WebSocketPresentation.text(paused ? "resumeView" : "pauseView"), for: .normal)
        refresh()
    }
    @objc private func clearHistory() { history.clear(); latest = history.snapshot(); dirty = true; refresh() }
    @objc private func toggleCapture() {
        guard let status = recorder.captureController.status() else { showBusy(); return }
        if status.mode == .detailed {
            if !recorder.captureController.stopDetailed() { showBusy() }
        } else {
            do { try recorder.captureController.startDetailed() } catch { showBusy() }
        }
        refresh()
    }
    private func showBusy() {
        let alert = UIAlertController(title: WebSocketPresentation.text("busy"), message: nil, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: WebSocketPresentation.text("ok"), style: .default))
        present(alert, animated: true)
    }
    public func scrollViewWillBeginDragging(_ scrollView: UIScrollView) { if !paused { togglePause() } }
    public func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { events.count }
    public func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let event = events[indexPath.row]
        let cell = tableView.dequeueReusableCell(withIdentifier: "websocket")
            ?? UITableViewCell(style: .subtitle, reuseIdentifier: "websocket")
        cell.textLabel?.text = connectionID == nil ? event.url : WebSocketPresentation.summary(event)
        cell.textLabel?.font = .preferredFont(forTextStyle: .body)
        cell.textLabel?.adjustsFontForContentSizeCategory = true; cell.textLabel?.numberOfLines = 2
        cell.textLabel?.textColor = .label
        cell.detailTextLabel?.text = connectionID == nil
            ? String(event.connectionID.prefix(8)) + " · " + String(format: WebSocketPresentation.text("ws_group"), counts[event.connectionID] ?? 0, WebSocketPresentation.kind(event))
            : DateFormatter.localizedString(from: Date(timeIntervalSince1970: Double(event.timestampMillis) / 1_000), dateStyle: .none, timeStyle: .medium) + " · #\(event.sequence)"
        cell.detailTextLabel?.font = .preferredFont(forTextStyle: .caption1)
        cell.detailTextLabel?.adjustsFontForContentSizeCategory = true; cell.detailTextLabel?.numberOfLines = 0
        cell.detailTextLabel?.textColor = WebSocketPresentation.hasIssue(event) ? .systemRed : .secondaryLabel
        cell.accessoryType = .disclosureIndicator
        return cell
    }
    public func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        let event = events[indexPath.row]
        let detail: UIViewController = connectionID == nil
            ? WebSocketInspectorViewController(recorder: recorder, history: history, connectionID: event.connectionID)
            : WebSocketDetailViewController(event: event, history: history)
        if let navigationController { navigationController.pushViewController(detail, animated: true) }
        else { present(UINavigationController(rootViewController: detail), animated: true) }
    }
}

@MainActor
private final class WebSocketDetailViewController: UIViewController {
    private var event: WebSocketEvent
    private let history: any WebSocketHistory
    private let text = UITextView()
    private let section = UISegmentedControl(items: [WebSocketPresentation.text("http_overview"),
        WebSocketPresentation.text("ws_payload")])
    private var notice = ""

    init(event: WebSocketEvent, history: any WebSocketHistory) {
        self.event = event; self.history = history
        super.init(nibName: nil, bundle: nil)
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func viewDidLoad() {
        super.viewDidLoad()
        title = WebSocketPresentation.kind(event)
        view.backgroundColor = .systemBackground
        section.selectedSegmentIndex = 0
        section.addTarget(self, action: #selector(render), for: .valueChanged)
        text.isEditable = false; text.adjustsFontForContentSizeCategory = true
        text.backgroundColor = .systemBackground; text.textColor = .label
        text.textContainerInset = UIEdgeInsets(top: 16, left: 12, bottom: 16, right: 12)
        let stack = UIStackView(arrangedSubviews: [section, text])
        stack.axis = .vertical; stack.spacing = 8; stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            stack.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 8),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -8)
        ])
        navigationItem.rightBarButtonItems = [
            UIBarButtonItem(title: WebSocketPresentation.text("http_copy"), style: .plain, target: self, action: #selector(copyText)),
            UIBarButtonItem(barButtonSystemItem: .refresh, target: self, action: #selector(refresh))
        ]
        navigationItem.rightBarButtonItems?.last?.accessibilityLabel = WebSocketPresentation.text("http_refresh")
        refresh()
    }
    @objc private func refresh() {
        if let snapshot = history.snapshot() {
            if let current = snapshot.events.first(where: { $0.id == event.id }) { event = current; notice = "" }
            else { notice = WebSocketPresentation.text("ws_missing") }
        } else { notice = WebSocketPresentation.text("http_stale") }
        render()
    }
    @objc private func render() {
        let body = section.selectedSegmentIndex != 0
        text.font = body ? UIFontMetrics(forTextStyle: .body).scaledFont(for: .monospacedSystemFont(ofSize: 15, weight: .regular))
            : .preferredFont(forTextStyle: .body)
        let content = body ? WebSocketPresentation.payload(event.payload, history: history) : WebSocketPresentation.overview(event)
        text.text = (notice.isEmpty ? "" : notice + "\n\n") + content
        text.setContentOffset(.zero, animated: false)
    }
    @objc private func copyText() { UIPasteboard.general.string = text.text }
}
#endif

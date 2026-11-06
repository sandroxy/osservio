#if canImport(UIKit)
import OsservioCore
import UIKit

/// HTTP metadata is refreshed only while visible. Body lookup is explicit and lazy.
@MainActor
public final class HTTPInspectorViewController: UIViewController, UITableViewDataSource,
    UITableViewDelegate, UISearchResultsUpdating {
    private let recorder: HTTPRecorder
    private let history: any HTTPHistory
    private let table = UITableView(frame: .zero, style: .plain)
    private let summary = UILabel()
    private let filter = UISegmentedControl(items: [HTTPPresentation.text("http_all"),
        HTTPPresentation.text("http_issues"), HTTPPresentation.text("http_active")])
    private let search = UISearchController(searchResultsController: nil)
    private let capture = UIButton(type: .system)
    private let pause = UIButton(type: .system)
    private lazy var refreshLoop = InspectorRefreshLoop { [weak self] in self?.refresh() }
    private var latest: HTTPSnapshot?
    private var events: [HTTPEvent] = []
    private var paused = false
    private var dirty = true

    public init(recorder: HTTPRecorder, history: any HTTPHistory) {
        self.recorder = recorder; self.history = history
        super.init(nibName: nil, bundle: nil)
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }

    public override func viewDidLoad() {
        super.viewDidLoad()
        title = HTTPPresentation.text("network")
        view.backgroundColor = .systemBackground
        navigationItem.searchController = search
        navigationItem.hidesSearchBarWhenScrolling = false
        search.obscuresBackgroundDuringPresentation = false
        search.searchBar.placeholder = HTTPPresentation.text("http_search")
        search.searchResultsUpdater = self
        definesPresentationContext = true
        summary.font = .preferredFont(forTextStyle: .caption1)
        summary.adjustsFontForContentSizeCategory = true
        summary.textColor = .secondaryLabel
        summary.numberOfLines = 0
        capture.addTarget(self, action: #selector(toggleCapture), for: .touchUpInside)
        pause.setTitle(HTTPPresentation.text("pauseView"), for: .normal)
        pause.addTarget(self, action: #selector(togglePause), for: .touchUpInside)
        let clear = UIButton(type: .system)
        clear.setTitle(HTTPPresentation.text("http_clear"), for: .normal)
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
        refreshLoop.show(in: view)
    }
    public override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        refreshLoop.show(in: view)
    }
    public override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        refreshLoop.hide()
    }

    private func refresh() {
        recorder.maintenance()
        if !paused, let snapshot = history.snapshot() {
            if latest?.revision != snapshot.revision { dirty = true }
            latest = snapshot
        }
        if dirty { render() }
        if let status = recorder.captureController.status() {
            capture.setTitle(HTTPPresentation.text(status.mode == .detailed ? "http_stop" : "http_start"), for: .normal)
            summary.text = String(format: HTTPPresentation.text("http_summary"),
                                  HTTPPresentation.text("http_" + status.mode.rawValue), latest?.events.count ?? 0,
                                  latest?.evictedEvents ?? 0, latest?.evictedBodies ?? 0)
        }
    }
    private func render() {
        let query = search.searchBar.text ?? ""
        events = (latest?.events ?? []).reversed().filter { event in
            let matchesFilter = filter.selectedSegmentIndex == 0
                || (filter.selectedSegmentIndex == 1 && HTTPPresentation.hasIssue(event))
                || (filter.selectedSegmentIndex == 2 && HTTPPresentation.isActive(event))
            return matchesFilter && (query.isEmpty || event.url.localizedCaseInsensitiveContains(query)
                || (event.responseURL?.localizedCaseInsensitiveContains(query) ?? false)
                || event.method.localizedCaseInsensitiveContains(query) || (event.statusCode.map { String($0).contains(query) } ?? false))
        }
        table.reloadData()
        let empty = UILabel()
        empty.text = HTTPPresentation.text(latest?.events.isEmpty == false ? "http_no_matches" : "http_empty")
        empty.textColor = .secondaryLabel; empty.numberOfLines = 0; empty.textAlignment = .center
        empty.font = .preferredFont(forTextStyle: .body); empty.adjustsFontForContentSizeCategory = true
        table.backgroundView = events.isEmpty ? empty : nil
        dirty = false
    }
    public func updateSearchResults(for searchController: UISearchController) { filterChanged() }
    @objc private func filterChanged() { dirty = true; refresh() }
    @objc private func togglePause() {
        paused.toggle()
        pause.setTitle(HTTPPresentation.text(paused ? "resumeView" : "pauseView"), for: .normal)
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
        let alert = UIAlertController(title: HTTPPresentation.text("busy"), message: nil, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: HTTPPresentation.text("ok"), style: .default))
        present(alert, animated: true)
    }
    public func scrollViewWillBeginDragging(_ scrollView: UIScrollView) { if !paused { togglePause() } }
    public func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { events.count }
    public func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let event = events[indexPath.row]
        let cell = tableView.dequeueReusableCell(withIdentifier: "http")
            ?? UITableViewCell(style: .subtitle, reuseIdentifier: "http")
        cell.textLabel?.text = event.method + " " + event.url
        cell.textLabel?.font = .preferredFont(forTextStyle: .body)
        cell.textLabel?.adjustsFontForContentSizeCategory = true; cell.textLabel?.numberOfLines = 2
        cell.textLabel?.textColor = .label
        cell.detailTextLabel?.text = HTTPPresentation.summary(event)
        cell.detailTextLabel?.font = .preferredFont(forTextStyle: .caption1)
        cell.detailTextLabel?.adjustsFontForContentSizeCategory = true; cell.detailTextLabel?.numberOfLines = 0
        cell.detailTextLabel?.textColor = HTTPPresentation.hasIssue(event) ? .systemRed : .secondaryLabel
        cell.accessoryType = .disclosureIndicator
        return cell
    }
    public func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        let detail = HTTPDetailViewController(event: events[indexPath.row], history: history, recorder: recorder)
        if let navigationController { navigationController.pushViewController(detail, animated: true) }
        else { present(UINavigationController(rootViewController: detail), animated: true) }
    }
}

@MainActor
final class HTTPDetailViewController: UIViewController {
    private var event: HTTPEvent
    private let history: (any HTTPHistory)?
    private let recorder: HTTPRecorder?
    private let savedPayloads: [String: IncidentPayload]?
    private let text = UITextView()
    private let section = UISegmentedControl(items: [HTTPPresentation.text("http_overview"),
        HTTPPresentation.text("http_request_body"), HTTPPresentation.text("http_response_body")])
    private var notice = ""

    init(event: HTTPEvent, history: any HTTPHistory, recorder: HTTPRecorder) {
        self.event = event; self.history = history; self.recorder = recorder; savedPayloads = nil
        super.init(nibName: nil, bundle: nil)
    }
    init(event: HTTPEvent, savedPayloads: [String: IncidentPayload]) {
        self.event = event; self.savedPayloads = savedPayloads; history = nil; recorder = nil
        notice = HTTPPresentation.text("incident_frozen_detail")
        super.init(nibName: nil, bundle: nil)
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func viewDidLoad() {
        super.viewDidLoad()
        title = event.method
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
            UIBarButtonItem(title: HTTPPresentation.text("http_copy"), style: .plain, target: self, action: #selector(copyText))
        ]
        if history != nil {
            let refresh = UIBarButtonItem(barButtonSystemItem: .refresh, target: self, action: #selector(refresh))
            refresh.accessibilityLabel = HTTPPresentation.text("http_refresh"); navigationItem.rightBarButtonItems?.append(refresh)
        }
        refresh()
    }
    @objc private func refresh() {
        guard let history else { render(); return }
        recorder?.maintenance()
        if let snapshot = history.snapshot() {
            if let current = snapshot.events.first(where: { $0.id == event.id }) { event = current; notice = "" }
            else { notice = HTTPPresentation.text("http_missing") }
        } else { notice = HTTPPresentation.text("http_stale") }
        render()
    }
    @objc private func render() {
        let body = section.selectedSegmentIndex != 0
        text.font = body ? UIFontMetrics(forTextStyle: .body).scaledFont(for: .monospacedSystemFont(ofSize: 15, weight: .regular))
            : .preferredFont(forTextStyle: .body)
        let content: String
        let reference = section.selectedSegmentIndex == 1 ? event.requestBody : event.responseBody
        if !body { content = HTTPPresentation.overview(event) }
        else if let savedPayloads { content = HTTPPresentation.body(reference, savedPayloads: savedPayloads) }
        else if let history { content = HTTPPresentation.body(reference, history: history) }
        else { content = HTTPPresentation.text("incident_payload_missing") }
        text.text = (notice.isEmpty ? "" : notice + "\n\n") + content
        text.setContentOffset(.zero, animated: false)
    }
    @objc private func copyText() { UIPasteboard.general.string = text.text }
}
#endif

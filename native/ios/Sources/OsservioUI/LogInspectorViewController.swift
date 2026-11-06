#if canImport(UIKit)
import OsservioCore
import UIKit

private func copyText(_ key: String) -> String {
    NSLocalizedString(key, bundle: .module, comment: "")
}

/// Presents a live log history. Visibility and rendering never change capture state.
@MainActor
public final class LogInspectorViewController: UIViewController, UITableViewDataSource,
    UITableViewDelegate, UISearchResultsUpdating {
    private let recorder: LogRecorder
    private let history: any LogHistory
    private let table = UITableView(frame: .zero, style: .plain)
    private let summary = UILabel()
    private let levels = UISegmentedControl(items: [copyText("all"), "DEBUG", "INFO", "WARN", "ERROR"])
    private let search = UISearchController(searchResultsController: nil)
    private let pauseButton = UIButton(type: .system)
    private let captureButton = UIButton(type: .system)
    private lazy var refreshLoop = InspectorRefreshLoop { [weak self] in self?.refresh() }
    private var latest: LogSnapshot?
    private var visibleEvents: [LogEvent] = []
    private var paused = false
    private var needsRender = true
    private let timeFormatter: DateFormatter = {
        let value = DateFormatter()
        value.dateFormat = "HH:mm:ss.SSS"
        return value
    }()

    public init(recorder: LogRecorder, history: any LogHistory) {
        self.recorder = recorder
        self.history = history
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }

    public override func viewDidLoad() {
        super.viewDidLoad()
        title = copyText("logs")
        view.backgroundColor = .systemBackground
        navigationItem.searchController = search
        navigationItem.hidesSearchBarWhenScrolling = false
        search.obscuresBackgroundDuringPresentation = false
        search.searchBar.placeholder = copyText("search")
        search.searchResultsUpdater = self
        definesPresentationContext = true

        summary.font = .preferredFont(forTextStyle: .caption1)
        summary.adjustsFontForContentSizeCategory = true
        summary.textColor = .secondaryLabel
        summary.numberOfLines = 0
        levels.selectedSegmentIndex = 0
        levels.addTarget(self, action: #selector(filterChanged), for: .valueChanged)
        pauseButton.setTitle(copyText("pauseView"), for: .normal)
        pauseButton.addTarget(self, action: #selector(togglePause), for: .touchUpInside)
        captureButton.addTarget(self, action: #selector(toggleCapture), for: .touchUpInside)
        let clear = UIButton(type: .system)
        clear.setTitle(copyText("clear"), for: .normal)
        clear.addTarget(self, action: #selector(clearHistory), for: .touchUpInside)
        let controls = UIStackView(arrangedSubviews: [captureButton, pauseButton, clear])
        controls.distribution = .equalSpacing
        controls.spacing = 12
        let header = UIStackView(arrangedSubviews: [summary, controls, levels])
        header.axis = .vertical
        header.spacing = 8
        header.isLayoutMarginsRelativeArrangement = true
        header.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 12, leading: 16, bottom: 12, trailing: 16)
        let stack = UIStackView(arrangedSubviews: [header, table])
        stack.axis = .vertical
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            stack.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor)
        ])
        table.dataSource = self
        table.delegate = self
        table.rowHeight = UITableView.automaticDimension
        table.estimatedRowHeight = 80
        table.keyboardDismissMode = .onDrag
        table.tableFooterView = UIView()
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
        if !paused, let snapshot = history.snapshot() {
            if latest?.revision != snapshot.revision { needsRender = true }
            latest = snapshot
        }
        if needsRender { render() }
        if let status = recorder.status() {
            captureButton.setTitle(copyText(status.isRecording ? "stopCapture" : "startCapture"), for: .normal)
            let state = copyText(status.isRecording ? "capturing" : "stopped")
            summary.text = String(format: copyText("summary"), state, latest?.events.count ?? 0,
                                  latest?.evictedCount ?? 0, status.rateLimitedCount)
        }
    }

    private func render() {
        let query = search.searchBar.text ?? ""
        let index = levels.selectedSegmentIndex
        let level = index > 0 ? LogLevel.allCases[index - 1] : nil
        visibleEvents = (latest?.events ?? []).reversed().filter {
            (level == nil || $0.level == level) && (query.isEmpty
                || $0.message.localizedCaseInsensitiveContains(query)
                || $0.category.localizedCaseInsensitiveContains(query))
        }
        table.reloadData()
        let empty = UILabel()
        empty.text = copyText(latest?.events.isEmpty == false ? "noMatches" : "empty")
        empty.textColor = .secondaryLabel
        empty.font = .preferredFont(forTextStyle: .body)
        empty.textAlignment = .center
        empty.numberOfLines = 0
        table.backgroundView = visibleEvents.isEmpty ? empty : nil
        needsRender = false
    }

    public func updateSearchResults(for searchController: UISearchController) { filterChanged() }
    @objc private func filterChanged() { needsRender = true; refresh() }
    @objc private func clearHistory() {
        history.clear()
        latest = history.snapshot()
        needsRender = true
        refresh()
    }
    @objc private func togglePause() {
        paused.toggle()
        pauseButton.setTitle(copyText(paused ? "resumeView" : "pauseView"), for: .normal)
        refresh()
    }
    @objc private func toggleCapture() {
        guard let state = recorder.status() else { showBusy(); return }
        if state.isRecording {
            if !recorder.stop() { showBusy() }
        } else {
            do { try recorder.start() } catch { showBusy() }
        }
        refresh()
    }
    private func showBusy() {
        let alert = UIAlertController(title: copyText("busy"), message: nil, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: copyText("ok"), style: .default))
        present(alert, animated: true)
    }

    public func scrollViewWillBeginDragging(_ scrollView: UIScrollView) {
        if !paused { togglePause() }
    }

    public func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { visibleEvents.count }
    public func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let event = visibleEvents[indexPath.row]
        let cell = tableView.dequeueReusableCell(withIdentifier: "log")
            ?? UITableViewCell(style: .subtitle, reuseIdentifier: "log")
        cell.textLabel?.text = event.message
        cell.textLabel?.font = .preferredFont(forTextStyle: .body)
        cell.textLabel?.adjustsFontForContentSizeCategory = true
        cell.textLabel?.numberOfLines = 2
        cell.textLabel?.textColor = event.level == .error ? .systemRed : .label
        let time = timeFormatter.string(from: Date(timeIntervalSince1970: Double(event.timestampMillis) / 1_000))
        let marker = event.isTruncated ? " · " + copyText("truncated") : ""
        cell.detailTextLabel?.text = "\(time) · \(event.level.rawValue.uppercased()) · \(event.category)\(marker)"
        cell.detailTextLabel?.font = .preferredFont(forTextStyle: .caption1)
        cell.detailTextLabel?.adjustsFontForContentSizeCategory = true
        cell.detailTextLabel?.numberOfLines = 0
        cell.detailTextLabel?.textColor = .secondaryLabel
        cell.accessoryType = .disclosureIndicator
        return cell
    }

    public func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        let detail = LogDetailViewController(event: visibleEvents[indexPath.row])
        if let navigationController { navigationController.pushViewController(detail, animated: true) }
        else { present(UINavigationController(rootViewController: detail), animated: true) }
    }
}

@MainActor
final class LogDetailViewController: UIViewController {
    private let event: LogEvent
    init(event: LogEvent) { self.event = event; super.init(nibName: nil, bundle: nil) }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = event.category
        let text = UITextView()
        text.isEditable = false
        text.font = .preferredFont(forTextStyle: .body)
        text.adjustsFontForContentSizeCategory = true
        text.backgroundColor = .systemBackground
        text.textColor = .label
        text.textContainerInset = UIEdgeInsets(top: 16, left: 12, bottom: 16, right: 12)
        text.text = RuntimePresentation.date(event.timestampMillis) + "\n"
            + HTTPPresentation.text("http_session") + ": " + event.sessionID + "\n"
            + "\(event.level.rawValue.uppercased()) · #\(event.sequence)\n"
            + (event.isPrivate ? RuntimePresentation.text("crumb_private") + "\n" : "")
            + (event.isTruncated ? copyText("truncated") + "\n" : "") + "\n" + event.message
        view = text
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: copyText("copy"), style: .plain,
                                                            target: self, action: #selector(copyMessage))
    }
    @objc private func copyMessage() { UIPasteboard.general.string = event.message }
}
#endif

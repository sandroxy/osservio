#if canImport(UIKit)
import OsservioCore
import UIKit

@MainActor
final class BreadcrumbInspectorViewController: UIViewController, UITableViewDataSource, UITableViewDelegate, UITextFieldDelegate {
    private let recorder: BreadcrumbRecorder
    private let history: any BreadcrumbHistory
    private let table = InspectorTableView(frame: .zero, style: .plain)
    private let search = UITextField()
    private let summary = UILabel()
    private let baseline = UIButton(type: .system)
    private let pause = UIButton(type: .system)
    private var paused = false
    private lazy var refreshLoop = InspectorRefreshLoop { [weak self] in self?.refresh() }
    private var latest: BreadcrumbSnapshot?
    private var events: [BreadcrumbEvent] = []
    private var dirty = true
    init(recorder: BreadcrumbRecorder, history: any BreadcrumbHistory) {
        self.recorder = recorder; self.history = history; super.init(nibName: nil, bundle: nil)
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        summary.font = .preferredFont(forTextStyle: .caption1)
        summary.adjustsFontForContentSizeCategory = true; summary.textColor = .secondaryLabel; summary.numberOfLines = 0
        let explanation = UILabel()
        explanation.font = .preferredFont(forTextStyle: .caption1); explanation.adjustsFontForContentSizeCategory = true
        explanation.textColor = .secondaryLabel; explanation.numberOfLines = 0
        explanation.text = RuntimePresentation.text("crumb_baseline_hint")
        baseline.addTarget(self, action: #selector(toggleBaseline), for: .touchUpInside)
        pause.setTitle(RuntimePresentation.text("pauseView"), for: .normal)
        pause.addTarget(self, action: #selector(togglePause), for: .touchUpInside)
        let clear = UIButton(type: .system)
        clear.setTitle(RuntimePresentation.text("crumb_clear"), for: .normal)
        clear.addTarget(self, action: #selector(clearHistory), for: .touchUpInside)
        let controls = InspectorControls(buttons: [baseline, pause, clear])
        search.placeholder = RuntimePresentation.text("crumb_search")
        search.borderStyle = .roundedRect; search.clearButtonMode = .whileEditing; search.returnKeyType = .done
        search.font = .preferredFont(forTextStyle: .body); search.adjustsFontForContentSizeCategory = true; search.delegate = self
        search.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        search.addTarget(self, action: #selector(searchChanged), for: .editingChanged)
        let header = UIStackView(arrangedSubviews: [summary, controls, explanation, search])
        header.axis = .vertical; header.spacing = 8
        header.isLayoutMarginsRelativeArrangement = true
        header.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 8, leading: 8, bottom: 8, trailing: 8)
        table.tableHeaderView = header
        table.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(table)
        NSLayoutConstraint.activate([
            table.topAnchor.constraint(equalTo: view.topAnchor), table.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            table.leadingAnchor.constraint(equalTo: view.leadingAnchor), table.trailingAnchor.constraint(equalTo: view.trailingAnchor)
        ])
        table.dataSource = self; table.delegate = self; table.keyboardDismissMode = .onDrag
        table.rowHeight = UITableView.automaticDimension; table.estimatedRowHeight = 80; table.tableFooterView = UIView()
    }
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        refreshLoop.show(in: view)
    }
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        refreshLoop.show(in: view)
    }
    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        refreshLoop.hide()
    }

    private func refresh() {
        if !paused, let snapshot = history.snapshot() {
            if latest?.revision != snapshot.revision { dirty = true }; latest = snapshot
        }
        if dirty {
            let query = search.text ?? ""
            events = (latest?.events ?? []).reversed().filter { query.isEmpty || $0.label.localizedCaseInsensitiveContains(query)
                || RuntimePresentation.kind($0).localizedCaseInsensitiveContains(query) || ($0.message?.localizedCaseInsensitiveContains(query) ?? false) }
            table.reloadData(); dirty = false
            let empty = UILabel()
            empty.text = RuntimePresentation.text(latest == nil ? "busy" : latest?.events.isEmpty == false ? "ws_no_matches" : "crumb_empty")
            empty.numberOfLines = 0; empty.textAlignment = .center; empty.textColor = .secondaryLabel
            empty.font = .preferredFont(forTextStyle: .body); empty.adjustsFontForContentSizeCategory = true
            table.setEmptyState(events.isEmpty ? empty : nil)
        }
        if let status = recorder.captureController.status(), let recording = recorder.status() {
            baseline.setTitle(RuntimePresentation.text(status.baselineEnabled ? "crumb_disable_baseline" : "crumb_enable_baseline"), for: .normal)
            summary.text = String(format: RuntimePresentation.text("crumb_summary"), RuntimePresentation.text("http_" + status.mode.rawValue),
                latest?.events.count ?? 0, latest?.evictedCount ?? 0, recording.rateLimitedCount)
        }
        table.setNeedsLayout()
    }
    @objc private func toggleBaseline() {
        if let status = recorder.captureController.status(), recorder.captureController.setBaselineEnabled(!status.baselineEnabled) { refresh() }
        else {
            let alert = UIAlertController(title: RuntimePresentation.text("busy"), message: nil, preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: RuntimePresentation.text("ok"), style: .default)); present(alert, animated: true)
        }
    }
    @objc private func togglePause() { paused.toggle(); pause.setTitle(RuntimePresentation.text(paused ? "resumeView" : "pauseView"), for: .normal); refresh() }
    @objc private func clearHistory() { history.clear(); latest = history.snapshot(); dirty = true; refresh() }
    @objc private func searchChanged() { dirty = true; refresh() }
    func textFieldShouldReturn(_ textField: UITextField) -> Bool { textField.resignFirstResponder(); return true }
    func scrollViewWillBeginDragging(_ scrollView: UIScrollView) { if !paused { togglePause() } }
    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { events.count }
    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let event = events[indexPath.row]
        let cell = tableView.dequeueReusableCell(withIdentifier: "crumb") ?? UITableViewCell(style: .subtitle, reuseIdentifier: "crumb")
        cell.textLabel?.text = RuntimePresentation.kind(event) + " · " + event.label
        cell.textLabel?.font = .preferredFont(forTextStyle: .body); cell.textLabel?.adjustsFontForContentSizeCategory = true; cell.textLabel?.numberOfLines = 2
        cell.detailTextLabel?.text = RuntimePresentation.date(event.timestampMillis) + " · #\(event.sequence)" + (event.message.map { "\n" + $0 } ?? "")
        cell.detailTextLabel?.font = .preferredFont(forTextStyle: .caption1); cell.detailTextLabel?.adjustsFontForContentSizeCategory = true; cell.detailTextLabel?.numberOfLines = 3
        cell.accessoryType = .disclosureIndicator
        return cell
    }
    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        navigationController?.pushViewController(BreadcrumbDetailViewController(event: events[indexPath.row]), animated: true)
    }
}

@MainActor
final class BreadcrumbDetailViewController: UIViewController {
    private let event: BreadcrumbEvent
    init(event: BreadcrumbEvent) { self.event = event; super.init(nibName: nil, bundle: nil) }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func viewDidLoad() {
        super.viewDidLoad(); title = RuntimePresentation.kind(event)
        let text = UITextView(); text.isEditable = false; text.font = .preferredFont(forTextStyle: .body)
        text.adjustsFontForContentSizeCategory = true; text.backgroundColor = .systemBackground; text.textColor = .label
        text.textContainerInset = UIEdgeInsets(top: 16, left: 12, bottom: 16, right: 12)
        text.text = RuntimePresentation.text("crumb_detail_hint") + "\n\n" + RuntimePresentation.breadcrumb(event)
        view = text
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: RuntimePresentation.text("crumb_copy"), style: .plain, target: self, action: #selector(copyText))
    }
    @objc private func copyText() { UIPasteboard.general.string = RuntimePresentation.breadcrumb(event) }
}
#endif

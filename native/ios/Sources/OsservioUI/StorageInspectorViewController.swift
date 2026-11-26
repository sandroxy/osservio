#if canImport(UIKit)
import OsservioCore
import UIKit

@MainActor
final class StorageInspectorViewController: UITableViewController {
    private let storage: KeyValueInspection
    private let domain: KeyValueInspection.Domain?
    private let search = UISearchTextField()
    private let notice = InspectorTheme.label(.caption1, secondary: true)
    private let header = UIStackView()
    private var keys: [String] = [], shown: [String] = []
    private var index: KeyValueInspection.Keys?
    private var request: KeyValueInspection.Request?
    private var observation: KeyValueInspection.Request?
    private var generation = 0
    private var searchWork: DispatchWorkItem?
    private lazy var refreshButton = inspectorRefreshItem(target: self, action: #selector(readKeys))
    init(storage: KeyValueInspection, domain: KeyValueInspection.Domain? = nil) {
        self.storage = storage; self.domain = domain; super.init(style: .insetGrouped)
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    deinit { request?.cancel(); observation?.cancel(); searchWork?.cancel() }
    override func viewDidLoad() {
        super.viewDidLoad(); InspectorTheme.apply(self)
        tableView.rowHeight = UITableView.automaticDimension; tableView.estimatedRowHeight = 56
        tableView.keyboardDismissMode = .onDrag
        notice.numberOfLines = 0
        header.axis = .vertical; header.spacing = 12; header.isLayoutMarginsRelativeArrangement = true
        header.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 16, leading: 20, bottom: 12, trailing: 20)
        header.addArrangedSubview(notice)
        if domain != nil {
            search.placeholder = InspectorLocalization.text("storage_search"); search.autocorrectionType = .no; search.autocapitalizationType = .none
            search.font = .preferredFont(forTextStyle: .body); search.adjustsFontForContentSizeCategory = true
            search.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
            search.addTarget(self, action: #selector(searchChanged), for: .editingChanged); header.addArrangedSubview(search)
            inspectorActions = [refreshButton]
        }
        tableView.tableHeaderView = header; render()
    }
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated); InspectorTheme.apply(self); render()
        observation = storage.observeClose { [weak self] in Task { @MainActor [weak self] in self?.render() } }
        if domain != nil, index == nil { readKeys() }
    }
    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated); generation += 1; request?.cancel(); request = nil; observation?.cancel(); observation = nil; searchWork?.cancel()
    }
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let size = header.systemLayoutSizeFitting(CGSize(width: tableView.bounds.width, height: 0),
            withHorizontalFittingPriority: .required, verticalFittingPriority: .fittingSizeLevel)
        if header.frame.size != size { header.frame.size = size; tableView.tableHeaderView = header }
    }
    @objc private func searchChanged() {
        searchWork?.cancel(); let work = DispatchWorkItem { [weak self] in self?.filter() }; searchWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.12, execute: work)
    }
    private func filter() {
        let query = search.text ?? ""; shown = keys.filter { query.isEmpty || $0.range(of: query, options: [.caseInsensitive, .literal]) != nil }
        render()
    }
    @objc private func readKeys() {
        guard let domain, request == nil, !storage.isClosed else { render(); return }
        generation += 1; let ticket = generation; refreshButton.isEnabled = false; notice.text = InspectorLocalization.text("runtime_reading")
        request = storage.keys(domain: domain.id) { [weak self] result in
            Task { @MainActor [weak self] in
                guard let self, self.generation == ticket else { return }; self.request = nil
                guard !self.storage.isClosed else { self.render(); return }
                switch result {
                case .success(let index): self.index = index; self.keys = index.keys; self.filter()
                case .failure(let error): self.index = nil; self.keys = []; self.filter(); self.notice.text = InspectorLocalization.text(error == .busy ? "busy" : "runtime_read_failed")
                }
                self.view.setNeedsLayout()
            }
        }
    }
    private func render() {
        title = domain?.name ?? InspectorLocalization.text("storage_title")
        search.placeholder = InspectorLocalization.text("storage_search")
        refreshButton.isEnabled = !storage.isClosed && request == nil; refreshButton.tintColor = InspectorTheme.accent
        refreshButton.accessibilityLabel = InspectorLocalization.text("http_refresh")
        if storage.isClosed { keys = []; shown = []; index = nil; notice.text = InspectorLocalization.text("storage_closed") }
        else if let domain {
            var lines = [domain.backend + " · " + domain.scope]
            if let index {
                lines += [InspectorLocalization.text("storage_read_at") + " · " + InspectorLocalization.date(index.readAtMillis), "\(shown.count) / \(keys.count)"]
                if index.limited { lines.append(InspectorLocalization.text("storage_limited")) }
                if keys.isEmpty { lines.append(InspectorLocalization.text("storage_empty")) }
            }
            notice.text = lines.joined(separator: "\n")
        } else { notice.text = InspectorLocalization.text(storage.domains.isEmpty ? "storage_no_domains" : "storage_hint") }
        tableView.reloadData(); view.setNeedsLayout()
    }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        storage.isClosed ? 0 : domain == nil ? storage.domains.count : shown.count
    }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "storage") ?? UITableViewCell(style: .subtitle, reuseIdentifier: "storage")
        PersistencePresentation.style(cell); cell.backgroundColor = InspectorTheme.surface
        cell.textLabel?.numberOfLines = 3; cell.accessoryType = .disclosureIndicator
        if domain == nil {
            let item = storage.domains[indexPath.row]; cell.textLabel?.text = item.name; cell.detailTextLabel?.text = item.backend + " · " + item.scope
        } else { let key = shown[indexPath.row]; cell.textLabel?.text = key.isEmpty ? InspectorLocalization.text("storage_empty_key") : key; cell.detailTextLabel?.text = nil }
        return cell
    }
    override func tableView(_ tableView: UITableView, contextMenuConfigurationForRowAt indexPath: IndexPath, point: CGPoint) -> UIContextMenuConfiguration? {
        guard domain != nil, !storage.isClosed else { return nil }; let key = shown[indexPath.row], storage = storage
        return UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { _ in
            UIMenu(children: [UIAction(title: InspectorLocalization.text("storage_copy_key"), image: UIImage(systemName: "doc.on.doc")) { _ in
                if !storage.isClosed { UIPasteboard.general.string = key }
            }])
        }
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true); view.endEditing(true)
        guard !storage.isClosed else { render(); return }
        if let domain { inspectorPush(StorageValueViewController(storage: storage, domain: domain, key: shown[indexPath.row])) }
        else { inspectorPush(StorageInspectorViewController(storage: storage, domain: storage.domains[indexPath.row])) }
    }
}
#endif

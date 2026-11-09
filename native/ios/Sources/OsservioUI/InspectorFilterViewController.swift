#if canImport(UIKit)
import UIKit

/// Edits a private draft. Dismissal never changes the caller's active query.
@MainActor
final class InspectorFilterViewController: UITableViewController, UITextFieldDelegate {
    private var draft: InspectorQuery
    private var groups: [InspectorFilterGroup]
    private let apply: (InspectorQuery) -> Void
    private let search = UISearchTextField()
    private let timeLabel: String
    private var visible: [[(value: String?, label: String)]] = []
    private let times = [0, 5, 30, 60, 1440]

    init(query: InspectorQuery, groups: [InspectorFilterGroup], timeLabel: String = "ui_time", apply: @escaping (InspectorQuery) -> Void) {
        draft = query; self.groups = groups; self.apply = apply; self.timeLabel = timeLabel
        for index in self.groups.indices {
            let field = self.groups[index].field, existing = Set(self.groups[index].options.map(\.value))
            for value in (query.values[field] ?? []).subtracting(existing).sorted() {
                self.groups[index].options.append((value, InspectorFilterOptions.label(field, value)))
            }
        }
        super.init(style: .insetGrouped)
        // UITableView can query its data source as soon as a header is assigned.
        visible = self.groups.map { group in
            [(nil, InspectorLocalization.text("ui_all"))] + group.options.map { (Optional($0.value), $0.label) }
        }
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func loadView() { super.loadView(); tableView = InspectorTableView(frame: .zero, style: .insetGrouped) }
    override func viewDidLoad() {
        super.viewDidLoad(); InspectorTheme.apply(self)
        title = InspectorLocalization.text("ui_filters")
        navigationItem.leftBarButtonItem = inspectorDismissItem(target: self, action: #selector(cancel))
        inspectorPrimaryAction = UIBarButtonItem(title: InspectorLocalization.text("ui_apply"), style: .done, target: self, action: #selector(save))
        search.placeholder = InspectorLocalization.text("ui_filter_options"); search.delegate = self
        search.font = .preferredFont(forTextStyle: .body); search.adjustsFontForContentSizeCategory = true
        search.autocorrectionType = .no; search.autocapitalizationType = .none; search.returnKeyType = .done
        search.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        search.addTarget(self, action: #selector(filter), for: .editingChanged)
        let hint = InspectorTheme.label(.caption1, secondary: true); hint.text = InspectorLocalization.text("ui_filter_hint")
        let reset = UIButton(type: .system); reset.setTitle(InspectorLocalization.text("ui_reset"), for: .normal)
        reset.addTarget(self, action: #selector(resetDraft), for: .touchUpInside)
        let header = UIStackView(arrangedSubviews: [hint, search, InspectorControls(buttons: [reset])])
        header.axis = .vertical; header.spacing = 12; header.isLayoutMarginsRelativeArrangement = true
        header.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 16, leading: 20, bottom: 8, trailing: 20)
        tableView.tableHeaderView = header; tableView.keyboardDismissMode = .onDrag
        tableView.rowHeight = UITableView.automaticDimension; tableView.estimatedRowHeight = 52
        filter()
    }
    @objc private func filter() {
        guard search.markedTextRange == nil else { return }
        let query = String((search.text ?? "").prefix(512)); if query != search.text { search.text = query }
        visible = groups.map { group in
            let options: [(String?, String)] = [(nil, InspectorLocalization.text("ui_all"))] + group.options.map { (Optional($0.value), $0.label) }
            return options.filter { query.isEmpty || $0.1.localizedCaseInsensitiveContains(query) }
        }
        tableView.reloadData()
    }
    @objc private func resetDraft() { draft.values.removeAll(); draft.recentMinutes = 0; tableView.reloadData() }
    @objc private func cancel() { dismiss(animated: true) }
    @objc private func save() { view.endEditing(true); apply(draft); dismiss(animated: true) }
    func textFieldShouldReturn(_ textField: UITextField) -> Bool { textField.resignFirstResponder(); return true }
    override func numberOfSections(in tableView: UITableView) -> Int { groups.count + 1 }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { section == groups.count ? times.count : visible[section].count }
    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        section == groups.count ? InspectorLocalization.text(timeLabel) : groups[section].title
    }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = UITableViewCell(style: .default, reuseIdentifier: nil)
        cell.backgroundColor = InspectorTheme.surface; cell.textLabel?.font = .preferredFont(forTextStyle: .body)
        cell.textLabel?.adjustsFontForContentSizeCategory = true; cell.textLabel?.numberOfLines = 0
        let selected: Bool
        if indexPath.section == groups.count {
            let time = times[indexPath.row]; cell.textLabel?.text = InspectorLocalization.text(time == 0 ? "ui_time_all" : "ui_time_\(time)")
            selected = draft.recentMinutes == time
        } else {
            let option = visible[indexPath.section][indexPath.row], values = draft.values[groups[indexPath.section].field] ?? []
            cell.textLabel?.text = option.label; selected = option.value.map { values.contains($0) } ?? values.isEmpty
        }
        cell.accessoryType = selected ? .checkmark : .none
        cell.accessibilityTraits = selected ? [.button, .selected] : .button
        return cell
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        if indexPath.section == groups.count { draft.recentMinutes = times[indexPath.row] }
        else {
            let field = groups[indexPath.section].field
            if let value = visible[indexPath.section][indexPath.row].value {
                var values = draft.values[field] ?? []
                if values.contains(value) { values.remove(value) } else { values.insert(value) }
                draft.values[field] = values
            } else { draft.values[field] = [] }
        }
        tableView.reloadData()
    }
}
#endif

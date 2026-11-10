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
    private let payload: Bool
    private let network: Bool
    private var conditions: InspectorFilterConditions?
    private let hasHTTP: Bool
    private enum Section { case group(Int), duration, time }
    private var sections: [Section] = []

    private var visible: [[(value: String?, label: String)]] = []
    private let times = [0, 5, 30, 60, 1440]

    init(query: InspectorQuery, groups: [InspectorFilterGroup], timeLabel: String = "ui_time", payload: Bool = false, apply: @escaping (InspectorQuery) -> Void) {
        draft = query; self.groups = groups; self.apply = apply; self.timeLabel = timeLabel
        hasHTTP = payload && groups.contains { $0.field == .method }
        self.payload = payload; network = groups.contains { $0.field == .transport || $0.field == .direction }
        for index in self.groups.indices {
            let field = self.groups[index].field, existing = Set(self.groups[index].options.map(\.value))
            for value in (query.values[field] ?? []).subtracting(existing).sorted() {
                self.groups[index].options.append((value, InspectorFilterOptions.label(field, value)))
            }
        }
        self.groups.removeAll { $0.options.isEmpty }
        super.init(style: .insetGrouped)
        // UITableView can query its data source as soon as a header is assigned.
        visible = self.groups.map { group in
            [(nil, InspectorLocalization.text("ui_all"))] + group.options.map { (Optional($0.value), $0.label) }
        }
        rebuildSections()
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func loadView() { super.loadView(); tableView = InspectorTableView(frame: .zero, style: .insetGrouped) }
    override func viewDidLoad() {
        super.viewDidLoad(); InspectorTheme.apply(self)
        title = InspectorLocalization.text("ui_filters")
        navigationItem.leftBarButtonItem = UIBarButtonItem(title: InspectorLocalization.text("ui_reset"), style: .plain, target: self, action: #selector(resetDraft))
        inspectorPrimaryAction = UIBarButtonItem(title: InspectorLocalization.text("ui_apply"), style: .done, target: self, action: #selector(save))
        search.placeholder = InspectorLocalization.text("ui_filter_options"); search.delegate = self
        search.font = .preferredFont(forTextStyle: .body); search.adjustsFontForContentSizeCategory = true
        search.autocorrectionType = .no; search.autocapitalizationType = .none; search.returnKeyType = .done
        search.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        search.addTarget(self, action: #selector(filter), for: .editingChanged)
        let hint = InspectorTheme.label(.caption1, secondary: true)
        hint.text = InspectorLocalization.text(network ? "ui_network_filter_hint" : "ui_filter_hint")
        let header = UIStackView(arrangedSubviews: [hint, search])
        if payload {
            let editor = InspectorFilterConditions(query: draft, delegate: self); conditions = editor
            editor.changed = { [weak self] in self?.editConditions() }
            editor.layoutChanged = { [weak self] in self?.tableView.setNeedsLayout() }
            header.addArrangedSubview(editor)
        }
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
            return options.filter { query.isEmpty || $0.1.localizedCaseInsensitiveContains(query) || group.title.localizedCaseInsensitiveContains(query) }
        }
        rebuildSections(); tableView.reloadData()
    }
    private func syncFields() { conditions?.sync(draft) }
    private func editConditions() {
        guard let fields = conditions else { return }
        draft.excludedText = fields.exclude.text ?? ""; draft.fieldPath = fields.path.text ?? ""; draft.fieldValue = fields.value.text ?? ""
        if let millis = UInt64(fields.duration.text ?? ""), millis > 0 { draft.select(.transport, value: "http"); draft.minimumDurationMillis = millis }
        else { draft.minimumDurationMillis = 0 }
        // Update checkmarks without rebuilding text fields or disturbing an active keyboard.
        for index in tableView.indexPathsForVisibleRows ?? [] {
            if case .group = sections[index.section], let cell = tableView.cellForRow(at: index) { check(cell, at: index) }
        }
    }
    @objc private func resetDraft() { view.endEditing(true); draft.resetFilters(); syncFields(); tableView.reloadData() }
    @objc private func save() {
        view.endEditing(true)
        if let fields = conditions {
            editConditions()
            guard (draft.fieldPath.isEmpty || InspectorJSONField.components(draft.fieldPath) != nil),
                  (fields.duration.text?.isEmpty != false || Int64(fields.duration.text ?? "").map { $0 >= 0 } == true) else {
                inspectorNotice(InspectorLocalization.text("ui_field_invalid")); return
            }
        }
        apply(draft); dismiss(animated: true)
    }
    private func rebuildSections() {
        sections = []
        let lastHTTP = groups.lastIndex { InspectorQuery.httpFields.contains($0.field) }
        for index in groups.indices {
            if !visible[index].isEmpty { sections.append(.group(index)) }
            if hasHTTP && index == lastHTTP && (search.text?.isEmpty != false) { sections.append(.duration) }
        }
        if hasHTTP && lastHTTP == nil && (search.text?.isEmpty != false) { sections.append(.duration) }
        sections.append(.time)
    }
    func textFieldShouldReturn(_ textField: UITextField) -> Bool { textField.resignFirstResponder(); return true }
    override func numberOfSections(in tableView: UITableView) -> Int { sections.count }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        switch sections[section] { case .group(let index): return visible[index].count; case .duration: return 1; case .time: return times.count }
    }
    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        switch sections[section] {
        case .group(let index): return groups[index].title
        case .duration: return InspectorLocalization.text("ui_http_timing")
        case .time: return InspectorLocalization.text(timeLabel)
        }
    }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = UITableViewCell(style: .default, reuseIdentifier: nil)
        cell.backgroundColor = InspectorTheme.surface; cell.textLabel?.font = .preferredFont(forTextStyle: .body)
        cell.textLabel?.adjustsFontForContentSizeCategory = true; cell.textLabel?.numberOfLines = 0
        switch sections[indexPath.section] {
        case .duration:
            if let field = conditions?.duration {
                field.removeFromSuperview(); field.translatesAutoresizingMaskIntoConstraints = false; cell.contentView.addSubview(field)
                let margins = cell.contentView.layoutMarginsGuide
                NSLayoutConstraint.activate([field.leadingAnchor.constraint(equalTo: margins.leadingAnchor), field.trailingAnchor.constraint(equalTo: margins.trailingAnchor),
                    field.topAnchor.constraint(equalTo: margins.topAnchor), field.bottomAnchor.constraint(equalTo: margins.bottomAnchor)])
            }
            cell.selectionStyle = .none
        case .time:
            let time = times[indexPath.row]; cell.textLabel?.text = InspectorLocalization.text(time == 0 ? "ui_time_all" : "ui_time_\(time)")
            check(cell, at: indexPath)
        case .group(let index): cell.textLabel?.text = visible[index][indexPath.row].label; check(cell, at: indexPath)
        }
        return cell
    }
    private func check(_ cell: UITableViewCell, at indexPath: IndexPath) {
        let selected: Bool
        switch sections[indexPath.section] {
        case .time: selected = draft.recentMinutes == times[indexPath.row]
        case .group(let index):
            let option = visible[index][indexPath.row], values = draft.values[groups[index].field] ?? []
            selected = option.value.map { values.contains($0) } ?? values.isEmpty
        case .duration: return
        }
        cell.accessoryType = selected ? .checkmark : .none; cell.accessibilityTraits = selected ? [.button, .selected] : .button
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        switch sections[indexPath.section] {
        case .duration: return
        case .time: draft.recentMinutes = times[indexPath.row]
        case .group(let index):
            let field = groups[index].field
            if network { draft.select(field, value: visible[index][indexPath.row].value); syncFields() }
            else if let value = visible[index][indexPath.row].value {
                var values = draft.values[field] ?? []
                if values.contains(value) { values.remove(value) } else { values.insert(value) }
                draft.values[field] = values
            } else { draft.values[field] = [] }
        }
        tableView.reloadData()
    }
}
#endif

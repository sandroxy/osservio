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
    private let advanced = UIStackView()
    private let exclude = UITextField(), path = UITextField(), value = UITextField(), duration = UITextField()

    private var visible: [[(value: String?, label: String)]] = []
    private let times = [0, 5, 30, 60, 1440]

    init(query: InspectorQuery, groups: [InspectorFilterGroup], timeLabel: String = "ui_time", payload: Bool = false, apply: @escaping (InspectorQuery) -> Void) {
        draft = query; self.groups = groups; self.apply = apply; self.timeLabel = timeLabel
        self.payload = payload; network = groups.contains { $0.field == .transport || $0.field == .direction }
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
            let toggle = UIButton(type: .system); toggle.setTitle(InspectorLocalization.text("ui_content_conditions"), for: .normal)
            toggle.addTarget(self, action: #selector(toggleAdvanced), for: .touchUpInside)
            toggle.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
            header.addArrangedSubview(toggle); header.addArrangedSubview(advanced)
            advanced.axis = .vertical; advanced.spacing = 10
            let explanation = InspectorTheme.label(.caption1, secondary: true); explanation.text = InspectorLocalization.text("ui_field_hint")
            advanced.addArrangedSubview(explanation)
            let fields: [(UITextField, String)] = [(exclude, "ui_exclude"), (path, "ui_field_path"), (value, "ui_field_value")] +
                (groups.contains { $0.field == .method } ? [(duration, "ui_duration")] : [])
            for (field, key) in fields {
                field.borderStyle = .roundedRect; field.placeholder = InspectorLocalization.text(key); field.accessibilityLabel = InspectorLocalization.text(key)
                field.font = .preferredFont(forTextStyle: .body); field.adjustsFontForContentSizeCategory = true
                field.autocorrectionType = .no; field.autocapitalizationType = .none; field.returnKeyType = .done; field.delegate = self
                field.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
                field.addTarget(self, action: #selector(editConditions), for: .editingChanged); advanced.addArrangedSubview(field)
            }
            duration.keyboardType = .numberPad
            syncFields(); advanced.isHidden = draft.excludedText.isEmpty && draft.fieldPath.isEmpty && draft.minimumDurationMillis == 0
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
            return options.filter { query.isEmpty || $0.1.localizedCaseInsensitiveContains(query) }
        }
        tableView.reloadData()
    }
    private func syncFields() {
        exclude.text = draft.excludedText; path.text = draft.fieldPath; value.text = draft.fieldValue
        duration.text = draft.minimumDurationMillis == 0 ? "" : String(draft.minimumDurationMillis)
    }
    @objc private func toggleAdvanced() { advanced.isHidden.toggle(); tableView.setNeedsLayout() }
    @objc private func editConditions() {
        guard [exclude, path, value, duration].allSatisfy({ $0.markedTextRange == nil }) else { return }
        for field in [exclude, path, value, duration] {
            let limit = field === path ? 256 : field === duration ? 20 : 512
            if let text = field.text, text.count > limit { field.text = String(text.prefix(limit)) }
        }
        draft.excludedText = exclude.text ?? ""; draft.fieldPath = path.text ?? ""; draft.fieldValue = value.text ?? ""
        if let millis = UInt64(duration.text ?? ""), millis > 0 { draft.select(.transport, value: "http"); draft.minimumDurationMillis = millis }
        else { draft.minimumDurationMillis = 0 }
        tableView.reloadData()
    }
    @objc private func resetDraft() { draft.resetFilters(); syncFields(); tableView.reloadData() }
    @objc private func save() {
        view.endEditing(true)
        if payload {
            editConditions()
            guard (draft.fieldPath.isEmpty || InspectorJSONField.components(draft.fieldPath) != nil),
                  (duration.text?.isEmpty != false || Int64(duration.text ?? "").map { $0 >= 0 } == true) else {
                inspectorNotice(InspectorLocalization.text("ui_field_invalid")); return
            }
        }
        apply(draft); dismiss(animated: true)
    }
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
            if network { draft.select(field, value: visible[indexPath.section][indexPath.row].value); syncFields() }
            else if let value = visible[indexPath.section][indexPath.row].value {
                var values = draft.values[field] ?? []
                if values.contains(value) { values.remove(value) } else { values.insert(value) }
                draft.values[field] = values
            } else { draft.values[field] = [] }
        }
        tableView.reloadData()
    }
}
#endif

#if canImport(UIKit)
import UIKit

/// One temporary draft: common criteria first, precise criteria on demand.
@MainActor
final class InspectorFilterViewController: UITableViewController, UITextFieldDelegate {
    private var draft: InspectorQuery
    private var scopes = InspectorQuery.ScopeDraft()
    private var focusTarget: InspectorQuery.Target?
    private var groups: [InspectorFilterGroup]
    private let recent: InspectorRecentFilters?
    private var recentItems: [InspectorQuery] = []
    private var showsRecent = false
    private let candidates: [InspectorFilterGroup]
    private let apply: (InspectorQuery) -> Void
    private let search = UISearchTextField(), footer = UIStackView()
    private let timeLabel: String
    private let payload: Bool, network: Bool, hasHTTP: Bool, hasSockets: Bool
    private var conditions: InspectorFilterConditions?
    private var expanded: Set<InspectorQuery.Field> = []
    private var timeExpanded = false, durationExpanded = false, refined = false
    private var picker: Int?
    private var overviewOffset = CGPoint.zero
    private let times = [0, 5, 30, 60, 1440]
    private enum Row { case scope, groupHeading(Int), group(Int), option(Int, String?, String), time, timeOption(Int), duration, durationInput, refine, recentToggle, recent(Int) }
    private struct Section { let title: String?; let rows: [Row] }
    private var sections: [Section] = []
    private var scope: InspectorScopeControl?
    private lazy var resetAction = UIBarButtonItem(title: nil, style: .plain, target: self, action: #selector(resetDraft))
    private lazy var backAction = UIBarButtonItem(title: nil, style: .plain, target: self, action: #selector(backToFilters))

    init(query: InspectorQuery, focus: InspectorQuery.Target? = nil, recent: InspectorRecentFilters? = nil, groups: [InspectorFilterGroup], timeLabel: String = "ui_time", payload: Bool = false, apply: @escaping (InspectorQuery) -> Void) {
        self.recent = recent; draft = query; focusTarget = focus; self.groups = groups; self.apply = apply; self.timeLabel = timeLabel; self.payload = payload
        hasHTTP = groups.contains { $0.field == .method || $0.field == .transport && $0.options.contains(where: { $0.value == "http" }) }
        hasSockets = groups.contains { $0.field == .direction || $0.field == .transport && $0.options.contains(where: { $0.value == "webSocket" }) }
        network = hasHTTP || hasSockets
        if payload {
            var scopes = ["metadata", "identity"]
            if network { scopes = ["address", "headers"] + (hasHTTP ? ["request", "response"] : []) + (hasSockets ? ["message"] : []) + scopes }
            else { scopes.insert("message", at: 0) }
            self.groups.append(InspectorFilterGroup(.searchScope, values: scopes))
        }
        candidates = self.groups
        self.groups = Self.available(candidates, query: query)
        super.init(style: .insetGrouped)
        rebuild()
    }
    private static func available(_ candidates: [InspectorFilterGroup], query: InspectorQuery) -> [InspectorFilterGroup] {
        var result = candidates
        for index in result.indices {
            let field = result[index].field, existing = Set(result[index].options.map(\.value))
            for value in (query.values[field] ?? []).subtracting(existing).sorted() {
                result[index].options.append((value, InspectorFilterOptions.label(field, value)))
            }
        }
        return result.filter { !$0.options.isEmpty }.sorted {
            InspectorQuery.fieldOrder.firstIndex(of: $0.field)! < InspectorQuery.fieldOrder.firstIndex(of: $1.field)!
        }
    }
    @available(*, unavailable) required init?(coder: NSCoder) { return nil }
    override func loadView() { super.loadView(); tableView = InspectorTableView(frame: .zero, style: .insetGrouped) }
    override func viewDidLoad() {
        super.viewDidLoad(); InspectorTheme.apply(self)
        inspectorPrimaryAction = UIBarButtonItem(title: text("ui_apply"), style: .done, target: self, action: #selector(save))
        search.placeholder = text("ui_filter_options"); search.delegate = self
        search.font = .preferredFont(forTextStyle: .body); search.adjustsFontForContentSizeCategory = true
        search.autocorrectionType = .no; search.autocapitalizationType = .none; search.returnKeyType = .done
        search.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        search.addTarget(self, action: #selector(filterOptions), for: .editingChanged)
        footer.axis = .vertical; footer.spacing = 12; footer.isLayoutMarginsRelativeArrangement = true
        footer.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 12, leading: 20, bottom: 24, trailing: 20)
        if payload {
            let editor = InspectorFilterConditions(query: draft, delegate: self); conditions = editor
            editor.changed = { [weak self] in self?.editConditions() }
            editor.layoutChanged = { [weak self] in self?.resizeFooter() }
            footer.addArrangedSubview(editor)
        }
        let hint = InspectorTheme.label(.caption1, secondary: true); hint.text = text("ui_filter_hint"); footer.addArrangedSubview(hint)
        tableView.keyboardDismissMode = .onDrag
        tableView.rowHeight = UITableView.automaticDimension; tableView.estimatedRowHeight = 58
        rebuild()
        recent?.ownerChanged = { [weak self] in self?.dismiss(animated: false) }
        recent?.load { [weak self] items in
            guard let self, !items.isEmpty else { return }
            self.recentItems = items
            // Do not reload an active picker or text editor when disk data arrives.
            if self.picker == nil && !self.refined && !self.durationExpanded { self.rebuild() }
        }
    }
    private func text(_ key: String) -> String { InspectorLocalization.text(key) }
    private var transport: String? { draft.transport ?? (hasHTTP && !hasSockets ? "http" : hasSockets && !hasHTTP ? "webSocket" : nil) }
    private func applicable(_ group: InspectorFilterGroup) -> Bool {
        if !network { return group.field != .transport }
        if InspectorQuery.httpFields.contains(group.field) { return transport == "http" }
        if InspectorQuery.socketFields.contains(group.field) {
            if transport != "webSocket" { return false }
            if [.direction, .outcome].contains(group.field), draft.values[.activity] == ["connections"] { return false }
            if group.field == .closeCode, draft.values[.activity] == ["messages"] { return false }
            if group.field == .kind, let activity = draft.values[.activity]?.first,
               !group.options.contains(where: { ["message", "messageResult"].contains($0.value) == (activity == "messages") }) { return false }
        }
        return group.field != .transport
    }
    private func advanced(_ group: InspectorFilterGroup) -> Bool {
        if [.searchScope, .content, .source, .connection, .outcome, .closeCode, .excludedCategory, .excludedHost].contains(group.field) { return true }
        if group.field == .kind && network { return true }
        return [.host, .category].contains(group.field) && group.options.count == 1
    }
    private func rows(for indices: [Int]) -> [Row] {
        indices.flatMap { index -> [Row] in
            if direct(groups[index]) { return [.groupHeading(index)] + choices(index) }
            return [.group(index)] + (expanded.contains(groups[index].field) ? choices(index) : [])
        }
    }
    private func direct(_ group: InspectorFilterGroup) -> Bool {
        [.level, .activity, .direction].contains(group.field) && group.options.count <= 6
    }
    private func choices(_ index: Int) -> [Row] {
        let group = groups[index], term = picker == nil ? "" : search.text ?? ""
        var options = group.options
        if network, group.field == .kind, let activity = draft.values[.activity]?.first {
            options.removeAll { ["message", "messageResult"].contains($0.value) != (activity == "messages") }
        }
        if group.field == .searchScope {
            options.removeAll { (transport == "http" && $0.value == "message") || (transport == "webSocket" && ["request", "response"].contains($0.value)) }
        }
        if group.field == .status, term.count == 3, term.allSatisfy({ $0.isASCII && $0.isNumber }), let code = Int(term), (100...599).contains(code), !options.contains(where: { $0.value == term }) {
            options.append((term, term))
        }
        return (term.isEmpty ? [.option(index, nil, text([.excludedCategory, .excludedHost].contains(group.field) ? "ui_no_exclusions" : "ui_all"))] : []) + options.filter {
            term.isEmpty || $0.label.localizedCaseInsensitiveContains(term) || $0.value.localizedCaseInsensitiveContains(term)
        }.map { .option(index, $0.value, $0.label) }
    }
    private func rebuild() {
        if let picker {
            sections = [Section(title: nil, rows: choices(picker))]
        } else {
            let commonGroups = groups.indices.filter { applicable(groups[$0]) && !advanced(groups[$0]) }
            var common = rows(for: commonGroups.filter { direct(groups[$0]) }) + [.time]
            if timeExpanded { common += times.map(Row.timeOption) }
            common += rows(for: commonGroups.filter { !direct(groups[$0]) })
            if payload && transport == "http" { common.append(.duration); if durationExpanded { common.append(.durationInput) } }
            sections = (hasHTTP && hasSockets ? [Section(title: text("ui_filter_scope"), rows: [.scope])] : [])
                + [Section(title: text("ui_filter_common"), rows: common)]
            if !recentItems.isEmpty {
                sections.append(Section(title: nil, rows: [.recentToggle] + (showsRecent ? recentItems.indices.map(Row.recent) : [])))
            }
            let precise = groups.indices.filter { applicable(groups[$0]) && advanced(groups[$0]) }
            if payload || !precise.isEmpty { sections.append(Section(title: nil, rows: [.refine] + (refined ? rows(for: precise) : []))) }
        }
        guard isViewLoaded else { return }
        title = picker.map { groups[$0].title } ?? text("ui_filters")
        let leading = picker == nil ? resetAction : backAction
        leading.title = text(picker == nil ? "ui_reset" : "ui_back")
        leading.accessibilityLabel = leading.title
        InspectorTheme.apply(leading)
        if navigationItem.leftBarButtonItem !== leading { navigationItem.leftBarButtonItem = leading }
        if picker != nil {
            search.placeholder = text(groups[picker!].field == .status ? "ui_specific_status" : "ui_filter_options")
            let header = UIStackView(arrangedSubviews: [search]); header.axis = .vertical; header.isLayoutMarginsRelativeArrangement = true
            header.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 12, leading: 20, bottom: 12, trailing: 20)
            tableView.tableHeaderView = header; tableView.tableFooterView = nil
        } else {
            tableView.tableHeaderView = nil; conditions?.isHidden = !refined; tableView.tableFooterView = footer
        }
        tableView.reloadData(); resizeFooter()
    }
    private func resizeFooter() {
        guard isViewLoaded, picker == nil, tableView.bounds.width > 0 else { return }
        let height = footer.systemLayoutSizeFitting(CGSize(width: tableView.bounds.width, height: UIView.layoutFittingCompressedSize.height), withHorizontalFittingPriority: .required, verticalFittingPriority: .fittingSizeLevel).height
        let size = CGSize(width: tableView.bounds.width, height: ceil(height))
        if footer.frame.size != size { footer.frame.size = size; tableView.tableFooterView = footer }
    }
    override func viewDidLayoutSubviews() { super.viewDidLayoutSubviews(); resizeFooter() }
    @objc private func filterOptions() {
        guard search.markedTextRange == nil, let picker else { return }
        let value = String((search.text ?? "").prefix(512)); if value != search.text { search.text = value }
        sections = [Section(title: nil, rows: choices(picker))]; tableView.reloadData()
    }
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        guard let target = focusTarget else { return }
        focusTarget = nil
        switch target {
        case .field(.transport): break
        case .field(let field):
            guard let index = groups.firstIndex(where: { $0.field == field }) else { return }
            refined = advanced(groups[index])
            if !direct(groups[index]) && (groups[index].options.count > 6 || field == .status) { picker = index }
            else { expanded.insert(field) }
        case .time: timeExpanded = true
        case .duration: durationExpanded = true
        case .excludedText, .json: refined = true
        }
        rebuild()
        tableView.layoutIfNeeded()
        guard picker == nil else { return }
        if target == .excludedText || target == .json {
            if let field = target == .json ? conditions?.path : conditions?.exclude {
                tableView.scrollRectToVisible(field.convert(field.bounds, to: tableView), animated: false)
            }
            return
        }
        for (section, group) in sections.enumerated() {
            for (row, item) in group.rows.enumerated() {
                let matches: Bool
                switch item {
                case .scope: matches = target == .field(.transport)
                case .group(let index), .groupHeading(let index): matches = target == .field(groups[index].field)
                case .time: matches = target == .time
                case .duration: matches = target == .duration
                default: matches = false
                }
                if matches { tableView.scrollToRow(at: IndexPath(row: row, section: section), at: .top, animated: false); return }
            }
        }
    }
    override func accessibilityPerformEscape() -> Bool {
        if picker != nil { backToFilters(); return true }
        return super.accessibilityPerformEscape()
    }
    @objc private func backToFilters() { view.endEditing(true); picker = nil; search.text = ""; rebuild(); tableView.setContentOffset(overviewOffset, animated: false) }
    private func editConditions() {
        guard let fields = conditions else { return }
        draft.excludedText = fields.exclude.text ?? ""; draft.fieldPath = fields.path.text ?? ""; draft.fieldValue = fields.value.text ?? ""
        draft.minimumDurationMillis = UInt64(fields.duration.text ?? "") ?? 0
    }
    @objc private func resetDraft() { view.endEditing(true); scopes = InspectorQuery.ScopeDraft(); draft.resetFilters(); conditions?.sync(draft); rebuild() }
    @objc private func save() {
        view.endEditing(true); editConditions()
        if let fields = conditions {
            guard (draft.fieldPath.isEmpty || InspectorJSONField.components(draft.fieldPath) != nil),
                  (fields.duration.text?.isEmpty != false || Int64(fields.duration.text ?? "").map { $0 >= 0 } == true) else {
                inspectorNotice(text("ui_field_invalid")); return
            }
        }
        if !(hasHTTP && hasSockets) { draft.values[.transport] = nil }
        recent?.update(draft); apply(draft); dismiss(animated: true)
    }
    func textFieldShouldReturn(_ textField: UITextField) -> Bool { textField.resignFirstResponder(); return true }
    override func numberOfSections(in tableView: UITableView) -> Int { sections.count }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { sections[section].rows.count }
    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? { sections[section].title }
    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        if picker != nil, sections[section].rows.isEmpty { return text("ui_filter_options_empty") }
        if let picker, groups[picker].field == .searchScope { return text("ui_search_scope_hint") }
        if case .scope? = sections[section].rows.first { return text("ui_filter_switch") }
        return nil
    }
    private func refineSummary() -> String {
        var details = groups.filter { applicable($0) && advanced($0) && !(draft.values[$0.field]?.isEmpty ?? true) }
            .map { $0.title + ": " + compact(summary($0)) }
        if !draft.excludedText.isEmpty { details.append(text("ui_exclude") + ": " + compact(draft.excludedText)) }
        if !draft.fieldPath.isEmpty { details.append(text("ui_json_field") + ": " + compact(draft.fieldPath + " = " + draft.fieldValue)) }
        return details.isEmpty ? text("ui_filter_refine_hint") : details.prefix(3).joined(separator: " · ")
            + (details.count > 3 ? " · +\(details.count - 3)" : "")
    }
    private func compact(_ value: String) -> String { String(value.prefix(80)) + (value.count > 80 ? "…" : "") }
    private func summary(_ group: InspectorFilterGroup) -> String {
        let values = draft.values[group.field] ?? []
        guard !values.isEmpty else { return text("ui_all") }
        return values.sorted().prefix(2).map { value in group.options.first { $0.value == value }?.label ?? InspectorFilterOptions.label(group.field, value) }.joined(separator: ", ") + (values.count > 2 ? " +\(values.count - 2)" : "")
    }
    override func tableView(_ tableView: UITableView, cellForRowAt path: IndexPath) -> UITableViewCell {
        let row = sections[path.section].rows[path.row]
        let reusable: Bool
        switch row { case .option, .timeOption: reusable = true; default: reusable = false }
        let cell = reusable ? tableView.dequeueReusableCell(withIdentifier: "choice") ?? UITableViewCell(style: .subtitle, reuseIdentifier: "choice")
            : UITableViewCell(style: .subtitle, reuseIdentifier: nil)
        cell.accessoryView = nil; cell.accessoryType = .none; cell.indentationLevel = 0
        cell.textLabel?.text = nil; cell.detailTextLabel?.text = nil
        cell.backgroundColor = InspectorTheme.surface
        cell.textLabel?.font = .preferredFont(forTextStyle: .body); cell.textLabel?.adjustsFontForContentSizeCategory = true; cell.textLabel?.numberOfLines = 0
        cell.textLabel?.lineBreakMode = .byWordWrapping
        cell.detailTextLabel?.font = .preferredFont(forTextStyle: .subheadline); cell.detailTextLabel?.adjustsFontForContentSizeCategory = true
        cell.detailTextLabel?.textColor = InspectorTheme.secondary; cell.detailTextLabel?.numberOfLines = 2
        func selected(_ value: Bool) {
            let mark = UIImageView(image: value ? UIImage(systemName: "checkmark") : nil)
            mark.frame.size = CGSize(width: 22, height: 22); mark.contentMode = .scaleAspectFit
            mark.tintColor = InspectorTheme.accent
            // Reserve trailing space without relying on accessory alpha, which UIKit manages.
            cell.accessoryView = mark; cell.accessibilityTraits = value ? [.button, .selected] : .button
        }
        func disclosure(_ open: Bool) { let icon = UIImageView(image: UIImage(systemName: open ? "chevron.up" : "chevron.down")); icon.tintColor = InspectorTheme.secondary; cell.accessoryView = icon; cell.accessibilityTraits = .button }
        func embed(_ view: UIView) {
            view.removeFromSuperview(); view.translatesAutoresizingMaskIntoConstraints = false; cell.contentView.addSubview(view)
            let margins = cell.contentView.layoutMarginsGuide
            NSLayoutConstraint.activate([view.leadingAnchor.constraint(equalTo: margins.leadingAnchor), view.trailingAnchor.constraint(equalTo: margins.trailingAnchor), view.topAnchor.constraint(equalTo: margins.topAnchor), view.bottomAnchor.constraint(equalTo: margins.bottomAnchor)])
            cell.selectionStyle = .none
        }
        switch row {
        case .groupHeading(let index):
            let group = groups[index]
            cell.textLabel?.text = String(format: text(group.field == .activity ? "ui_filter_single_title" : "ui_filter_multiple_title"), group.title)
            cell.textLabel?.font = .preferredFont(forTextStyle: .subheadline); cell.textLabel?.textColor = InspectorTheme.secondary
            cell.accessibilityTraits = .header; cell.selectionStyle = .none
        case .recentToggle:
            cell.textLabel?.text = text("ui_recent_filters"); disclosure(showsRecent)
        case .recent(let index):
            let item = recentItems[index], label = InspectorFilterOptions.summary(item)
            cell.textLabel?.text = label; cell.detailTextLabel?.text = text("ui_restore_filter")
            let remove = InspectorFilterToken { [weak self] in
                guard let self else { return }
                self.recent?.update(item, removing: true); self.recentItems.removeAll { $0 == item }; self.rebuild()
            }
            remove.setImage(UIImage(systemName: "xmark"), for: .normal)
            remove.frame.size = CGSize(width: 44, height: 44)
            remove.accessibilityLabel = text("ui_forget_filter") + ", " + label; cell.accessoryView = remove
        case .scope:
            if scope == nil {
                let control = InspectorScopeControl(titles: [text("ui_all"), "HTTP", "WebSocket"])
                control.onSelect = { [weak self] index in
                    guard let self else { return }; self.view.endEditing(true)
                    self.scopes.switchTo(index == 1 ? "http" : index == 2 ? "webSocket" : nil, in: &self.draft)
                    self.conditions?.sync(self.draft); self.rebuild()
                }; scope = control
            }
            scope?.selectedIndex = draft.transport == "http" ? 1 : draft.transport == "webSocket" ? 2 : 0
            if let scope { embed(scope) }; cell.backgroundColor = InspectorTheme.canvas
        case .group(let index):
            cell.textLabel?.text = groups[index].title; cell.detailTextLabel?.text = summary(groups[index]); disclosure(expanded.contains(groups[index].field))
            if groups[index].options.count > 6 || groups[index].field == .status { cell.accessoryView = nil; cell.accessoryType = .disclosureIndicator }
        case .option(let index, let value, let label):
            cell.textLabel?.text = label
            if picker == nil && !direct(groups[index]) { cell.indentationLevel = 1; cell.indentationWidth = 12 }
            let values = draft.values[groups[index].field] ?? []; selected(value.map(values.contains) ?? values.isEmpty)
        case .time: cell.textLabel?.text = text(timeLabel); cell.detailTextLabel?.text = text(draft.recentMinutes == 0 ? "ui_time_all" : "ui_time_\(draft.recentMinutes)"); disclosure(timeExpanded)
        case .timeOption(let value): cell.textLabel?.text = text(value == 0 ? "ui_time_all" : "ui_time_\(value)"); cell.indentationLevel = 1; cell.indentationWidth = 12; selected(value == draft.recentMinutes)
        case .duration: cell.textLabel?.text = text("ui_http_timing"); cell.detailTextLabel?.text = draft.minimumDurationMillis == 0 ? text("ui_all") : "≥ \(draft.minimumDurationMillis) ms"; disclosure(durationExpanded)
        case .durationInput: if let field = conditions?.duration { embed(field) }
        case .refine: cell.textLabel?.text = text("ui_filter_refine"); cell.detailTextLabel?.text = refineSummary(); cell.detailTextLabel?.numberOfLines = 3; disclosure(refined)
        }
        return cell
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt path: IndexPath) {
        tableView.deselectRow(at: path, animated: true)
        let wasPicker = picker != nil
        switch sections[path.section].rows[path.row] {
        case .scope, .groupHeading, .durationInput: return
        case .recentToggle: showsRecent.toggle()
        case .recent(let index):
            let term = draft.text
            draft = recentItems[index]; draft.text = term
            scopes = InspectorQuery.ScopeDraft(); showsRecent = false
            groups = Self.available(candidates, query: draft); conditions?.sync(draft)
            rebuild(); tableView.setContentOffset(CGPoint(x: 0, y: -tableView.adjustedContentInset.top), animated: false); return
        case .group(let index):
            view.endEditing(true)
            if groups[index].options.count > 6 || groups[index].field == .status { overviewOffset = tableView.contentOffset; picker = index; search.text = "" }
            else if expanded.contains(groups[index].field) { expanded.remove(groups[index].field) } else { expanded.insert(groups[index].field) }
        case .option(let index, let value, _):
            view.endEditing(true)
            draft.select(groups[index].field, value: value)
            if let value, !groups[index].options.contains(where: { $0.value == value }) { groups[index].options.append((value, value)) }
            conditions?.sync(draft)
        case .time: view.endEditing(true); timeExpanded.toggle()
        case .timeOption(let value): draft.recentMinutes = value; timeExpanded = false
        case .duration: durationExpanded.toggle()
        case .refine: view.endEditing(true); refined.toggle()
        }
        rebuild()
        if !wasPicker && picker != nil { tableView.setContentOffset(.zero, animated: false) }
    }
}
#endif

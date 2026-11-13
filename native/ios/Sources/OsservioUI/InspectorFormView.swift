#if canImport(UIKit)
import UIKit

@MainActor
final class InspectorFormView: UITableView, UITableViewDataSource, UITableViewDelegate {
    private(set) var document: InspectorFormDocument?
    var open: ((InspectorFormDocument.Field) -> Void)?
    private var focusedField: Int?
    init() {
        super.init(frame: .zero, style: .insetGrouped)
        dataSource = self; delegate = self; keyboardDismissMode = .onDrag
        rowHeight = UITableView.automaticDimension; estimatedRowHeight = 64
        backgroundColor = InspectorTheme.canvas; accessibilityIdentifier = "Osservio.Form.Fields"
    }
    @available(*, unavailable) required init?(coder: NSCoder) { return nil }
    func display(_ value: InspectorFormDocument) { document = value; focusedField = nil; reloadData() }
    func focus(_ range: NSRange?, reveal: Bool) {
        let next = range.flatMap { document?.field(at: $0.location) }
        if next != focusedField { focusedField = next; refreshPresentation() }
        if reveal, let next { scrollToRow(at: IndexPath(row: next, section: 0), at: .middle, animated: false) }
    }
    func refreshPresentation() {
        backgroundColor = InspectorTheme.canvas
        for path in indexPathsForVisibleRows ?? [] { if let cell = cellForRow(at: path) { configure(cell, index: path.row) } }
    }
    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { document?.fields.count ?? 0 }
    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = dequeueReusableCell(withIdentifier: "field") ?? UITableViewCell(style: .subtitle, reuseIdentifier: "field")
        configure(cell, index: indexPath.row); return cell
    }
    private func configure(_ cell: UITableViewCell, index: Int) {
        guard let field = document?.fields[index] else { return }
        cell.textLabel?.text = field.name.isEmpty ? InspectorLocalization.text("form_empty_name") : InspectorRecordPresentation.compact(field.name, limit: 180)
        cell.detailTextLabel?.text = Self.value(field)
        cell.textLabel?.font = .preferredFont(forTextStyle: .caption1); cell.textLabel?.textColor = InspectorTheme.secondary
        cell.detailTextLabel?.font = .preferredFont(forTextStyle: .body); cell.detailTextLabel?.textColor = .label
        cell.textLabel?.numberOfLines = 3; cell.detailTextLabel?.numberOfLines = 6
        cell.textLabel?.adjustsFontForContentSizeCategory = true; cell.detailTextLabel?.adjustsFontForContentSizeCategory = true
        cell.backgroundColor = index == focusedField ? InspectorTheme.tint : InspectorTheme.surface
        cell.accessoryType = .disclosureIndicator
        cell.accessibilityIdentifier = "Osservio.Form.Field.\(index)"
    }
    static func value(_ field: InspectorFormDocument.Field) -> String {
        if !field.hasEquals { return InspectorLocalization.text("form_missing_equals") }
        if field.value.isEmpty { return InspectorLocalization.text("form_empty_value") }
        return String(field.value.prefix(512)) + (field.value.count > 512 ? "…" : "")
    }
    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        deselectRow(at: indexPath, animated: true)
        if let field = document?.fields[indexPath.row] { open?(field) }
    }
    func tableView(_ tableView: UITableView, contextMenuConfigurationForRowAt indexPath: IndexPath, point: CGPoint) -> UIContextMenuConfiguration? {
        guard let field = document?.fields[indexPath.row] else { return nil }
        return UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { _ in
            UIMenu(children: [UIAction(title: InspectorLocalization.text("form_copy_source"), image: UIImage(systemName: "doc.on.doc")) { _ in
                UIPasteboard.general.string = field.original
            }])
        }
    }
}
#endif

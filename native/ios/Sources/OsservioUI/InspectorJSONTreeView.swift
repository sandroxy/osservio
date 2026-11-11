#if canImport(UIKit)
import UIKit

/// Reuses native rows without separators or disclosure accessories. Only visible rows format text.
@MainActor
final class InspectorJSONTreeView: UITableView, UITableViewDataSource, UITableViewDelegate {
    private(set) var document: InspectorJSONDocument?
    private(set) var expanded: Set<Int> = []
    private(set) var rows: [InspectorJSONDocument.Row] = []
    private var focusedNode: Int?
    init() {
        super.init(frame: .zero, style: .plain)
        dataSource = self; delegate = self; separatorStyle = .none
        rowHeight = UITableView.automaticDimension; estimatedRowHeight = 32
        backgroundColor = InspectorTheme.canvas; keyboardDismissMode = .onDrag
        contentInset = UIEdgeInsets(top: 12, left: 0, bottom: 16, right: 0)
        register(JSONCell.self, forCellReuseIdentifier: "node")
        accessibilityIdentifier = "Osservio.JSON.Tree"
    }
    @available(*, unavailable) required init?(coder: NSCoder) { return nil }
    func display(_ document: InspectorJSONDocument) {
        self.document = document; expanded = []; focusedNode = nil; rebuild()
    }
    func focus(_ range: NSRange?, reveal: Bool) {
        guard let document else { return }
        let next = range.map { document.node(at: $0.location) }
        var changed = next != focusedNode; focusedNode = next
        if reveal, let focusedNode {
            let parents = document.ancestors(of: focusedNode)
            changed = changed || !parents.isSubset(of: expanded); expanded.formUnion(parents)
        }
        if changed { rebuild() }
        if reveal, let focusedNode, let index = rows.firstIndex(where: { $0.node == focusedNode && !$0.closing }) {
            scrollToRow(at: IndexPath(row: index, section: 0), at: .middle, animated: false)
        }
    }
    private func rebuild() { rows = document?.rows(expanded: expanded) ?? []; reloadData() }
    func refreshPresentation() {
        // Refresh retained visible cells without rebuilding the tree or moving its scroll position.
        for path in indexPathsForVisibleRows ?? [] {
            if path.row < rows.count, let cell = cellForRow(at: path) as? JSONCell { configure(cell, row: rows[path.row]) }
        }
    }
    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { rows.count }
    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = dequeueReusableCell(withIdentifier: "node", for: indexPath) as! JSONCell
        configure(cell, row: rows[indexPath.row]); return cell
    }
    private func configure(_ cell: JSONCell, row: InspectorJSONDocument.Row) {
        guard let document else { return }
        let node = document.nodes[row.node]
        let toggle = !row.closing && row.node != 0 && node.expandable
        let key = row.closing ? "" : document.key(row.node)
        let token = document.token(row, expanded: expanded) + document.comma(row, expanded: expanded)
        let prefix = node.depth > 8 ? "… " : ""
        let value = NSMutableAttributedString(string: prefix + key, attributes: [.foregroundColor: InspectorTheme.accent])
        let color: UIColor
        switch node.kind {
        case .string: color = InspectorTheme.jsonString
        case .number: color = InspectorTheme.jsonNumber
        case .literal: color = InspectorTheme.jsonLiteral
        default: color = InspectorTheme.secondary
        }
        value.append(NSAttributedString(string: token, attributes: [.foregroundColor: color]))
        cell.code.font = UIFontMetrics(forTextStyle: .body).scaledFont(for: .monospacedSystemFont(ofSize: 14, weight: .regular))
        cell.code.attributedText = value; cell.indent.constant = 16 + CGFloat(min(node.depth, 8)) * 14
        cell.minimum.constant = toggle ? 44 : 28
        cell.backgroundColor = focusedNode == row.node && !row.closing ? InspectorTheme.tint : InspectorTheme.canvas
        cell.accessibilityLabel = value.string
        cell.accessibilityTraits = toggle ? .button : .staticText
        cell.accessibilityValue = toggle ? InspectorLocalization.text(expanded.contains(row.node) ? "json_expanded" : "json_collapsed") : nil
        cell.accessibilityHint = InspectorLocalization.text(toggle ? "json_toggle_hint" : "json_copy_hint")
        cell.accessibilityIdentifier = "Osservio.JSON.Node.\(row.identity)"
        cell.selectionStyle = toggle ? .default : .none
    }
    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        deselectRow(at: indexPath, animated: false)
        guard let document else { return }
        let row = rows[indexPath.row]
        guard !row.closing, row.node != 0, document.nodes[row.node].expandable else { return }
        let previousOffset = contentOffset
        if expanded.contains(row.node) { expanded.remove(row.node) } else { expanded.insert(row.node) }
        rebuild(); layoutIfNeeded()
        let maxY = max(-adjustedContentInset.top, contentSize.height - bounds.height + adjustedContentInset.bottom)
        setContentOffset(CGPoint(x: previousOffset.x, y: min(previousOffset.y, maxY)), animated: false)
    }
    func tableView(_ tableView: UITableView, contextMenuConfigurationForRowAt indexPath: IndexPath, point: CGPoint) -> UIContextMenuConfiguration? {
        guard let document else { return nil }
        let node = rows[indexPath.row].node
        return UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { _ in
            UIMenu(children: [UIAction(title: InspectorLocalization.text("json_copy_value"), image: UIImage(systemName: "doc.on.doc")) { _ in
                UIPasteboard.general.string = document.original(node)
            }])
        }
    }
}

@MainActor
private final class JSONCell: UITableViewCell {
    let code = UILabel()
    private(set) var indent: NSLayoutConstraint!
    private(set) var minimum: NSLayoutConstraint!
    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        isAccessibilityElement = true; code.numberOfLines = 0; code.adjustsFontForContentSizeCategory = true
        code.translatesAutoresizingMaskIntoConstraints = false; contentView.addSubview(code)
        indent = code.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 16)
        minimum = contentView.heightAnchor.constraint(greaterThanOrEqualToConstant: 28)
        NSLayoutConstraint.activate([indent, minimum,
            code.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -16),
            code.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 3),
            code.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -3)])
    }
    @available(*, unavailable) required init?(coder: NSCoder) { return nil }
}
#endif

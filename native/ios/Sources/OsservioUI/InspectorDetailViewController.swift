#if canImport(UIKit)
import UIKit

/// Grouped admitted metadata; large content is opened in a separate, bounded reader.
@MainActor
class InspectorDetailViewController: UITableViewController {
    struct Row {
        let label: String
        let value: String?
        let action: (() -> Void)?
        init(_ label: String, _ value: String? = nil, action: (() -> Void)? = nil) {
            self.label = label; self.value = value; self.action = action
        }
    }
    struct Section {
        let title: String
        let rows: [Row]
        var footer: String? = nil
    }
    var sections: [Section] = [] { didSet { if isViewLoaded { tableView.reloadData() } } }
    init() { super.init(style: .insetGrouped) }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func loadView() { super.loadView(); tableView = InspectorTableView(frame: .zero, style: .insetGrouped) }
    override func viewDidLoad() {
        super.viewDidLoad(); InspectorTheme.apply(self)
        tableView.rowHeight = UITableView.automaticDimension; tableView.estimatedRowHeight = 68
    }
    override func viewWillAppear(_ animated: Bool) { super.viewWillAppear(animated); InspectorTheme.apply(self) }
    override func numberOfSections(in tableView: UITableView) -> Int { sections.count }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { sections[section].rows.count }
    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? { sections[section].title }
    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? { sections[section].footer }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let row = sections[indexPath.section].rows[indexPath.row]
        let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
        cell.backgroundColor = InspectorTheme.surface
        cell.textLabel?.text = row.label; cell.detailTextLabel?.text = row.value
        cell.textLabel?.font = .preferredFont(forTextStyle: row.value == nil ? .body : .caption1)
        cell.textLabel?.textColor = row.action != nil ? InspectorTheme.accent : row.value == nil ? .label : InspectorTheme.secondary
        cell.detailTextLabel?.font = .preferredFont(forTextStyle: .body); cell.detailTextLabel?.textColor = .label
        cell.textLabel?.adjustsFontForContentSizeCategory = true; cell.textLabel?.numberOfLines = row.action == nil ? 0 : 4
        cell.detailTextLabel?.adjustsFontForContentSizeCategory = true; cell.detailTextLabel?.numberOfLines = row.action == nil ? 0 : 3
        cell.accessoryType = row.action == nil ? .none : .disclosureIndicator
        cell.selectionStyle = row.action == nil ? .none : .default
        if row.action != nil { cell.accessibilityTraits.insert(.button) }
        return cell
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true); sections[indexPath.section].rows[indexPath.row].action?()
    }
    func read(_ title: String, content: String, code: Bool = true) {
        inspectorPush(InspectorContentViewController(title: title, content: content, code: code))
    }
}
#endif

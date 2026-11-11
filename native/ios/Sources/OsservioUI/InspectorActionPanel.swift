#if canImport(UIKit)
import UIKit

/// Short contextual actions use a native sheet, with destructive actions separated.
@MainActor
final class InspectorActionPanel: UITableViewController {
    struct Action {
        enum Style { case `default`, destructive }
        let title: String
        let style: Style
        var isEnabled = true
        var image: UIImage?
        let handler: (Action) -> Void
        init(title: String, style: Style, handler: @escaping (Action) -> Void) {
            self.title = title; self.style = style; self.handler = handler
        }
    }
    private let message: String?
    private var actions: [Action] = []
    private var groups: [[Action]] {
        [actions.filter { $0.style == .default }, actions.filter { $0.style == .destructive }].filter { !$0.isEmpty }
    }
    init(title: String, message: String?) { self.message = message; super.init(style: .insetGrouped); self.title = title }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    func addAction(_ action: Action) { actions.append(action) }
    override func viewDidLoad() {
        super.viewDidLoad(); InspectorTheme.apply(self)
        tableView.rowHeight = UITableView.automaticDimension; tableView.estimatedRowHeight = 56

    }
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        if #available(iOS 15, *), let sheet = navigationController?.sheetPresentationController {
            sheet.detents = traitCollection.preferredContentSizeCategory.isAccessibilityCategory ? [.large()] : [.medium(), .large()]
            sheet.prefersGrabberVisible = true; sheet.preferredCornerRadius = 28
        }
    }
    override func accessibilityPerformEscape() -> Bool { dismiss(animated: true); return true }
    override func numberOfSections(in tableView: UITableView) -> Int { groups.count }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { groups[section].count }
    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? { section == 0 ? message : nil }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let action = groups[indexPath.section][indexPath.row]
        let cell = UITableViewCell(style: .default, reuseIdentifier: nil)
        cell.textLabel?.text = action.title; cell.textLabel?.numberOfLines = 0
        cell.textLabel?.font = .preferredFont(forTextStyle: .body); cell.textLabel?.adjustsFontForContentSizeCategory = true
        cell.textLabel?.textColor = !action.isEnabled ? InspectorTheme.secondary : action.style == .destructive ? .systemRed : .label
        cell.backgroundColor = InspectorTheme.surface; cell.isUserInteractionEnabled = action.isEnabled
        cell.accessibilityTraits = action.isEnabled ? .button : [.button, .notEnabled]
        cell.imageView?.image = action.image; cell.imageView?.tintColor = InspectorTheme.accent
        if action.style == .destructive { cell.imageView?.image = UIImage(systemName: "trash"); cell.imageView?.tintColor = .systemRed }
        return cell
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        let action = groups[indexPath.section][indexPath.row]
        guard action.isEnabled else { return }
        dismiss(animated: true) { action.handler(action) }
    }
}

@MainActor
func inspectorCopyItem(target: Any, action: Selector, label: String) -> UIBarButtonItem {
    let item = UIBarButtonItem(image: UIImage(systemName: "doc.on.doc"), style: .plain, target: target, action: action)
    item.accessibilityLabel = InspectorLocalization.text(label)
    return item
}

@MainActor
func inspectorDismissItem(target: Any, action: Selector, label: String = "archive_cancel") -> UIBarButtonItem {
    let item = UIBarButtonItem(image: UIImage(systemName: "xmark"), style: .plain, target: target, action: action)
    item.accessibilityLabel = InspectorLocalization.text(label)
    return item
}
#endif

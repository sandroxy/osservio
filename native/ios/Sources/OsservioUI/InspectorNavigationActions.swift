#if canImport(UIKit)
import ObjectiveC
import UIKit

/// Keeps page actions independent of their visible representation and the fixed exit control.
@MainActor
final class InspectorNavigationActions: NSObject {
    private static var key: UInt8 = 0
    private weak var owner: UIViewController?
    var items: [UIBarButtonItem] = []
    var close: UIBarButtonItem?
    private var minimize: UIBarButtonItem?
    private lazy var more = UIBarButtonItem(image: UIImage(systemName: "ellipsis"), style: .plain,
                                           target: self, action: #selector(openMenu))

    static func state(for owner: UIViewController) -> InspectorNavigationActions {
        if let state = objc_getAssociatedObject(owner, &key) as? InspectorNavigationActions { return state }
        let state = InspectorNavigationActions(); state.owner = owner
        objc_setAssociatedObject(owner, &key, state, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        return state
    }

    func render() {
        guard let owner else { return }
        var exit = close
        if let navigation = owner.navigationController as? InspectorNavigationController,
           navigation.session != nil, !navigation.transientPanel {
            if minimize == nil { minimize = navigation.minimizeItem() }
            minimize?.accessibilityLabel = InspectorLocalization.text("ui_minimize"); exit = minimize
        }
        let actions = items
        more.accessibilityLabel = InspectorLocalization.text("ui_actions")
        more.accessibilityIdentifier = "Osservio.MoreActions"
        let visible = actions.count > (exit == nil ? 2 : 1) ? [more] : actions
        // UIKit's trailing items are ordered from the outside edge inward.
        let result = (exit.map { [$0] } ?? []) + visible
        for item in result { item.tintColor = InspectorTheme.accent }
        let existing = owner.navigationItem.rightBarButtonItems ?? []
        if existing.count != result.count || !zip(existing, result).allSatisfy({ $0 === $1 }) {
            owner.navigationItem.rightBarButtonItems = result.isEmpty ? nil : result
        }
    }

    @objc private func openMenu() {
        guard let owner else { return }
        let panel = InspectorActionPanel(title: InspectorLocalization.text("ui_actions"), message: nil)
        for item in items.reversed() where item.accessibilityIdentifier != "Osservio.CloseInspector" {
            var action = InspectorActionPanel.Action(title: item.accessibilityLabel ?? item.title ?? "", style: .default) { [weak item] _ in
                guard let item, item.isEnabled, let selector = item.action else { return }
                UIApplication.shared.sendAction(selector, to: item.target, from: item, for: nil)
            }
            action.image = item.image; action.isEnabled = item.isEnabled
            panel.addAction(action)
        }
        owner.inspectorPresent(panel)
    }
}
#endif

#if canImport(UIKit)
import UIKit

/// SDK-owned colors and presentation. Never changes a window, host defaults or UIAppearance.
@MainActor
enum InspectorTheme {
    static let canvas = pair(0xF5F6FA, 0x131720)
    static let surface = pair(0xFFFFFF, 0x1D2330)
    static let accent = pair(0x5146D8, 0xB4ADFF)
    static let secondary = pair(0x626978, 0xAEB7C7)
    static let tint = pair(0xEEECFF, 0x302C48)
    private static func pair(_ light: UInt32, _ dark: UInt32) -> UIColor {
        UIColor { traits in
            let value = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat((value >> 16) & 255) / 255, green: CGFloat((value >> 8) & 255) / 255,
                           blue: CGFloat(value & 255) / 255, alpha: 1)
        }
    }
    static func apply(_ controller: UIViewController) {
        let appearance: UIUserInterfaceStyle = InspectorPreferences.shared.appearance == .dark ? .dark : .light
        if controller.overrideUserInterfaceStyle != appearance { controller.overrideUserInterfaceStyle = appearance }
        controller.view.tintColor = accent
        controller.view.backgroundColor = canvas
        if let table = (controller as? UITableViewController)?.tableView {
            table.backgroundColor = canvas; table.separatorColor = .separator
        }
    }
    static func label(_ style: UIFont.TextStyle = .body, secondary: Bool = false) -> UILabel {
        let label = UILabel()
        label.font = .preferredFont(forTextStyle: style); label.adjustsFontForContentSizeCategory = true
        label.textColor = secondary ? Self.secondary : .label; label.numberOfLines = 0
        return label
    }
    static func empty(_ key: String) -> UILabel {
        let label = label(.body, secondary: true)
        label.text = InspectorLocalization.text(key); label.textAlignment = .center
        return label
    }
}

/// Retained by its owner, with a weak callback. Presentation updates preserve controller state.
@MainActor
final class InspectorStyleObserver: NSObject {
    private let update: () -> Void
    init(update: @escaping () -> Void) {
        self.update = update; super.init()
        NotificationCenter.default.addObserver(self, selector: #selector(changed), name: InspectorPreferences.didChange, object: nil)
    }
    deinit { NotificationCenter.default.removeObserver(self) }
    @objc func changed() { update() }
}

@MainActor
final class InspectorNavigationController: UINavigationController, UINavigationControllerDelegate {
    private lazy var style = InspectorStyleObserver { [weak self] in self?.updateStyle() }
    override func viewDidLoad() { super.viewDidLoad(); delegate = self; style.changed() }
    func navigationController(_ navigationController: UINavigationController, willShow viewController: UIViewController, animated: Bool) {
        tintItems(viewController)
    }
    private func tintItems(_ controller: UIViewController) {
        let items = (controller.navigationItem.leftBarButtonItems ?? []) + (controller.navigationItem.rightBarButtonItems ?? [])
        for item in items { item.tintColor = InspectorTheme.accent }
        controller.navigationItem.backBarButtonItem = UIBarButtonItem(title: InspectorLocalization.text("ui_back"), style: .plain, target: nil, action: nil)
    }
    private func updateStyle() {
        InspectorTheme.apply(self)
        let appearance = UINavigationBarAppearance(); appearance.configureWithOpaqueBackground()
        appearance.backgroundColor = InspectorTheme.surface
        appearance.titleTextAttributes = [.foregroundColor: UIColor.label]
        navigationBar.standardAppearance = appearance; navigationBar.scrollEdgeAppearance = appearance
        navigationBar.compactAppearance = appearance; navigationBar.tintColor = InspectorTheme.accent
        navigationBar.prefersLargeTitles = false
        for controller in viewControllers {
            tintItems(controller)
        }
    }
}

@MainActor
extension UIViewController {
    func inspectorPush(_ controller: UIViewController) {
        view.endEditing(true)
        navigationItem.backBarButtonItem = UIBarButtonItem(title: InspectorLocalization.text("ui_back"), style: .plain, target: nil, action: nil)
        if let navigationController { navigationController.pushViewController(controller, animated: true) }
        else { present(InspectorNavigationController(rootViewController: controller), animated: true) }
    }
    func inspectorNotice(_ message: String, title: String? = nil) {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: InspectorLocalization.text("ok"), style: .default))
        alert.overrideUserInterfaceStyle = overrideUserInterfaceStyle
        present(alert, animated: true)
    }
    func inspectorConfirm(_ title: String, message: String, action: @escaping () -> Void) {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: InspectorLocalization.text("archive_cancel"), style: .cancel))
        alert.addAction(UIAlertAction(title: title, style: .destructive) { _ in action() })
        alert.overrideUserInterfaceStyle = overrideUserInterfaceStyle
        present(alert, animated: true)
    }
}
#endif

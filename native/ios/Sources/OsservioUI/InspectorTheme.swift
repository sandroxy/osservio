#if canImport(UIKit)
import UIKit

/// SDK-owned colors and presentation. Never changes a window, host defaults or UIAppearance.
@MainActor
enum InspectorTheme {
    static let canvas = pair(0xF5F6FA, 0x131720)
    static let surface = pair(0xFFFFFF, 0x1D2330)
    static var accent: UIColor {
        let colors = InspectorPreferences.shared.palette.colors; return pair(colors.light, colors.dark)
    }
    static var filledAccent: UIColor {
        let colors = InspectorPreferences.shared.palette.colors; return pair(colors.light, colors.light)
    }
    static let secondary = pair(0x626978, 0xAEB7C7)
    static var tint: UIColor {
        let colors = InspectorPreferences.shared.palette.colors; return pair(colors.lightTint, colors.darkTint)
    }
    static let jsonString = pair(0x26734F, 0x8BDDAE)
    static let jsonNumber = pair(0x215FAD, 0x98C6FF)
    static let jsonLiteral = pair(0x934625, 0xF0B38D)
    static func pair(_ light: UInt32, _ dark: UInt32) -> UIColor {
        UIColor { traits in
            let value = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat((value >> 16) & 255) / 255, green: CGFloat((value >> 8) & 255) / 255,
                           blue: CGFloat(value & 255) / 255, alpha: 1)
        }
    }
    static func apply(_ controller: UIViewController) {
        let appearance: UIUserInterfaceStyle = InspectorPreferences.shared.appearance == .dark ? .dark : .light
        if controller.overrideUserInterfaceStyle != appearance { controller.overrideUserInterfaceStyle = appearance }
        InspectorKeyboardDismissal.install(in: controller.view)
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
    var transientPanel = false
    private var discardRequested = false
    func discardPanel() {
        discardRequested = true
        if isBeingPresented, let transitionCoordinator,
           transitionCoordinator.animate(alongsideTransition: nil, completion: { [weak self] _ in self?.finishDiscard() }) { return }
        finishDiscard()
    }
    private func finishDiscard() {
        guard discardRequested, !isBeingPresented, !isBeingDismissed else { return }
        presentingViewController?.dismiss(animated: false)
    }
    override func viewDidAppear(_ animated: Bool) { super.viewDidAppear(animated); finishDiscard() }
    var visiblePageChanged: (() -> Void)?
    weak var session: InspectorSession? {
        didSet { for controller in viewControllers { installSessionAction(on: controller) } }
    }
    private lazy var style = InspectorStyleObserver { [weak self] in self?.updateStyle() }
    override func viewDidLoad() { super.viewDidLoad(); delegate = self; style.changed() }
    func navigationController(_ navigationController: UINavigationController, willShow viewController: UIViewController, animated: Bool) {
        installSessionAction(on: viewController)
        tintItems(viewController)
        visiblePageChanged?()
    }
    private func installSessionAction(on controller: UIViewController) {
        let actions = controller.inspectorActions
        controller.inspectorActions = actions
    }
    func minimizeItem() -> UIBarButtonItem {
        let item = UIBarButtonItem(image: UIImage(systemName: "arrow.down.right.and.arrow.up.left"), style: .plain, target: self, action: #selector(minimize))
        item.accessibilityIdentifier = "Osservio.Minimize"
        item.accessibilityLabel = InspectorLocalization.text("ui_minimize")
        return item
    }
    @objc private func minimize() { session?.collapse() }
    private func tintItems(_ controller: UIViewController) {
        let items = (controller.navigationItem.leftBarButtonItems ?? []) + (controller.navigationItem.rightBarButtonItems ?? [])
        for item in items {
            item.tintColor = InspectorTheme.accent
            // Modern done buttons use tint as a solid fill behind a white title.
            if #available(iOS 26, *), item.style == .done { item.tintColor = InspectorTheme.filledAccent }
        }
        controller.inspectorMinimalBackTitle()
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
            installSessionAction(on: controller)
            tintItems(controller)
        }
    }
}

@MainActor
extension UIViewController {
    var inspectorSession: InspectorSession? {
        (navigationController as? InspectorNavigationController)?.session
            ?? (self as? InspectorViewController)?.session
            ?? (self as? InspectorNavigationController)?.session
    }
    /// Page-owned actions remain separate from the session's stable minimize affordance.
    var inspectorActions: [UIBarButtonItem]? {
        get { InspectorNavigationActions.state(for: self).items }
        set {
            let state = InspectorNavigationActions.state(for: self)
            state.items = newValue?.filter { $0.accessibilityIdentifier != "Osservio.Minimize" } ?? []
            state.render()
        }
    }
    var inspectorPrimaryAction: UIBarButtonItem? {
        get { inspectorActions?.first }
        set { inspectorActions = newValue.map { [$0] } }
    }
    func inspectorPresent(_ controller: UIViewController, retainingPage: Bool = false) {
        view.endEditing(true)
        let navigation = InspectorNavigationController(rootViewController: controller)
        navigation.overrideUserInterfaceStyle = InspectorPreferences.shared.appearance == .dark ? .dark : .light
        navigation.transientPanel = !retainingPage
        navigation.session = inspectorSession
        navigation.modalPresentationStyle = retainingPage ? .fullScreen : .pageSheet
        if #available(iOS 15, *), !retainingPage, let sheet = navigation.sheetPresentationController {
            sheet.detents = [.large()]; sheet.prefersGrabberVisible = true; sheet.preferredCornerRadius = 28
        }
        present(navigation, animated: true)
    }
    func inspectorMinimalBackTitle() {
        if #available(iOS 14, *) { navigationItem.backButtonDisplayMode = .minimal }
        else { navigationItem.backBarButtonItem = UIBarButtonItem(title: "", style: .plain, target: nil, action: nil) }
    }
    func inspectorPush(_ controller: UIViewController) {
        view.endEditing(true)
        inspectorMinimalBackTitle()
        if let navigationController { navigationController.pushViewController(controller, animated: true) }
        else { inspectorPresent(controller, retainingPage: true) }
    }
    func inspectorNotice(_ message: String, title: String? = nil) {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: InspectorLocalization.text("ok"), style: .default))
        alert.view.accessibilityIdentifier = "Osservio.TransientNotice"
        alert.overrideUserInterfaceStyle = overrideUserInterfaceStyle
        present(alert, animated: true)
    }
    func inspectorConfirm(_ title: String, message: String, confirm: String? = nil, cancel: String? = nil, action: @escaping () -> Void) {
        guard presentedViewController == nil else { return }
        view.endEditing(true)
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: cancel ?? InspectorLocalization.text("archive_cancel"), style: .cancel))
        alert.addAction(UIAlertAction(title: confirm ?? title, style: .destructive) { _ in action() })
        alert.view.accessibilityIdentifier = "Osservio.TransientNotice"
        alert.view.tintColor = InspectorTheme.accent
        alert.overrideUserInterfaceStyle = overrideUserInterfaceStyle
        present(alert, animated: true)
    }
}
#endif

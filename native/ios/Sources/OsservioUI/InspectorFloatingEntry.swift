#if canImport(UIKit)
import UIKit

struct InspectorDockPosition {
    var trailing: Bool
    var fraction: CGFloat
    func center(in rect: CGRect) -> CGPoint {
        CGPoint(x: trailing ? rect.maxX : rect.minX, y: rect.minY + rect.height * min(1, max(0, fraction)))
    }
    static func nearest(to point: CGPoint, in rect: CGRect) -> Self {
        Self(trailing: point.x >= rect.midX, fraction: rect.height > 0 ? min(1, max(0, (point.y - rect.minY) / rect.height)) : 0)
    }
}

@MainActor
final class InspectorEntryViewController: UIViewController {
    var onOpen: (() -> Void)?
    var onHide: (() -> Void)?
    let entry = InspectorFloatingEntry()
    private var dock = InspectorDockPosition(trailing: true, fraction: 0.35)
    private var keyboardFrame: CGRect?
    private var dragging = false
    private lazy var style = InspectorStyleObserver { [weak self] in self?.updateStyle() }
    override func viewDidLoad() {
        super.viewDidLoad(); view.backgroundColor = .clear
        if let stored = UserDefaults.standard.object(forKey: "osservio.ui.entryFraction") as? Double, stored.isFinite {
            dock.fraction = CGFloat(min(1, max(0, stored)))
        }
        dock.trailing = UserDefaults.standard.string(forKey: "osservio.ui.entrySide") != "leading"
        view.addSubview(entry); entry.addTarget(self, action: #selector(open), for: .touchUpInside)
        entry.addGestureRecognizer(UIPanGestureRecognizer(target: self, action: #selector(drag(_:))))
        NotificationCenter.default.addObserver(self, selector: #selector(keyboard(_:)), name: UIResponder.keyboardWillChangeFrameNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(keyboardHidden), name: UIResponder.keyboardWillHideNotification, object: nil)
        style.changed()
    }
    deinit { NotificationCenter.default.removeObserver(self) }
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        if !dragging { entry.center = dock.center(in: available) }
    }
    private var available: CGRect {
        var bounds = view.bounds.inset(by: view.safeAreaInsets)
        if let keyboardFrame, let window = view.window {
            let keyboard = view.convert(window.convert(keyboardFrame, from: nil), from: window)
            if keyboard.intersects(bounds), keyboard.maxY >= bounds.maxY - 1 { bounds.size.height = max(0, keyboard.minY - bounds.minY) }
        }
        let inset: CGFloat = 32
        return CGRect(x: bounds.minX + min(inset, bounds.width / 2), y: bounds.minY + min(inset, bounds.height / 2),
                      width: max(0, bounds.width - inset * 2), height: max(0, bounds.height - inset * 2))
    }
    private func updateStyle() {
        overrideUserInterfaceStyle = InspectorPreferences.shared.appearance == .dark ? .dark : .light
        entry.accessibilityLabel = InspectorLocalization.text("ui_open_inspector")
        entry.accessibilityHint = InspectorLocalization.text("ui_entry_hint")
        entry.refreshAppearance()
        entry.accessibilityCustomActions = [
            UIAccessibilityCustomAction(name: InspectorLocalization.text("ui_dock_leading"), target: self, selector: #selector(dockLeading)),
            UIAccessibilityCustomAction(name: InspectorLocalization.text("ui_dock_trailing"), target: self, selector: #selector(dockTrailing)),
            UIAccessibilityCustomAction(name: InspectorLocalization.text("ui_hide_entry"), target: self, selector: #selector(hide))
        ]
    }
    @objc private func open() { onOpen?() }
    @objc private func hide() -> Bool { onHide?(); return true }
    @objc private func dockLeading() -> Bool { dock.trailing = false; settle(); return true }
    @objc private func dockTrailing() -> Bool { dock.trailing = true; settle(); return true }
    @objc private func drag(_ pan: UIPanGestureRecognizer) {
        switch pan.state {
        case .began:
            dragging = true
            if let position = entry.layer.presentation()?.position { entry.center = position }
            entry.layer.removeAllAnimations(); pan.setTranslation(.zero, in: view)
        case .changed:
            let translation = pan.translation(in: view), rect = available
            entry.center = CGPoint(x: min(rect.maxX, max(rect.minX, entry.center.x + translation.x)),
                                   y: min(rect.maxY, max(rect.minY, entry.center.y + translation.y)))
            pan.setTranslation(.zero, in: view)
        case .ended, .cancelled:
            dragging = false; dock = .nearest(to: entry.center, in: available); settle()
        default: break
        }
    }
    private func settle() {
        UserDefaults.standard.set(dock.trailing ? "trailing" : "leading", forKey: "osservio.ui.entrySide")
        UserDefaults.standard.set(Double(dock.fraction), forKey: "osservio.ui.entryFraction")
        UIView.animate(withDuration: InspectorMotion.duration, delay: 0, options: [.beginFromCurrentState, .allowUserInteraction]) {
            self.entry.center = self.dock.center(in: self.available)
        }
    }
    @objc private func keyboard(_ notification: Notification) {
        keyboardFrame = notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect
        view.setNeedsLayout()
    }
    @objc private func keyboardHidden() { keyboardFrame = nil; view.setNeedsLayout() }
}

@MainActor
final class InspectorFloatingEntry: UIControl {
    private let image = UIImageView(image: UIImage(systemName: "viewfinder"))
    private var material: UIVisualEffectView?
    override init(frame: CGRect) {
        super.init(frame: CGRect(origin: .zero, size: CGSize(width: 48, height: 48)))
        isAccessibilityElement = true; accessibilityTraits = .button; accessibilityIdentifier = "Osservio.Entry"
        layer.cornerRadius = 24; layer.cornerCurve = .continuous
        layer.shadowColor = UIColor.black.cgColor; layer.shadowOpacity = 0.16; layer.shadowRadius = 9; layer.shadowOffset = CGSize(width: 0, height: 4)
        image.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 22, weight: .medium)
        image.contentMode = .center; image.isUserInteractionEnabled = false
        addSubview(image); refreshAppearance()
        NotificationCenter.default.addObserver(self, selector: #selector(refreshAppearance), name: UIAccessibility.reduceTransparencyStatusDidChangeNotification, object: nil)
    }
    @available(*, unavailable) required init?(coder: NSCoder) { return nil }
    deinit { NotificationCenter.default.removeObserver(self) }
    override func layoutSubviews() {
        super.layoutSubviews(); image.frame = bounds; material?.frame = bounds
        layer.shadowPath = UIBezierPath(ovalIn: bounds).cgPath
    }
    override var isHighlighted: Bool {
        didSet { image.alpha = isHighlighted ? 0.55 : 1 }
    }
    @objc func refreshAppearance() {
        material?.removeFromSuperview(); material = nil
        backgroundColor = InspectorTheme.surface; image.tintColor = InspectorTheme.accent
        if !UIAccessibility.isReduceTransparencyEnabled {
            let effect: UIVisualEffect
            #if compiler(>=6.2)
            if #available(iOS 26, *) {
                let glass = UIGlassEffect(style: .regular); glass.isInteractive = true; effect = glass
            } else { effect = UIBlurEffect(style: .systemMaterial) }
            #else
            effect = UIBlurEffect(style: .systemMaterial)
            #endif
            let view = UIVisualEffectView(effect: effect); view.isUserInteractionEnabled = false
            view.layer.cornerRadius = 24; view.layer.cornerCurve = .continuous; view.clipsToBounds = true
            insertSubview(view, at: 0); material = view; backgroundColor = .clear
        }
        setNeedsLayout()
    }
}
#endif

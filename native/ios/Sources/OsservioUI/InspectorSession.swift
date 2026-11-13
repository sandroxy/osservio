#if canImport(UIKit)
import UIKit

/// An app-local inspection session owned by one host window. Recorders and archives
/// retain their independent lifetimes. The entry starts hidden; the inspector is lazy.
/// Collapse/hide retain in-process reading and editing state. Dispose releases it.
@MainActor
public final class InspectorSession: NSObject {
    public enum Presentation: Equatable { case hidden, entry, expanded }
    public private(set) var presentation: Presentation = .hidden
    public private(set) var isDisposed = false
    private weak var hostWindow: UIWindow?
    private weak var returnWindow: UIWindow?
    private var makeInspector: (() -> InspectorViewController)?
    private var entryWindow: InspectorEntryWindow?
    private var contentWindow: UIWindow?
    private var inspector: InspectorViewController?
    private var suspended = false
    private var creatingInspector = false
    private let transition = InspectorEntryTransition()

    public init(window: UIWindow, makeInspector: @escaping () -> InspectorViewController) {
        hostWindow = window; self.makeInspector = makeInspector
        suspended = UIApplication.shared.applicationState == .background
        super.init()
        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(background), name: UIApplication.didEnterBackgroundNotification, object: nil)
        center.addObserver(self, selector: #selector(foreground), name: UIApplication.didBecomeActiveNotification, object: nil)
        center.addObserver(self, selector: #selector(sceneHidden(_:)), name: UIScene.didEnterBackgroundNotification, object: nil)
        center.addObserver(self, selector: #selector(sceneShown(_:)), name: UIScene.didActivateNotification, object: nil)
        center.addObserver(self, selector: #selector(sceneDisconnected(_:)), name: UIScene.didDisconnectNotification, object: nil)
        center.addObserver(self, selector: #selector(hostVisibilityChanged(_:)), name: UIWindow.didBecomeVisibleNotification, object: nil)
        center.addObserver(self, selector: #selector(hostVisibilityChanged(_:)), name: UIWindow.didBecomeHiddenNotification, object: nil)
    }
    deinit {
        NotificationCenter.default.removeObserver(self)
        // A host may release its owner without explicitly disposing. UIKit cleanup
        // must still run on the main actor, including when the last release did not.
        let content = contentWindow, entry = entryWindow, previous = returnWindow, host = hostWindow, animation = transition
        Task { @MainActor in
            animation.cancel()
            if content?.isKeyWindow == true, let target = previous ?? host, !target.isHidden { target.makeKey() }
            content?.isHidden = true; entry?.isHidden = true
            content?.rootViewController = nil; entry?.rootViewController = nil
        }
    }

    /// Reveals the entry without replacing an expanded inspector. False means disposed or no host.
    @discardableResult public func showEntry() -> Bool {
        guard available else { return false }
        if presentation == .hidden { presentation = .entry }
        reconcile(animated: false); return true
    }
    /// Opens or restores this session. Repeated calls never create another inspector.
    @discardableResult public func expand() -> Bool {
        let span = InspectorPerformance.begin(.open); defer { span?.end() }
        guard available else { return false }
        guard presentation != .expanded else { return true }
        presentation = .expanded; reconcile(animated: true); return !isDisposed
    }
    /// Returns to the host without applying a filter draft or saving an unfinished problem.
    public func collapse() {
        let span = InspectorPerformance.begin(.collapse); defer { span?.end() }
        guard !isDisposed, presentation == .expanded else { return }
        endEditing(); discardTransientPanels(); presentation = .entry; reconcile(animated: true)
    }
    /// Hides both surfaces without stopping capture or discarding the session.
    public func hideEntry() {
        guard !isDisposed else { return }
        endEditing(); discardTransientPanels(); presentation = .hidden; reconcile(animated: false)
    }
    /// Releases presentation state. Does not stop recorders or delete retained evidence.
    public func dispose() {
        guard !isDisposed else { return }
        isDisposed = true; presentation = .hidden; endEditing(); cancelAnimation()
        if let contentWindow { NotificationCenter.default.post(name: Self.willCollapse, object: contentWindow) }
        inspector?.dismiss(animated: false)
        restoreHostFocus(); contentWindow?.isHidden = true; entryWindow?.isHidden = true
        inspector?.session = nil
        contentWindow?.rootViewController = nil; entryWindow?.rootViewController = nil
        inspector = nil; contentWindow = nil; entryWindow = nil; makeInspector = nil; hostWindow = nil
        NotificationCenter.default.removeObserver(self)
    }

    private var available: Bool { !isDisposed && hostWindow != nil }
    private func reconcile(animated: Bool) {
        cancelAnimation()
        guard let hostWindow, !hostWindow.isHidden, !suspended, !isDisposed else {
            restoreHostFocus(); contentWindow?.isHidden = true; entryWindow?.isHidden = true; return
        }
        if presentation == .expanded {
            ensureEntry(host: hostWindow)
            if inspector == nil, let makeInspector {
                guard !creatingInspector else { return }
                creatingInspector = true
                defer { creatingInspector = false }
                let controller = makeInspector(); controller.session = self
                guard available else { controller.session = nil; return }
                let window = makeWindow(host: hostWindow)
                window.rootViewController = controller
                inspector = controller; contentWindow = window; self.makeInspector = nil
                if presentation != .expanded { reconcile(animated: false); return }
            }
            guard let window = contentWindow else { return }
            let opening = window.isHidden
            if !window.isKeyWindow {
                returnWindow = hostWindow.windowScene?.windows.first { $0.isKeyWindow && $0 !== window && $0 !== entryWindow } ?? hostWindow
                window.makeKeyAndVisible()
            }
            if opening { animate(window, opening: true, enabled: animated) }
            NotificationCenter.default.post(name: Self.willExpand, object: window)
            entryWindow?.isHidden = true
        } else {
            if presentation == .entry { ensureEntry(host: hostWindow) }
            entryWindow?.isHidden = presentation != .entry
            if let window = contentWindow, !window.isHidden {
                // Suspend queries before the visual exit without destroying the navigation stack.
                NotificationCenter.default.post(name: Self.willCollapse, object: window)
                restoreHostFocus()
                animate(window, opening: false, enabled: animated)
            }
        }
    }
    private func makeWindow(host: UIWindow) -> UIWindow {
        let window = host.windowScene.map(InspectorContentWindow.init(windowScene:)) ?? InspectorContentWindow(frame: host.frame)
        window.frame = host.frame; window.windowLevel = host.windowLevel + 1
        window.backgroundColor = .clear
        window.geometryChanged = { [weak self] in
            guard let self, self.transition.isRunning else { return }
            self.reconcile(animated: false)
        }
        return window
    }
    private func ensureEntry(host: UIWindow) {
        guard entryWindow == nil else { return }
        let window = host.windowScene.map(InspectorEntryWindow.init(windowScene:)) ?? InspectorEntryWindow(frame: host.frame)
        window.frame = host.frame; window.windowLevel = host.windowLevel + 1; window.backgroundColor = .clear
        let controller = InspectorEntryViewController()
        controller.onOpen = { [weak self] in self?.expand() }
        controller.onHide = { [weak self] in self?.hideEntry() }
        window.rootViewController = controller; entryWindow = window
        controller.loadViewIfNeeded(); controller.view.frame = window.bounds; controller.view.layoutIfNeeded()
    }
    private func animate(_ window: UIWindow, opening: Bool, enabled: Bool) {
        entryWindow?.layoutIfNeeded()
        let entry = (entryWindow?.rootViewController as? InspectorEntryViewController)?.entry
        let origin = entry.map { $0.convert($0.bounds, to: window) }
            ?? CGRect(x: window.bounds.maxX - 56, y: window.bounds.height * 0.35, width: 48, height: 48)
        transition.run(window: window, entryWindow: entryWindow, origin: origin, opening: opening, animated: enabled)
    }
    private func cancelAnimation() { transition.cancel() }
    private func discardTransientPanels() {
        var current: UIViewController? = inspector
        while let presented = current?.presentedViewController {
            if let navigation = presented as? InspectorNavigationController, navigation.transientPanel {
                navigation.discardPanel(); return
            }
            if presented.viewIfLoaded?.accessibilityIdentifier == "Osservio.TransientNotice" {
                presented.dismiss(animated: false); return
            }
            current = presented
        }
    }
    private func endEditing() { contentWindow?.endEditing(true) }
    private func restoreHostFocus() {
        guard contentWindow?.isKeyWindow == true else { return }
        if let returnWindow, !returnWindow.isHidden { returnWindow.makeKey() }
        else if let hostWindow, !hostWindow.isHidden { hostWindow.makeKey() }
        returnWindow = nil
    }
    @objc private func background() { suspended = true; endEditing(); reconcile(animated: false) }
    @objc private func foreground() {
        // A different scene becoming active must not reveal this scene's windows.
        suspended = hostWindow?.windowScene.map { $0.activationState != .foregroundActive } ?? false
        reconcile(animated: false)
    }
    @objc private func sceneHidden(_ notification: Notification) {
        guard let scene = notification.object as? UIScene, scene === hostWindow?.windowScene else { return }
        background()
    }
    @objc private func sceneShown(_ notification: Notification) {
        guard let scene = notification.object as? UIScene, scene === hostWindow?.windowScene else { return }
        foreground()
    }
    @objc private func sceneDisconnected(_ notification: Notification) {
        guard let scene = notification.object as? UIScene, scene === hostWindow?.windowScene else { return }
        dispose()
    }
    @objc private func hostVisibilityChanged(_ notification: Notification) {
        if notification.object as? UIWindow === hostWindow { reconcile(animated: false) }
    }
    static let willCollapse = Notification.Name("com.sandrox.osservio.inspector.willCollapse")
    static let willExpand = Notification.Name("com.sandrox.osservio.inspector.willExpand")
}

@MainActor
enum InspectorMotion {
    static var reduced: Bool {
        UIAccessibility.isReduceMotionEnabled || ProcessInfo.processInfo.isLowPowerModeEnabled
            || ProcessInfo.processInfo.thermalState == .serious || ProcessInfo.processInfo.thermalState == .critical
    }
    static var duration: TimeInterval { reduced ? 0.12 : 0.22 }
}

/// Only the visible entry consumes touches; the rest of the app remains interactive.
@MainActor
final class InspectorEntryWindow: UIWindow {
    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        let target = super.hitTest(point, with: event)
        return target === rootViewController?.view || target === self ? nil : target
    }
}
#endif

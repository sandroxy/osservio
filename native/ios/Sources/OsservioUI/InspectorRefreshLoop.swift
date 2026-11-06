#if canImport(UIKit)
import UIKit

/// Owns visible-list polling only. Capture and retained evidence have independent lifetimes.
@MainActor
final class InspectorRefreshLoop: NSObject {
    private weak var view: UIView?
    private weak var suspendedScene: UIScene?
    private let refresh: () -> Void
    private var visible = false
    private var applicationSuspended = false
    private var timer: Timer?
    private var generation: UInt64 = 0

    init(refresh: @escaping () -> Void) {
        self.refresh = refresh
        super.init()
        let center = NotificationCenter.default
        for name in [UIApplication.willResignActiveNotification, UIApplication.didEnterBackgroundNotification] {
            center.addObserver(self, selector: #selector(applicationDeactivated), name: name, object: nil)
        }
        center.addObserver(self, selector: #selector(applicationActivated), name: UIApplication.didBecomeActiveNotification, object: nil)
        for name in [UIScene.willDeactivateNotification, UIScene.didEnterBackgroundNotification, UIScene.didDisconnectNotification] {
            center.addObserver(self, selector: #selector(sceneDeactivated(_:)), name: name, object: nil)
        }
        center.addObserver(self, selector: #selector(sceneActivated(_:)), name: UIScene.didActivateNotification, object: nil)
        for name in [UIWindow.didBecomeVisibleNotification, UIWindow.didBecomeHiddenNotification] {
            center.addObserver(self, selector: #selector(windowVisibilityChanged(_:)), name: name, object: nil)
        }
    }
    deinit { timer?.invalidate(); NotificationCenter.default.removeObserver(self) }

    /// Idempotent across will/did-appear. The latter handles initial attachment to a window.
    func show(in view: UIView) {
        self.view = view; visible = true
        reconcile()
    }
    func hide() {
        visible = false; view = nil; suspendedScene = nil
        stopTimer()
    }
    private var mayRefresh: Bool {
        guard visible, !applicationSuspended, let window = view?.window, !window.isHidden else { return false }
        if let scene = window.windowScene {
            return scene !== suspendedScene && scene.activationState == .foregroundActive
        }
        return UIApplication.shared.applicationState == .active
    }
    private func reconcile() {
        guard mayRefresh else { stopTimer(); return }
        guard timer == nil else { return }
        generation &+= 1
        let current = generation
        timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, self.generation == current, self.timer != nil else { return }
                guard self.mayRefresh else { self.stopTimer(); return }
                self.refresh()
            }
        }
        refresh()
    }
    private func stopTimer() {
        generation &+= 1
        timer?.invalidate(); timer = nil
    }
    @objc private func applicationDeactivated() {
        // "Will" notifications precede the state transition. Stop immediately instead of rereading the old state.
        applicationSuspended = true
        stopTimer()
    }
    @objc private func applicationActivated() {
        applicationSuspended = false
        reconcile()
    }
    @objc private func sceneDeactivated(_ notification: Notification) {
        guard let scene = notification.object as? UIScene, scene === view?.window?.windowScene else { return }
        suspendedScene = scene
        stopTimer()
    }
    @objc private func sceneActivated(_ notification: Notification) {
        guard let scene = notification.object as? UIScene, scene === view?.window?.windowScene else { return }
        suspendedScene = nil
        reconcile()
    }
    @objc private func windowVisibilityChanged(_ notification: Notification) {
        guard let window = notification.object as? UIWindow, window === view?.window else { return }
        reconcile()
    }
}
#endif

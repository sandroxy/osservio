#if canImport(UIKit)
import OsservioCore
import UIKit

/// Owns preparation and presentation, not the host's exporter or completed file retention.
@MainActor
final class IncidentSharePresentation: NSObject {
    private weak var owner: UIViewController?
    private let exporter: any IncidentExporter
    private let snapshot: IncidentSnapshot
    private let notice: (String?) -> Void
    private var task: (any IncidentExportTask)?
    private var preparing = false
    private var generation: UInt64 = 0
    lazy var button: UIBarButtonItem = {
        let item = UIBarButtonItem(image: UIImage(systemName: "square.and.arrow.up"), style: .plain,
            target: self, action: #selector(shareOrCancel))
        item.accessibilityLabel = RuntimePresentation.text("incident_share")
        return item
    }()

    init(owner: UIViewController, snapshot: IncidentSnapshot, exporter: any IncidentExporter, notice: @escaping (String?) -> Void) {
        self.owner = owner; self.snapshot = snapshot; self.exporter = exporter; self.notice = notice
        super.init()
        NotificationCenter.default.addObserver(self, selector: #selector(cancel), name: UIApplication.willResignActiveNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(sceneDeactivated(_:)), name: UIScene.willDeactivateNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(presentationHidden(_:)), name: InspectorSession.willCollapse, object: nil)
    }
    deinit { task?.cancel(); NotificationCenter.default.removeObserver(self) }

    @objc private func shareOrCancel() {
        if preparing { cancel(); return }
        guard let owner, visible(), owner.presentedViewController == nil else { return }
        generation &+= 1; let request = generation; preparing = true
        button.image = nil; button.title = RuntimePresentation.text("archive_cancel")
        button.accessibilityLabel = RuntimePresentation.text("incident_export_cancel")
        notice(RuntimePresentation.text("incident_export_preparing"))
        task = exporter.prepare(snapshot) { [weak self] result in
            Task { @MainActor [weak self] in self?.finished(request: request, result: result) }
        }
    }
    private func finished(request: UInt64, result: Result<IncidentExportFile, IncidentExportError>) {
        guard generation == request, preparing else { return }
        task = nil; preparing = false; restoreButton()
        guard let owner, visible(), owner.presentedViewController == nil else { notice(nil); return }
        switch result {
        case .success(let file):
            notice(nil)
            let sharing = UIActivityViewController(activityItems: [file.url], applicationActivities: nil)
            sharing.popoverPresentationController?.barButtonItem = button
            owner.present(sharing, animated: true)
        case .failure(.cancelled): notice(nil)
        case .failure(let error): notice(RuntimePresentation.text(Self.message(error)))
        }
    }
    @objc func cancel() {
        generation &+= 1; task?.cancel(); task = nil
        if preparing { preparing = false; restoreButton(); notice(nil) }
    }
    private func restoreButton() {
        button.title = nil; button.image = UIImage(systemName: "square.and.arrow.up")
        button.accessibilityLabel = RuntimePresentation.text("incident_share")
    }
    @objc private func sceneDeactivated(_ notification: Notification) {
        guard let scene = notification.object as? UIScene, scene === owner?.viewIfLoaded?.window?.windowScene else { return }
        cancel()
    }
    @objc private func presentationHidden(_ notification: Notification) {
        guard let window = notification.object as? UIWindow, window === owner?.viewIfLoaded?.window else { return }
        cancel()
    }
    private func visible() -> Bool {
        guard let owner, let window = owner.viewIfLoaded?.window, !window.isHidden,
              owner.navigationController == nil || owner.navigationController?.topViewController === owner else { return false }
        if let scene = window.windowScene { return scene.activationState == .foregroundActive }
        return UIApplication.shared.applicationState == .active
    }
    private static func message(_ error: IncidentExportError) -> String {
        switch error {
        case .busy: return "incident_export_busy"
        case .closed: return "incident_export_closed"
        case .tooLarge: return "incident_export_too_large"
        case .storageFull: return "incident_export_storage_full"
        case .invalidRecord: return "incident_export_invalid"
        case .cancelled, .unavailable: return "incident_export_unavailable"
        }
    }
}
#endif

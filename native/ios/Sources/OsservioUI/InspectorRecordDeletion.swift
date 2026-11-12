#if canImport(UIKit)
import OsservioCore
import UIKit

/// A saved detail owns its deletion delivery; leaving it invalidates UI callbacks.
@MainActor
final class InspectorRecordDeletion: NSObject {
    typealias Completion = @Sendable (Result<Void, PersistenceError>) -> Void
    private weak var owner: UIViewController?
    private let label: String, confirmation: String
    private let operation: (@escaping Completion) -> Void
    private let state: (Bool, String?) -> Void
    private var generation = 0
    private var busy = false
    lazy var button = inspectorDeleteItem(target: self, action: #selector(confirm), label: label)

    init(owner: UIViewController, label: String, confirmation: String,
         operation: @escaping (@escaping Completion) -> Void, state: @escaping (Bool, String?) -> Void) {
        self.owner = owner; self.label = label; self.confirmation = confirmation
        self.operation = operation; self.state = state
    }
    func relabel() { button.accessibilityLabel = RuntimePresentation.text(label) }
    @objc private func confirm() {
        guard !busy, let owner, owner.presentedViewController == nil else { return }
        let alert = UIAlertController(title: RuntimePresentation.text(label),
            message: RuntimePresentation.text(confirmation), preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: RuntimePresentation.text("archive_cancel"), style: .cancel))
        alert.addAction(UIAlertAction(title: RuntimePresentation.text("archive_delete_confirm"), style: .destructive) { [weak self] _ in self?.deleteConfirmed() })
        owner.present(alert, animated: true)
    }
    func deleteConfirmed() {
        guard !busy else { return }
        busy = true; generation += 1; let request = generation
        button.isEnabled = false; state(true, RuntimePresentation.text("archive_deleting"))
        operation { [weak self] result in
            Task { @MainActor [weak self] in
                guard let self, self.generation == request else { return }
                self.busy = false; self.button.isEnabled = true
                switch result {
                case .success:
                    self.state(false, nil)
                    if let owner = self.owner, owner.navigationController?.topViewController === owner {
                        owner.navigationController?.popViewController(animated: true)
                    }
                case .failure(let error): self.state(false, PersistencePresentation.error(error))
                }
            }
        }
    }
    func invalidate() {
        generation += 1
        if busy { busy = false; button.isEnabled = true; state(false, nil) }
    }
}
#endif

#if canImport(UIKit)
import UIKit

/// Only SDK-owned views install this gesture. The original touch still reaches its control.
@MainActor
final class InspectorKeyboardDismissal: UITapGestureRecognizer, UIGestureRecognizerDelegate {
    static func install(in view: UIView) {
        guard !(view.gestureRecognizers ?? []).contains(where: { $0 is InspectorKeyboardDismissal }) else { return }
        let gesture = InspectorKeyboardDismissal(); gesture.addTarget(gesture, action: #selector(dismissKeyboard))
        gesture.cancelsTouchesInView = false; gesture.delegate = gesture; view.addGestureRecognizer(gesture)
    }
    @objc private func dismissKeyboard() { view?.endEditing(true) }
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        var candidate = touch.view
        while let current = candidate {
            if current is UITextInput { return false }
            candidate = current.superview
        }
        return true
    }
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool { true }
}
#endif

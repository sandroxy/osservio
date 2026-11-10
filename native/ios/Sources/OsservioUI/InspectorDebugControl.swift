#if canImport(UIKit)
import OsservioCore
import UIKit

/// One control model for every root tab. Separate host controllers are reported honestly if a control is rejected.
@MainActor
final class InspectorDebugControl: NSObject {
    private let controllers: [CaptureController]
    private var buttons: [UIButton] = []
    private var lastPresentation: String?
    weak var presenter: UIViewController?

    init(controllers: [CaptureController]) {
        var unique: [CaptureController] = []
        for controller in controllers where !unique.contains(where: { $0 === controller }) { unique.append(controller) }
        self.controllers = unique
        super.init()
    }
    func item() -> UIBarButtonItem {
        let button = UIButton(type: .system)
        button.widthAnchor.constraint(equalToConstant: 44).isActive = true
        button.heightAnchor.constraint(equalToConstant: 44).isActive = true
        button.accessibilityIdentifier = "Osservio.Debug"
        button.addTarget(self, action: #selector(toggle), for: .touchUpInside)
        buttons.append(button); lastPresentation = nil; refresh()
        return UIBarButtonItem(customView: button)
    }
    func refresh() {
        let states = controllers.compactMap { $0.status() }
        let busy = states.count != controllers.count
        let enabled = !states.isEmpty && states.allSatisfy(\.debugEnabled)
        let partial = !enabled && states.contains(where: \.debugEnabled)
        let expired = !enabled && states.contains(where: \.debugExpired)
        let key = busy ? "busy" : partial ? "ui_debug_partial" : enabled ? "ui_debug_on" : expired ? "ui_debug_expired" : "ui_debug_off"
        var value = InspectorLocalization.text(key)
        if let remaining = states.filter(\.debugHasTimeLimit).map(\.remainingMillis).min() {
            value += " · " + String(format: InspectorLocalization.text("ui_debug_remaining"), (remaining + 999) / 1_000)
        }
        let label = InspectorLocalization.text(enabled ? "ui_debug_disable" : "ui_debug_enable")
        let signature = value + label + InspectorPreferences.shared.appearance.rawValue
        guard signature != lastPresentation else { return }; lastPresentation = signature
        for button in buttons {
            button.setImage(UIImage(systemName: enabled ? "ladybug.fill" : "ladybug"), for: .normal)
            button.backgroundColor = enabled ? InspectorTheme.accent : InspectorTheme.tint
            button.tintColor = enabled ? InspectorTheme.surface : InspectorTheme.accent
            button.layer.cornerRadius = 22
            button.isEnabled = !busy; button.isSelected = enabled
            button.accessibilityTraits = enabled ? [.button, .selected] : .button
            button.accessibilityLabel = label; button.accessibilityValue = value
            button.accessibilityHint = InspectorLocalization.text("ui_debug_hint")
        }
    }
    static func describe(_ status: CaptureStatus, includeBaseline: Bool = false) -> String {
        var value = InspectorLocalization.text(status.debugEnabled ? "ui_debug_on" : status.debugExpired ? "ui_debug_expired" : "ui_debug_off")
        if status.debugHasTimeLimit {
            value += " · " + String(format: InspectorLocalization.text("ui_debug_remaining"), (status.remainingMillis + 999) / 1_000)
        }
        if includeBaseline { value += " · " + InspectorLocalization.text(status.baselineEnabled ? "ui_baseline_on" : "ui_baseline_off") }
        return value
    }
    @objc private func toggle() {
        let states = controllers.compactMap { $0.status() }
        guard states.count == controllers.count else { presenter?.inspectorNotice(InspectorLocalization.text("busy")); refresh(); return }
        let enabled = !states.allSatisfy(\.debugEnabled)
        var failed = false
        for controller in controllers {
            do { try controller.setDebugEnabled(enabled) } catch { failed = true }
        }
        refresh()
        if failed { presenter?.inspectorNotice(InspectorLocalization.text("ui_debug_control_failed")) }
    }
}
#endif

#if canImport(UIKit)
import UIKit

/// Segment semantics with full-width choices at accessibility text sizes.
@MainActor
final class InspectorScopeControl: UIStackView {
    private let buttons: [UIButton]
    private let choices: InspectorControls
    var onSelect: ((Int) -> Void)?
    var selectedIndex = 0 { didSet { updateSelection() } }
    init(titles: [String]) {
        buttons = titles.map { title in
            let button = UIButton(type: .custom); button.setTitle(title, for: .normal)
            button.setTitleColor(InspectorTheme.secondary, for: .normal)
            button.setTitleColor(InspectorTheme.accent, for: .selected)
            button.setTitleColor(InspectorTheme.accent.withAlphaComponent(0.6), for: .highlighted)
            return button
        }
        choices = InspectorControls(buttons: buttons)
        super.init(frame: .zero); axis = .vertical; addArrangedSubview(choices)
        for (index, button) in buttons.enumerated() {
            button.tag = index; button.layer.cornerRadius = 10
            button.addTarget(self, action: #selector(selectChoice(_:)), for: .touchUpInside)
        }
        updateSelection()
    }
    @available(*, unavailable)
    required init(coder: NSCoder) { fatalError("Programmatic construction only") }
    func setTitles(_ titles: [String]) {
        precondition(titles.count == buttons.count)
        for (button, title) in zip(buttons, titles) { button.setTitle(title, for: .normal) }
    }
    @objc private func selectChoice(_ button: UIButton) { selectedIndex = button.tag; onSelect?(selectedIndex) }
    private func updateSelection() {
        for (index, button) in buttons.enumerated() {
            button.isSelected = index == selectedIndex
            button.backgroundColor = button.isSelected ? InspectorTheme.tint : InspectorTheme.surface
            button.accessibilityTraits = button.isSelected ? [.button, .selected] : .button
        }
    }
}
#endif

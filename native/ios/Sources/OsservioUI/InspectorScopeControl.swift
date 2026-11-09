#if canImport(UIKit)
import UIKit

/// Segment semantics with full-width choices at accessibility text sizes.
@MainActor
final class InspectorScopeControl: UIStackView {
    private let buttons: [UIButton]
    private let choices: InspectorControls
    private let segments: UISegmentedControl
    var onSelect: ((Int) -> Void)?
    var selectedIndex = 0 { didSet { updateSelection() } }
    init(titles: [String]) {
        segments = UISegmentedControl(items: titles)
        buttons = titles.map { title in
            let button = UIButton(type: .custom); button.setTitle(title, for: .normal)
            button.setTitleColor(InspectorTheme.secondary, for: .normal)
            button.setTitleColor(InspectorTheme.accent, for: .selected)
            button.setTitleColor(InspectorTheme.accent.withAlphaComponent(0.6), for: .highlighted)
            return button
        }
        choices = InspectorControls(buttons: buttons)
        super.init(frame: .zero); axis = .vertical
        segments.selectedSegmentTintColor = InspectorTheme.surface
        segments.backgroundColor = InspectorTheme.tint
        segments.setTitleTextAttributes([.foregroundColor: InspectorTheme.secondary], for: .normal)
        segments.setTitleTextAttributes([.foregroundColor: InspectorTheme.accent], for: .selected)
        segments.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        segments.addTarget(self, action: #selector(selectSegment), for: .valueChanged)
        addArrangedSubview(segments); addArrangedSubview(choices)
        for (index, button) in buttons.enumerated() {
            button.tag = index; button.layer.cornerRadius = 10
            button.addTarget(self, action: #selector(selectChoice(_:)), for: .touchUpInside)
        }
        updateLayout(); updateSelection()
    }
    @available(*, unavailable)
    required init(coder: NSCoder) { fatalError("Programmatic construction only") }
    func setTitles(_ titles: [String]) {
        precondition(titles.count == buttons.count)
        for (index, title) in titles.enumerated() {
            buttons[index].setTitle(title, for: .normal); segments.setTitle(title, forSegmentAt: index)
        }
    }
    @objc private func selectSegment() { selectedIndex = segments.selectedSegmentIndex; onSelect?(selectedIndex) }
    @objc private func selectChoice(_ button: UIButton) { selectedIndex = button.tag; onSelect?(selectedIndex) }
    private func updateSelection() {
        segments.selectedSegmentIndex = selectedIndex
        for (index, button) in buttons.enumerated() {
            button.isSelected = index == selectedIndex
            button.backgroundColor = button.isSelected ? InspectorTheme.tint : InspectorTheme.surface
            button.accessibilityTraits = button.isSelected ? [.button, .selected] : .button
        }
    }
    override func didMoveToWindow() { super.didMoveToWindow(); updateLayout() }
    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection); updateLayout()
    }
    private func updateLayout() {
        let large = traitCollection.preferredContentSizeCategory.isAccessibilityCategory
        segments.isHidden = large; choices.isHidden = !large
    }
}
#endif

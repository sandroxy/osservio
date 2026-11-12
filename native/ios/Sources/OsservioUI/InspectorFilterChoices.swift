#if canImport(UIKit)
import UIKit

/// Direct choices for short, stable dimensions. The query owns selection semantics.
@MainActor
final class InspectorFilterChoices: UIStackView {
    private let grid = UIStackView()
    private let controls: [Choice]
    private var columns = 0
    var onSelect: ((String?) -> Void)?

    init(group: InspectorFilterGroup, selected: Set<String>) {
        controls = [Choice(value: nil, title: InspectorLocalization.text("ui_all"), selected: selected.isEmpty)]
            + group.options.map { Choice(value: $0.value, title: $0.label, selected: selected.contains($0.value)) }
        super.init(frame: .zero); axis = .vertical; spacing = 10
        let heading = InspectorTheme.label(.body); heading.text = group.title; heading.accessibilityTraits = .header
        addArrangedSubview(heading); addArrangedSubview(grid); grid.axis = .vertical; grid.spacing = 8
        for control in controls { control.addTarget(self, action: #selector(choose(_:)), for: .touchUpInside) }
        updateLayout()
    }
    @available(*, unavailable)
    required init(coder: NSCoder) { fatalError("Programmatic construction only") }
    @objc private func choose(_ control: Choice) { onSelect?(control.value) }
    override func didMoveToWindow() { super.didMoveToWindow(); updateLayout() }
    override func layoutSubviews() { updateLayout(); super.layoutSubviews() }
    override func systemLayoutSizeFitting(_ targetSize: CGSize, withHorizontalFittingPriority horizontal: UILayoutPriority, verticalFittingPriority vertical: UILayoutPriority) -> CGSize {
        updateLayout(width: targetSize.width)
        return super.systemLayoutSizeFitting(targetSize, withHorizontalFittingPriority: horizontal, verticalFittingPriority: vertical)
    }
    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection); updateLayout()
    }
    private func updateLayout(width: CGFloat? = nil) {
        let available = width ?? bounds.width
        var count = traitCollection.preferredContentSizeCategory.isAccessibilityCategory ? 1 : 3
        let required = controls.map(\.minimumWidth).max() ?? 0
        if available > 0 { while count > 1 && (available - CGFloat(count - 1) * 8) / CGFloat(count) < required { count -= 1 } }
        guard count != columns else { return }; columns = count
        for control in controls { control.removeFromSuperview() }
        for row in grid.arrangedSubviews { row.removeFromSuperview() }
        for start in stride(from: 0, to: controls.count, by: columns) {
            let row = UIStackView(); row.axis = .horizontal; row.spacing = 8; row.distribution = .fillEqually
            for index in start..<(start + columns) {
                row.addArrangedSubview(index < controls.count ? controls[index] : UIView())
            }
            grid.addArrangedSubview(row)
        }
    }

    private final class Choice: UIControl {
        let value: String?
        private let label = InspectorTheme.label(.subheadline)
        private var measuredFont: UIFont?
        private var measuredWidth: CGFloat = 0
        var minimumWidth: CGFloat {
            let text = label.text ?? "", font = label.font ?? UIFont.preferredFont(forTextStyle: .subheadline)
            if measuredFont == font { return measuredWidth }
            var word: CGFloat = 0
            text.enumerateSubstrings(in: text.startIndex..<text.endIndex, options: .byWords) { value, _, _, _ in
                if let value { word = max(word, (value as NSString).size(withAttributes: [.font: font]).width) }
            }
            measuredFont = font; measuredWidth = ceil(max(word, (text as NSString).size(withAttributes: [.font: font]).width / 2)) + 38
            return measuredWidth
        }
        init(value: String?, title: String, selected: Bool) {
            self.value = value; super.init(frame: .zero)
            isSelected = selected; isAccessibilityElement = true; accessibilityLabel = title
            accessibilityTraits = selected ? [.button, .selected] : .button
            backgroundColor = selected ? InspectorTheme.tint : InspectorTheme.canvas
            layer.cornerRadius = 12
            label.text = title
            label.textColor = selected ? InspectorTheme.accent : .label
            let check = UIImageView(image: UIImage(systemName: "checkmark")); check.tintColor = InspectorTheme.accent
            check.contentMode = .scaleAspectFit; check.alpha = selected ? 1 : 0
            let content = UIStackView(arrangedSubviews: [label, check]); content.axis = .horizontal; content.spacing = 4; content.alignment = .center
            content.isUserInteractionEnabled = false; content.translatesAutoresizingMaskIntoConstraints = false; addSubview(content)
            NSLayoutConstraint.activate([content.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 10),
                content.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -10),
                content.topAnchor.constraint(equalTo: topAnchor, constant: 10), content.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -10),
                check.widthAnchor.constraint(equalToConstant: 14), check.heightAnchor.constraint(equalToConstant: 16),
                heightAnchor.constraint(greaterThanOrEqualToConstant: 44)])
        }
        @available(*, unavailable) required init?(coder: NSCoder) { return nil }
        override var isHighlighted: Bool { didSet { alpha = isHighlighted ? 0.6 : 1 } }
    }
}
#endif

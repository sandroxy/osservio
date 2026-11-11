#if canImport(UIKit)
import UIKit

/// A small adaptive palette; every swatch has a full-size hit target and a non-color selection mark.
@MainActor
final class InspectorPaletteGrid: UIView {
    private var columns = 5
    private let buttons = InspectorPalette.allCases.map(InspectorPaletteButton.init)
    override init(frame: CGRect) {
        super.init(frame: frame)
        buttons.forEach(addSubview)
    }
    @available(*, unavailable) required init?(coder: NSCoder) { return nil }
    override var intrinsicContentSize: CGSize {
        CGSize(width: UIView.noIntrinsicMetric, height: CGFloat((buttons.count + columns - 1) / columns) * 56)
    }
    override func layoutSubviews() {
        super.layoutSubviews()
        let next = max(1, min(5, Int(bounds.width / 56)))
        if columns != next { columns = next; invalidateIntrinsicContentSize() }
        let width = bounds.width / CGFloat(columns)
        for (index, button) in buttons.enumerated() {
            button.frame = CGRect(x: CGFloat(index % columns) * width + (width - 48) / 2,
                                  y: CGFloat(index / columns) * 56 + 4, width: 48, height: 48)
        }
    }
}

@MainActor
private final class InspectorPaletteButton: UIButton {
    private let palette: InspectorPalette
    init(_ palette: InspectorPalette) {
        self.palette = palette; super.init(frame: .zero)
        accessibilityLabel = InspectorLocalization.text(palette.label)
        isSelected = palette == InspectorPreferences.shared.palette
        accessibilityTraits = isSelected ? [.button, .selected] : .button
        addTarget(self, action: #selector(choose), for: .touchUpInside)
    }
    @available(*, unavailable) required init?(coder: NSCoder) { return nil }
    override var isHighlighted: Bool { didSet { alpha = isHighlighted ? 0.65 : 1 } }
    override func draw(_ rect: CGRect) {
        let colors = palette.colors
        let color = InspectorTheme.pair(colors.light, colors.dark)
        color.setFill(); UIBezierPath(ovalIn: CGRect(x: bounds.midX - 15, y: bounds.midY - 15, width: 30, height: 30)).fill()
        if isSelected {
            color.setStroke(); let ring = UIBezierPath(ovalIn: bounds.insetBy(dx: 5, dy: 5)); ring.lineWidth = 2; ring.stroke()
            let ink: UIColor = traitCollection.userInterfaceStyle == .dark ? InspectorTheme.canvas : .white
            UIImage(systemName: "checkmark", withConfiguration: UIImage.SymbolConfiguration(pointSize: 15, weight: .bold))?
                .withTintColor(ink, renderingMode: .alwaysOriginal).draw(in: CGRect(x: bounds.midX - 8, y: bounds.midY - 8, width: 16, height: 16))
        }
    }
    @objc private func choose() { InspectorPreferences.shared.palette = palette }
}
#endif

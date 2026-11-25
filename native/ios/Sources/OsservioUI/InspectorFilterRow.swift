#if canImport(UIKit)
import UIKit

/// Shares the field/value layout between table rows and disclosure buttons in the filter editor.
@MainActor
final class InspectorFilterLabels: UIStackView {
    let name = InspectorTheme.label(.body)
    let value: UILabel
    var prefersStacked = false

    init(value: UILabel? = nil) {
        self.value = value ?? InspectorTheme.label(.subheadline, secondary: true)
        super.init(frame: .zero)
        axis = .vertical; alignment = .fill; spacing = 3
        isUserInteractionEnabled = false
        addArrangedSubview(name); addArrangedSubview(self.value)
        name.setContentHuggingPriority(.defaultHigh, for: .horizontal)
        self.value.numberOfLines = 0
    }
    @available(*, unavailable) required init(coder: NSCoder) { fatalError("Programmatic construction only") }

    func arrange(width: CGFloat) {
        guard width > 0 else { return }
        let unconstrained = CGSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude)
        let inline = !prefersStacked && !traitCollection.preferredContentSizeCategory.isAccessibilityCategory
            && name.sizeThatFits(unconstrained).width + 12 + value.sizeThatFits(unconstrained).width <= width
            && value.text?.contains("\n") != true
        let direction: NSLayoutConstraint.Axis = inline ? .horizontal : .vertical
        if axis != direction {
            axis = direction; alignment = inline ? .center : .fill; spacing = inline ? 12 : 3
        }
        let trailing: NSTextAlignment = effectiveUserInterfaceLayoutDirection == .rightToLeft ? .left : .right
        value.textAlignment = inline ? trailing : .natural
    }
    override func layoutSubviews() { arrange(width: bounds.width); super.layoutSubviews() }
}

/// The three disclosure directions use the same symbol at its native size.
@MainActor
final class InspectorFilterDisclosure: UIView {
    enum Direction { case forward, up, down }
    static let width: CGFloat = 16
    private static let glyph = UIImage(systemName: "chevron.down", withConfiguration: UIImage.SymbolConfiguration(pointSize: 13, weight: .semibold))
    private let icon = UIImageView(image: glyph)
    var direction: Direction = .forward { didSet { if oldValue != direction { setNeedsLayout() } } }

    func attach(to row: UIView) {
        isUserInteractionEnabled = false; isAccessibilityElement = false
        icon.contentMode = .center; icon.tintColor = InspectorTheme.secondary; addSubview(icon)
        translatesAutoresizingMaskIntoConstraints = false; row.addSubview(self)
        NSLayoutConstraint.activate([widthAnchor.constraint(equalToConstant: Self.width), heightAnchor.constraint(equalTo: widthAnchor),
            trailingAnchor.constraint(equalTo: row.trailingAnchor, constant: -16), centerYAnchor.constraint(equalTo: row.centerYAnchor)])
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        icon.bounds = CGRect(origin: .zero, size: bounds.size); icon.center = CGPoint(x: bounds.midX, y: bounds.midY)
        switch direction {
        case .forward: icon.transform = CGAffineTransform(rotationAngle: effectiveUserInterfaceLayoutDirection == .rightToLeft ? .pi / 2 : -.pi / 2)
        case .up: icon.transform = CGAffineTransform(scaleX: 1, y: -1)
        case .down: icon.transform = .identity
        }
    }
}

@MainActor
final class InspectorFilterSummaryCell: UITableViewCell {
    let labels = InspectorFilterLabels()
    private let arrow = InspectorFilterDisclosure()
    private let inset: CGFloat = 16, arrowSpacing: CGFloat = 10
    private lazy var labelsTrailing = labels.trailingAnchor.constraint(equalTo: contentView.trailingAnchor)

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = InspectorTheme.surface
        labels.translatesAutoresizingMaskIntoConstraints = false; contentView.addSubview(labels)
        arrow.attach(to: contentView)
        let minimum = contentView.heightAnchor.constraint(greaterThanOrEqualToConstant: 46); minimum.priority = .defaultHigh
        NSLayoutConstraint.activate([minimum, labels.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: inset),
            labelsTrailing, labels.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 10),
            labels.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -10)])
        isAccessibilityElement = true
    }
    @available(*, unavailable) required init?(coder: NSCoder) { return nil }

    func bind(_ name: String, _ value: String, direction: InspectorFilterDisclosure.Direction?, stacked: Bool = false) {
        let heading = direction == nil
        labels.name.font = .preferredFont(forTextStyle: heading ? .headline : .body, compatibleWith: traitCollection)
        labels.name.text = name; labels.value.text = value; labels.value.isHidden = value.isEmpty
        labels.value.font = .preferredFont(forTextStyle: heading ? .caption1 : .subheadline, compatibleWith: traitCollection)
        labels.prefersStacked = stacked
        if let direction { arrow.direction = direction }
        arrow.isHidden = heading; labelsTrailing.constant = -inset - (heading ? 0 : InspectorFilterDisclosure.width + arrowSpacing)
        accessibilityLabel = name; accessibilityValue = value.isEmpty ? nil : value
        accessibilityTraits = heading ? .header : .button; selectionStyle = heading ? .none : .default
    }
    override func systemLayoutSizeFitting(_ targetSize: CGSize, withHorizontalFittingPriority horizontal: UILayoutPriority,
                                          verticalFittingPriority vertical: UILayoutPriority) -> CGSize {
        labels.arrange(width: targetSize.width - inset + labelsTrailing.constant)
        return super.systemLayoutSizeFitting(targetSize, withHorizontalFittingPriority: horizontal, verticalFittingPriority: vertical)
    }
}
#endif

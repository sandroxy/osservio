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
        let inline = !prefersStacked && !traitCollection.preferredContentSizeCategory.isAccessibilityCategory
            && name.intrinsicContentSize.width + 12 + value.intrinsicContentSize.width <= width
            && value.text?.contains("\n") != true
        let direction: NSLayoutConstraint.Axis = inline ? .horizontal : .vertical
        if axis != direction {
            axis = direction; alignment = inline ? .firstBaseline : .fill; spacing = inline ? 12 : 3
        }
        let trailing: NSTextAlignment = effectiveUserInterfaceLayoutDirection == .rightToLeft ? .left : .right
        value.textAlignment = inline ? trailing : .natural
    }
    override func layoutSubviews() { arrange(width: bounds.width); super.layoutSubviews() }
}

@MainActor
final class InspectorFilterSummaryCell: UITableViewCell {
    let labels = InspectorFilterLabels()
    private let arrow = UIImageView()
    private let line = UIStackView()
    private let inset: CGFloat = 16

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = InspectorTheme.surface
        line.alignment = .center; line.spacing = 10; line.translatesAutoresizingMaskIntoConstraints = false
        arrow.contentMode = .scaleAspectFit; arrow.tintColor = InspectorTheme.secondary
        arrow.isAccessibilityElement = false
        let arrowWidth = arrow.widthAnchor.constraint(equalToConstant: 16); arrowWidth.priority = .defaultHigh; arrowWidth.isActive = true
        line.addArrangedSubview(labels); line.addArrangedSubview(arrow); contentView.addSubview(line)
        let minimum = contentView.heightAnchor.constraint(greaterThanOrEqualToConstant: 46); minimum.priority = .defaultHigh
        NSLayoutConstraint.activate([minimum, line.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: inset),
            line.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -inset),
            line.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 10),
            line.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -10)])
        isAccessibilityElement = true
    }
    @available(*, unavailable) required init?(coder: NSCoder) { return nil }

    func bind(_ name: String, _ value: String, symbol: String?, stacked: Bool = false) {
        let heading = symbol == nil
        labels.name.font = .preferredFont(forTextStyle: heading ? .headline : .body, compatibleWith: traitCollection)
        labels.name.text = name; labels.value.text = value; labels.value.isHidden = value.isEmpty
        labels.value.font = .preferredFont(forTextStyle: heading ? .caption1 : .subheadline, compatibleWith: traitCollection)
        labels.prefersStacked = stacked
        arrow.image = symbol.flatMap {
            let image = UIImage(systemName: $0, withConfiguration: UIImage.SymbolConfiguration(pointSize: 13, weight: .semibold))
            return $0 == "chevron.right" ? image?.imageFlippedForRightToLeftLayoutDirection() : image
        }
        arrow.isHidden = heading
        accessibilityLabel = name; accessibilityValue = value.isEmpty ? nil : value
        accessibilityTraits = heading ? .header : .button; selectionStyle = heading ? .none : .default
    }
    override func systemLayoutSizeFitting(_ targetSize: CGSize, withHorizontalFittingPriority horizontal: UILayoutPriority,
                                          verticalFittingPriority vertical: UILayoutPriority) -> CGSize {
        labels.arrange(width: targetSize.width - 2 * inset - (arrow.isHidden ? 0 : 16 + line.spacing))
        return super.systemLayoutSizeFitting(targetSize, withHorizontalFittingPriority: horizontal, verticalFittingPriority: vertical)
    }
}
#endif

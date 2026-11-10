#if canImport(UIKit)
import UIKit

/// Controls scroll with history so a short viewport never collapses the list.
@MainActor
final class InspectorTableView: UITableView {
    private var emptyFooter: UIStackView?

    func setEmptyState(_ label: UILabel?) {
        emptyFooter = label.map {
            let footer = UIStackView(arrangedSubviews: [$0])
            footer.axis = .vertical; footer.isLayoutMarginsRelativeArrangement = true
            footer.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 16, leading: 16, bottom: 16, trailing: 16)
            return footer
        }
        tableFooterView = emptyFooter ?? UIView()
        setNeedsLayout()
    }

    override func layoutSubviews() {
        if let header = tableHeaderView, bounds.width > 0 {
            let height = header.systemLayoutSizeFitting(
                CGSize(width: bounds.width, height: UIView.layoutFittingCompressedSize.height),
                withHorizontalFittingPriority: .required, verticalFittingPriority: .fittingSizeLevel).height
            let size = CGSize(width: bounds.width, height: ceil(height))
            if header.frame.size != size {
                header.frame.size = size
                tableHeaderView = header
            }
        }
        if let footer = emptyFooter, bounds.width > 0 {
            let height = footer.systemLayoutSizeFitting(
                CGSize(width: bounds.width, height: UIView.layoutFittingCompressedSize.height),
                withHorizontalFittingPriority: .required, verticalFittingPriority: .fittingSizeLevel).height
            let size = CGSize(width: bounds.width, height: ceil(height))
            if footer.frame.size != size {
                footer.frame.size = size
                tableFooterView = footer
            }
        }
        super.layoutSubviews()
    }
}

/// Accessibility sizes use full-width actions instead of three compressed titles.
@MainActor
final class InspectorControls: UIStackView {
    init(buttons: [UIButton]) {
        super.init(frame: .zero)
        distribution = .fillEqually; spacing = 8
        for button in buttons {
            button.titleLabel?.numberOfLines = 0
            button.titleLabel?.textAlignment = .center
            button.titleLabel?.adjustsFontForContentSizeCategory = true
            button.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
            addArrangedSubview(button)
        }
        updateTypography()
    }
    @available(*, unavailable)
    required init(coder: NSCoder) { fatalError("Programmatic construction only") }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        if previousTraitCollection?.preferredContentSizeCategory != traitCollection.preferredContentSizeCategory {
            updateTypography()
        }
    }
    private func updateTypography() {
        axis = traitCollection.preferredContentSizeCategory.isAccessibilityCategory ? .vertical : .horizontal
        for case let button as UIButton in arrangedSubviews {
            button.titleLabel?.font = .preferredFont(forTextStyle: .subheadline, compatibleWith: traitCollection)
        }
    }
}
/// The primary inline action keeps a capsule shape at every supported text size.
@MainActor
final class InspectorCapsuleButton: UIButton {
    override func layoutSubviews() { super.layoutSubviews(); layer.cornerRadius = bounds.height / 2 }
    override var intrinsicContentSize: CGSize {
        let size = super.intrinsicContentSize
        return CGSize(width: size.width + 32, height: max(48, size.height + 20))
    }
}
#endif

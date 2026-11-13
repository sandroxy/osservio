#if canImport(UIKit)
import UIKit

/// Controls scroll with history so a short viewport never collapses the list.
@MainActor
final class InspectorTableView: UITableView {
    private var emptyFooter: UIStackView?
    /// Live feeds keep the top anchored through deferred self-sizing and search results.
    /// A user drag releases this before UIKit changes the reading position.
    var followsLatest = false {
        didSet { if followsLatest != oldValue { setNeedsLayout() } }
    }

    override func accessibilityScroll(_ direction: UIAccessibilityScrollDirection) -> Bool {
        if direction == .up || direction == .down {
            // VoiceOver scrolls without beginning a touch drag. Release the same reading anchor.
            delegate?.scrollViewWillBeginDragging?(self)
        }
        return super.accessibilityScroll(direction)
    }

    func showLatest() {
        followsLatest = true
        setNeedsLayout()
        UIView.performWithoutAnimation { layoutIfNeeded() }
    }
    /// Retained windows often replace records without changing their count. Preserve
    /// UIKit's reusable cells and offscreen height estimates in that case; cells
    /// entering the viewport still bind against the current data source.
    func refreshFeed(rowCount: Int) {
        guard numberOfSections == 1, numberOfRows(inSection: 0) == rowCount,
              rowCount > 0, window != nil else { reloadData(); return }
        refreshRows(indexPathsForVisibleRows ?? [])
    }

    func refreshRows(_ positions: [IndexPath]) {
        guard !positions.isEmpty else { return }
        UIView.performWithoutAnimation {
            if #available(iOS 15, *) { reconfigureRows(at: positions) }
            else { reloadRows(at: positions, with: .none) }
        }
    }
    var followLatestButton: UIButton? {
        didSet {
            oldValue?.removeFromSuperview()
            if let followLatestButton { addSubview(followLatestButton) }
            setNeedsLayout()
        }
    }

    func setEmptyState(_ label: UILabel?) {
        if label == nil && emptyFooter == nil { return }
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
        if followsLatest && !isTracking && !isDragging && !isDecelerating {
            let top = CGPoint(x: contentOffset.x, y: -adjustedContentInset.top)
            if abs(contentOffset.y - top.y) > 0.5 { setContentOffset(top, animated: false) }
        }
        if let button = followLatestButton {
            let available = max(0, bounds.width - safeAreaInsets.left - safeAreaInsets.right - 32)
            let fitted = button.sizeThatFits(CGSize(width: available, height: 100))
            let size = CGSize(width: min(available, max(44, fitted.width)), height: max(44, fitted.height))
            UIView.performWithoutAnimation {
                button.frame = CGRect(x: bounds.midX - size.width / 2,
                    y: max(bounds.minY + adjustedContentInset.top, bounds.maxY - adjustedContentInset.bottom - size.height - 16),
                    width: size.width, height: size.height)
                button.layoutIfNeeded()
            }
            bringSubviewToFront(button)
        }
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

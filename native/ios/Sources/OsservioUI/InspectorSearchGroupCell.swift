#if canImport(UIKit)
import UIKit

/// The first row of a searchable section. Results remain reusable sibling rows.
@MainActor
final class InspectorSearchGroupCell: UITableViewCell {
    private let heading = InspectorTheme.label(.headline)
    private let summary = InspectorTheme.label(.caption1, secondary: true)
    private let message = InspectorTheme.label(.subheadline, secondary: true)
    private let searchControls: UIView
    private let adjust: UIButton?

    init(search: UISearchTextField, controls: InspectorFeedHeader? = nil) {
        searchControls = controls ?? search
        adjust = controls?.adjustFiltersButton
        controls?.directionalLayoutMargins = .zero
        super.init(style: .default, reuseIdentifier: nil)
        selectionStyle = .none; backgroundColor = InspectorTheme.surface
        heading.accessibilityTraits.insert(.header)
        search.backgroundColor = InspectorTheme.canvas
        search.layer.cornerRadius = 12; search.clipsToBounds = true
        let stack = UIStackView(arrangedSubviews: [heading, summary, searchControls, message])
        if let adjust { stack.addArrangedSubview(adjust); adjust.isHidden = true }
        stack.axis = .vertical; stack.spacing = 10; stack.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 16),
            stack.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -16),
            stack.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 16),
            stack.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -16)
        ])
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func layoutSubviews() {
        super.layoutSubviews()
        // The search and first result share a surface without an extra dividing rule.
        separatorInset = UIEdgeInsets(top: 0, left: bounds.width, bottom: 0, right: 0)
    }
    func configure(title: String, summary: String?, searchable: Bool = true, message: String?, filteredEmpty: Bool = false) {
        heading.text = title
        self.summary.text = summary; self.summary.isHidden = summary?.isEmpty != false
        searchControls.isHidden = !searchable
        self.message.text = message; self.message.isHidden = message == nil
        adjust?.isHidden = !filteredEmpty
        adjust?.setTitle(InspectorLocalization.text("ui_adjust_filters"), for: .normal)
        adjust?.tintColor = InspectorTheme.accent
    }
}
#endif

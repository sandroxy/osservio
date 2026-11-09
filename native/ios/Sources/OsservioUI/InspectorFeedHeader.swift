#if canImport(UIKit)
import UIKit

/// All controls scroll with the feed, including at accessibility sizes or with the keyboard open.
@MainActor
final class InspectorFeedHeader: UIStackView, UITextFieldDelegate {
    let status = InspectorTheme.label(.caption1, secondary: true)
    let pause = UIButton(type: .system)
    let actions = UIButton(type: .system)
    let search = UISearchTextField()
    let filters = UIButton(type: .system)
    let criteria = InspectorTheme.label(.caption1, secondary: true)
    let count = InspectorTheme.label(.caption1, secondary: true)
    let extra = UIStackView()
    var onSearch: ((String) -> Void)?

    init() {
        super.init(frame: .zero)
        axis = .vertical; spacing = 10; isLayoutMarginsRelativeArrangement = true
        directionalLayoutMargins = NSDirectionalEdgeInsets(top: 12, leading: 16, bottom: 14, trailing: 16)
        extra.axis = .vertical; extra.spacing = 8
        search.font = .preferredFont(forTextStyle: .body); search.adjustsFontForContentSizeCategory = true
        search.backgroundColor = InspectorTheme.surface; search.layer.cornerRadius = 10
        search.autocorrectionType = .no; search.autocapitalizationType = .none
        search.returnKeyType = .done; search.delegate = self
        search.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        search.addTarget(self, action: #selector(searchChanged), for: .editingChanged)
        actions.setImage(UIImage(systemName: "ellipsis"), for: .normal)
        let controls = InspectorControls(buttons: [pause, filters, actions])
        for button in [pause, filters] {
            button.backgroundColor = InspectorTheme.tint; button.layer.cornerRadius = 10
        }
        [status, extra, search, controls, criteria, count].forEach(addArrangedSubview)
        relabel(paused: false, query: InspectorQuery())
    }
    @available(*, unavailable)
    required init(coder: NSCoder) { fatalError("Programmatic construction only") }
    func relabel(paused: Bool, query: InspectorQuery) {
        pause.setTitle(InspectorLocalization.text(paused ? "ui_follow_latest" : "ui_pause"), for: .normal)
        actions.accessibilityLabel = InspectorLocalization.text("ui_actions")
        filters.setTitle(InspectorLocalization.text("ui_filters") + (query.activeCount > 0 ? " · \(query.activeCount)" : ""), for: .normal)
        criteria.text = InspectorFilterOptions.summary(query)
        criteria.isHidden = query.activeCount == 0
    }
    @objc private func searchChanged() {
        guard search.markedTextRange == nil else { return }
        let value = String((search.text ?? "").prefix(512))
        if value != search.text { search.text = value }
        onSearch?(value)
    }
    func textFieldShouldReturn(_ textField: UITextField) -> Bool { textField.resignFirstResponder(); return true }
}

@MainActor
final class InspectorEventCell: UITableViewCell {
    private let badge = InspectorTheme.label(.caption1)
    private let timestamp = InspectorTheme.label(.caption2, secondary: true)
    private let primary = InspectorTheme.label(.body)
    private let secondary = InspectorTheme.label(.caption1, secondary: true)
    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        primary.numberOfLines = 3; secondary.numberOfLines = 3
        let heading = UIStackView(arrangedSubviews: [badge, timestamp]); heading.spacing = 12
        badge.setContentCompressionResistancePriority(.required, for: .horizontal)
        timestamp.textAlignment = .right
        let stack = UIStackView(arrangedSubviews: [heading, primary, secondary])
        stack.axis = .vertical; stack.spacing = 6; stack.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 13),
            stack.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -13),
            stack.leadingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.trailingAnchor)
        ])
        backgroundColor = InspectorTheme.surface; accessoryType = .disclosureIndicator
        isAccessibilityElement = true; accessibilityTraits = .button
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    func configure(badge: String, time: Int64, title: String, subtitle: String, color: UIColor? = nil) {
        self.badge.text = badge; self.badge.textColor = color ?? InspectorTheme.accent
        timestamp.text = InspectorLocalization.date(time, timeOnly: true)
        primary.text = title; secondary.text = subtitle
        accessibilityLabel = [badge, title, subtitle, timestamp.text ?? ""].joined(separator: ", ")
    }
}
#endif

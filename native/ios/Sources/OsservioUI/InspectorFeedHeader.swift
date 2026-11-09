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
        for button in [pause, filters, actions] {
            button.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
            button.widthAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
            button.setContentHuggingPriority(.required, for: .horizontal)
            button.setContentCompressionResistancePriority(.required, for: .horizontal)
        }
        let searchRow = UIStackView(arrangedSubviews: [search, filters]); searchRow.spacing = 8
        let controls = UIStackView(arrangedSubviews: [status, pause, actions]); controls.spacing = 8; controls.alignment = .center
        status.setContentHuggingPriority(.defaultLow, for: .horizontal)
        [extra, searchRow, controls, criteria, count].forEach(addArrangedSubview)
        relabel(paused: false, query: InspectorQuery())
    }
    @available(*, unavailable)
    required init(coder: NSCoder) { fatalError("Programmatic construction only") }
    func relabel(paused: Bool, query: InspectorQuery) {
        configure(pause, symbol: paused ? "play.fill" : "pause.fill", selected: paused)
        pause.accessibilityLabel = InspectorLocalization.text(paused ? "ui_follow_latest" : "ui_pause")
        configure(actions, symbol: "ellipsis")
        actions.accessibilityLabel = InspectorLocalization.text("ui_actions")
        configure(filters, symbol: "line.3.horizontal.decrease", selected: query.activeCount > 0,
                  title: query.activeCount > 0 ? String(query.activeCount) : nil)
        filters.accessibilityLabel = InspectorLocalization.text("ui_filters")
        filters.accessibilityValue = query.activeCount > 0 ? InspectorFilterOptions.summary(query) : nil
        criteria.text = InspectorFilterOptions.summary(query)
        criteria.isHidden = query.activeCount == 0
    }
    private func configure(_ button: UIButton, symbol: String, selected: Bool = false, title: String? = nil) {
        guard #available(iOS 15, *) else {
            button.setImage(UIImage(systemName: symbol), for: .normal); button.setTitle(title, for: .normal)
            button.tintColor = InspectorTheme.accent; button.backgroundColor = selected ? InspectorTheme.tint : InspectorTheme.surface
            button.layer.cornerRadius = 22; button.contentEdgeInsets = UIEdgeInsets(top: 10, left: 12, bottom: 10, right: 12)
            return
        }
        var configuration = UIButton.Configuration.plain()
        configuration.image = UIImage(systemName: symbol)
        configuration.preferredSymbolConfigurationForImage = UIImage.SymbolConfiguration(pointSize: 17, weight: .medium)
        configuration.title = title; configuration.imagePadding = 5
        configuration.baseForegroundColor = InspectorTheme.accent
        configuration.background.backgroundColor = selected ? InspectorTheme.tint : InspectorTheme.surface
        configuration.cornerStyle = .capsule
        configuration.contentInsets = NSDirectionalEdgeInsets(top: 10, leading: 12, bottom: 10, trailing: 12)
        button.configuration = configuration
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

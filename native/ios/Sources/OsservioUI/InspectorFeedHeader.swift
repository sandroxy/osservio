#if canImport(UIKit)
import UIKit

/// Search and summaries scroll with the feed; navigation and the latest shortcut have separate owners.
@MainActor
final class InspectorFeedHeader: UIStackView, UITextFieldDelegate {
    let status = InspectorTheme.label(.caption1, secondary: true)
    let pause = UIButton(type: .system)
    let actions = UIButton(type: .system)
    let search = UISearchTextField()
    let filters = UIButton(type: .system)
    let criteria = InspectorTheme.label(.caption1, secondary: true)
    let count = InspectorTheme.label(.caption1, secondary: true)
    let activity = UIActivityIndicatorView(style: .medium)
    let extra = UIStackView()
    var onSearch: ((String) -> Void)?
    var onQueryChange: ((InspectorQuery) -> Void)?
    private let compact: Bool
    private let actionLabel: String
    private let tokens = UIStackView()
    private let tokenScroll = UIScrollView()
    private var tokenQuery: InspectorQuery?
    private var tokenStyle = ""
    private var latestStyle = ""


    init(compact: Bool = false, actionLabel: String = "clear") {
        self.compact = compact; self.actionLabel = actionLabel
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
        let activitySlot = UIView(); activitySlot.translatesAutoresizingMaskIntoConstraints = false
        activity.translatesAutoresizingMaskIntoConstraints = false; activitySlot.addSubview(activity)
        NSLayoutConstraint.activate([activitySlot.widthAnchor.constraint(equalToConstant: 20),
            activitySlot.heightAnchor.constraint(equalToConstant: 20), activity.centerXAnchor.constraint(equalTo: activitySlot.centerXAnchor),
            activity.centerYAnchor.constraint(equalTo: activitySlot.centerYAnchor)])
        let countRow = UIStackView(arrangedSubviews: [count, activitySlot]); countRow.spacing = 8; countRow.alignment = .center
        let summary = UIStackView(arrangedSubviews: compact ? [countRow, status] : [status]); summary.axis = .vertical; summary.spacing = 4
        let controls = UIStackView(arrangedSubviews: compact ? [summary] : [summary, pause, actions]); controls.spacing = 8; controls.alignment = .center
        status.setContentHuggingPriority(.defaultLow, for: .horizontal)
        if compact {
            actions.isHidden = true
            tokens.spacing = 6; tokens.translatesAutoresizingMaskIntoConstraints = false
            tokenScroll.addSubview(tokens); tokenScroll.showsHorizontalScrollIndicator = false
            NSLayoutConstraint.activate([tokens.leadingAnchor.constraint(equalTo: tokenScroll.contentLayoutGuide.leadingAnchor),
                tokens.trailingAnchor.constraint(equalTo: tokenScroll.contentLayoutGuide.trailingAnchor),
                tokens.topAnchor.constraint(equalTo: tokenScroll.contentLayoutGuide.topAnchor), tokens.bottomAnchor.constraint(equalTo: tokenScroll.contentLayoutGuide.bottomAnchor),
                tokens.heightAnchor.constraint(equalTo: tokenScroll.frameLayoutGuide.heightAnchor)])
            tokenScroll.heightAnchor.constraint(equalToConstant: 44).isActive = true
            [extra, searchRow, tokenScroll, controls].forEach(addArrangedSubview)
        } else { [extra, searchRow, controls, criteria, count].forEach(addArrangedSubview) }
        relabel(paused: false, query: InspectorQuery())
    }
    @available(*, unavailable)
    required init(coder: NSCoder) { fatalError("Programmatic construction only") }
    func relabel(paused: Bool, query: InspectorQuery) {
        if compact {
            let style = InspectorLocalization.text("ui_follow_latest") + InspectorPreferences.shared.appearance.rawValue
                + InspectorPreferences.shared.palette.rawValue + traitCollection.preferredContentSizeCategory.rawValue
            if latestStyle != style {
                latestStyle = style
                configure(pause, symbol: "arrow.up", title: InspectorLocalization.text("ui_follow_latest"))
                pause.titleLabel?.font = .preferredFont(forTextStyle: .subheadline, compatibleWith: traitCollection)
                pause.titleLabel?.adjustsFontForContentSizeCategory = true
                pause.titleLabel?.numberOfLines = 1
            }
            pause.isHidden = !paused
            pause.layer.shadowColor = UIColor.black.cgColor; pause.layer.shadowOpacity = 0.12
            pause.layer.shadowRadius = 8; pause.layer.shadowOffset = CGSize(width: 0, height: 3)
        } else { configure(pause, symbol: paused ? "play.fill" : "pause.fill", selected: paused) }
        pause.accessibilityLabel = InspectorLocalization.text(paused ? "ui_follow_latest" : "ui_pause")
        configure(actions, symbol: compact ? "trash" : "ellipsis")
        actions.accessibilityLabel = InspectorLocalization.text(compact ? actionLabel : "ui_actions")
        configure(filters, symbol: "line.3.horizontal.decrease", selected: query.activeCount > 0,
                  title: query.activeCount > 0 ? String(query.activeCount) : nil)
        filters.accessibilityLabel = InspectorLocalization.text("ui_filters")
        filters.accessibilityValue = query.activeCount > 0 ? InspectorFilterOptions.summary(query) : nil
        criteria.text = InspectorFilterOptions.summary(query)
        criteria.isHidden = query.activeCount == 0
        if compact { updateTokens(query); status.isHidden = status.text?.isEmpty != false }
    }
    private func updateTokens(_ query: InspectorQuery) {
        tokenScroll.isHidden = query.activeCount == 0
        let style = InspectorLocalization.text("ui_remove_filter") + InspectorPreferences.shared.appearance.rawValue + InspectorPreferences.shared.palette.rawValue
        guard tokenQuery != query || tokenStyle != style else { return }
        tokenQuery = query; tokenStyle = style
        for view in tokens.arrangedSubviews { tokens.removeArrangedSubview(view); view.removeFromSuperview() }
        func add(_ title: String, changed: InspectorQuery) {
            let button = InspectorFilterToken { [weak self] in self?.onQueryChange?(changed) }
            button.setTitle(String(title.prefix(34)) + (title.count > 34 ? "…" : "") + " ×", for: .normal)
            button.titleLabel?.font = .preferredFont(forTextStyle: .caption1); button.titleLabel?.adjustsFontForContentSizeCategory = true
            button.tintColor = InspectorTheme.accent; button.backgroundColor = InspectorTheme.tint; button.layer.cornerRadius = 14
            button.setTitleColor(InspectorTheme.accent, for: .normal)
            button.contentEdgeInsets = UIEdgeInsets(top: 6, left: 10, bottom: 6, right: 10)
            button.accessibilityLabel = title; button.accessibilityHint = InspectorLocalization.text("ui_remove_filter")
            tokens.addArrangedSubview(button)
        }
        for field in InspectorQuery.fieldOrder {
            guard let values = query.values[field], !values.isEmpty else { continue }
            var changed = query
            if field == .transport { changed.select(.transport, value: nil) } else { changed.values[field] = nil }
            add(InspectorLocalization.text("ui_" + field.rawValue) + ": " + values.sorted().map { InspectorFilterOptions.label(field, $0) }.joined(separator: ", "), changed: changed)
        }
        if query.recentMinutes > 0 { var changed = query; changed.recentMinutes = 0; add(InspectorLocalization.text("ui_time_\(query.recentMinutes)"), changed: changed) }
        if !query.excludedText.isEmpty { var changed = query; changed.excludedText = ""; add(InspectorLocalization.text("ui_exclude") + ": " + query.excludedText, changed: changed) }
        if !query.fieldPath.isEmpty { var changed = query; changed.fieldPath = ""; changed.fieldValue = ""; add(query.fieldPath + " = " + query.fieldValue, changed: changed) }
        if query.minimumDurationMillis > 0 { var changed = query; changed.minimumDurationMillis = 0; add("HTTP ≥ \(query.minimumDurationMillis) ms", changed: changed) }
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
        configuration.titleLineBreakMode = .byTruncatingTail
        let font = UIFont.preferredFont(forTextStyle: .subheadline, compatibleWith: traitCollection)
        configuration.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { attributes in
            var attributes = attributes; attributes.font = font; return attributes
        }
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
private final class InspectorFilterToken: UIButton {
    private let operation: () -> Void
    init(operation: @escaping () -> Void) { self.operation = operation; super.init(frame: .zero); addTarget(self, action: #selector(run), for: .touchUpInside) }
    @available(*, unavailable) required init?(coder: NSCoder) { return nil }
    @objc private func run() { operation() }
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
    func setTimestamp(_ value: String) { timestamp.text = value; accessibilityLabel = [badge.text, primary.text, secondary.text, value].compactMap { $0 }.joined(separator: ", ") }
    func configure(_ row: InspectorRecordPresentation.Row) {
        configure(badge: row.badge, time: row.time, title: row.title, subtitle: row.subtitle,
            color: row.tone == .error ? .systemRed : row.tone == .warning ? .systemOrange : .secondaryLabel)
    }
    func configure(badge: String, time: Int64, title: String, subtitle: String, color: UIColor? = nil) {
        self.badge.text = badge; self.badge.textColor = color ?? InspectorTheme.accent
        timestamp.text = InspectorValuePresentation.eventTime(time)
        primary.text = title; secondary.text = subtitle
        accessibilityLabel = [badge, title, subtitle, timestamp.text ?? ""].joined(separator: ", ")
    }
}
#endif

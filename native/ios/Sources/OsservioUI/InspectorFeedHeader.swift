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
    var historicalTime = false
    var onFilters: ((InspectorQuery.Target?) -> Void)?
    private let undo = UIButton(type: .system)
    weak var feedbackTable: InspectorTableView?
    private lazy var undoNotice: UIStackView = {
        let label = InspectorTheme.label(.subheadline)
        label.text = InspectorLocalization.text("ui_filter_undo_notice")
        let panel = UIStackView(arrangedSubviews: [label, undo])
        panel.spacing = 12; panel.alignment = .center; panel.isLayoutMarginsRelativeArrangement = true
        panel.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 4, leading: 16, bottom: 4, trailing: 12)
        panel.backgroundColor = InspectorTheme.surface; panel.layer.cornerRadius = 22
        panel.layer.shadowColor = UIColor.black.cgColor; panel.layer.shadowOpacity = 0.12
        panel.layer.shadowRadius = 8; panel.layer.shadowOffset = CGSize(width: 0, height: 3)
        return panel
    }()
    private var removed: (before: InspectorQuery, after: InspectorQuery)?
    var onSearch: ((String) -> Void)?
    var onQueryChange: ((InspectorQuery) -> Void)?
    private let compact: Bool
    private let actionLabel: String
    private let tokens = UIStackView()
    private let tokenScroll = UIScrollView()
    private lazy var tokenHeight = tokenScroll.heightAnchor.constraint(equalToConstant: 44)
    private var measuredTokenWidth: CGFloat = 0
    private var tokenQuery: InspectorQuery?
    private var tokenStyle = ""
    private var latestStyle = ""
    private var displayedQuery: InspectorQuery?
    private var displayedPaused: Bool?
    private var displayedStyle = ""
    private var styleObserver: InspectorStyleObserver?


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
        filters.addTarget(self, action: #selector(openFilters), for: .touchUpInside)
        undo.titleLabel?.font = .preferredFont(forTextStyle: .subheadline)
        undo.titleLabel?.adjustsFontForContentSizeCategory = true
        undo.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        undo.widthAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        undo.addTarget(self, action: #selector(undoRemoval), for: .touchUpInside)
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
            tokenHeight.isActive = true
            [extra, searchRow, tokenScroll, controls].forEach(addArrangedSubview)
        } else { [extra, searchRow, controls, criteria, count].forEach(addArrangedSubview) }
        relabel(paused: false, query: InspectorQuery())
        styleObserver = InspectorStyleObserver { [weak self] in
            guard let self, let query = self.displayedQuery else { return }
            if self.relabel(paused: self.displayedPaused ?? false, query: query) { self.setNeedsLayout() }
        }
    }
    @available(*, unavailable)
    required init(coder: NSCoder) { fatalError("Programmatic construction only") }
    @discardableResult
    func relabel(paused: Bool, query: InspectorQuery) -> Bool {
        let style = InspectorLocalization.text("ui_filters") + InspectorPreferences.shared.appearance.rawValue
            + InspectorPreferences.shared.palette.rawValue + traitCollection.preferredContentSizeCategory.rawValue
            + String(InspectorTheme.allowsTransparentMaterial)
        let hidesStatus = compact && status.text?.isEmpty != false
        let statusChanged = compact && status.isHidden != hidesStatus
        if compact { status.isHidden = hidesStatus }
        guard displayedQuery != query || displayedPaused != paused || displayedStyle != style else { return statusChanged }
        if displayedStyle != style { expireUndo() }
        displayedQuery = query; displayedPaused = paused; displayedStyle = style
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
        filters.accessibilityValue = query.activeCount > 0 ? InspectorFilterOptions.summary(query, historical: historicalTime) : nil
        criteria.text = InspectorFilterOptions.summary(query, historical: historicalTime)
        criteria.isHidden = query.activeCount == 0
        if compact { updateTokens(query); status.isHidden = status.text?.isEmpty != false }
        return true
    }
    private func updateTokens(_ query: InspectorQuery) {
        if let removed, removed.after != query { expireUndo() }
        undo.setTitle(InspectorLocalization.text("ui_undo_filter"), for: .normal)
        undo.tintColor = InspectorTheme.accent
        tokenScroll.isHidden = query.activeCount == 0
        let style = InspectorLocalization.text("ui_remove_filter") + InspectorPreferences.shared.appearance.rawValue + InspectorPreferences.shared.palette.rawValue + traitCollection.preferredContentSizeCategory.rawValue
        guard tokenQuery != query || tokenStyle != style else { return }
        tokenQuery = query; tokenStyle = style; measuredTokenWidth = 0
        for view in tokens.arrangedSubviews { tokens.removeArrangedSubview(view); view.removeFromSuperview() }
        func add(_ title: String, target: InspectorQuery.Target, changed: InspectorQuery?) {
            let group = UIStackView()
            group.backgroundColor = InspectorTheme.tint; group.layer.cornerRadius = 18; group.clipsToBounds = true
            let edit = InspectorFilterToken { [weak self] in self?.onFilters?(target) }
            edit.setTitle(title, for: .normal)
            edit.accessibilityLabel = title; edit.accessibilityHint = InspectorLocalization.text("ui_edit_filter")
            edit.titleLabel?.numberOfLines = 0; edit.titleLabel?.lineBreakMode = .byWordWrapping
            group.addArrangedSubview(edit); tokens.addArrangedSubview(group)
            if let changed {
                let close = InspectorFilterToken { [weak self] in self?.replace(query, with: changed) }
                close.setImage(UIImage(systemName: "xmark"), for: .normal)
                close.widthAnchor.constraint(equalToConstant: 44).isActive = true
                close.accessibilityLabel = InspectorLocalization.text("ui_remove_filter") + ", " + title
                group.addArrangedSubview(close)
            }
        }
        for field in InspectorQuery.fieldOrder {
            guard let values = query.values[field], !values.isEmpty else { continue }
            var changed = query
            if field == .transport { changed.select(.transport, value: nil) } else { changed.values[field] = nil }
            add(InspectorFilterOptions.title(field, transport: query.transport) + ": " + InspectorFilterOptions.selectionSummary(values) { InspectorFilterOptions.label(field, $0) }, target: .field(field), changed: field == .transport && query.hasTransportConditions ? nil : changed)
        }
        if query.recentMinutes > 0 { var changed = query; changed.recentMinutes = 0; add(InspectorFilterOptions.time(query.recentMinutes, historical: historicalTime), target: .time, changed: changed) }
        if !query.excludedText.isEmpty { var changed = query; changed.excludedText = ""; add(InspectorLocalization.text("ui_exclude") + ": " + query.excludedText, target: .excludedText, changed: changed) }
        if !query.fieldPath.isEmpty { var changed = query; changed.fieldPath = ""; changed.fieldValue = ""; add(InspectorFilterOptions.jsonCondition(path: query.fieldPath, value: query.fieldValue), target: .json, changed: changed) }
        if query.minimumDurationMillis > 0 { var changed = query; changed.minimumDurationMillis = 0; add("HTTP ≥ \(query.minimumDurationMillis) ms", target: .duration, changed: changed) }
    }
    override func layoutSubviews() {
        super.layoutSubviews()
        let width = tokenScroll.bounds.width
        guard compact, !tokenScroll.isHidden, width > 0, width != measuredTokenWidth else { return }
        measuredTokenWidth = width
        var height: CGFloat = 44
        for case let group as UIStackView in tokens.arrangedSubviews {
            guard let edit = group.arrangedSubviews.first as? InspectorFilterToken else { continue }
            edit.fitTitle(in: width - (group.arrangedSubviews.count > 1 ? 44 : 0))
            height = max(height, edit.intrinsicContentSize.height)
        }
        tokenHeight.constant = height
    }
    lazy var adjustFiltersButton: UIButton = {
        let button = UIButton(type: .system)
        button.addTarget(self, action: #selector(openFilters), for: .touchUpInside)
        button.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        return button
    }()
    @objc private func openFilters() { onFilters?(nil) }
    func recordMenu(_ record: InspectorSearchRecord, recent: InspectorRecentFilters? = nil) -> UIContextMenuConfiguration? {
        let field: InspectorQuery.Field, value: String
        switch record {
        case .log(let event): field = .category; value = event.category
        case .http(let event): field = .host; value = InspectorQuery.host(event.url)
        case .socket(let event): field = .host; value = InspectorQuery.host(event.url)
        }
        return recordMenu(field: field, value: value, recent: recent)
    }
    func recordMenu(field: InspectorQuery.Field, value: String, recent: InspectorRecentFilters? = nil) -> UIContextMenuConfiguration? {
        guard !value.isEmpty else { return nil }
        return UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { [weak self] _ in
            let actions = [false, true].map { excluding in
                let key = "ui_" + (excluding ? "exclude_" : "only_") + field.rawValue
                return UIAction(title: InspectorLocalization.text(key)) { [weak self] _ in
                    guard let self, let original = self.displayedQuery else { return }
                    var query = original; query.filterRecord(field, value: value, excluding: excluding)
                    if query != original { recent?.update(query); self.replace(original, with: query) }
                }
            }
            return UIMenu(title: InspectorFilterOptions.label(field, value), children: actions)
        }
    }
    private func replace(_ original: InspectorQuery, with changed: InspectorQuery) {
        guard displayedQuery == original else { return }
        NSObject.cancelPreviousPerformRequests(withTarget: self, selector: #selector(expireUndo), object: nil)
        removed = (original, changed)
        undoNotice.backgroundColor = InspectorTheme.surface
        (undoNotice.arrangedSubviews.first as? UILabel)?.text = InspectorLocalization.text("ui_filter_undo_notice")
        feedbackTable?.filterFeedback = undoNotice
        perform(#selector(expireUndo), with: nil, afterDelay: UIAccessibility.isVoiceOverRunning ? 15 : 6)
        onQueryChange?(changed)
    }
    @objc private func undoRemoval() {
        guard let removed, displayedQuery == removed.after else { expireUndo(); return }
        expireUndo(); onQueryChange?(removed.before)
    }
    @objc func expireUndo() {
        NSObject.cancelPreviousPerformRequests(withTarget: self, selector: #selector(expireUndo), object: nil)
        guard removed != nil else { return }
        removed = nil
        if feedbackTable?.filterFeedback === undoNotice { feedbackTable?.filterFeedback = nil }
    }
    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window == nil { expireUndo() }
    }
    private func configure(_ button: UIButton, symbol: String, selected: Bool = false, title: String? = nil) {
        guard #available(iOS 15, *) else {
            button.setImage(UIImage(systemName: symbol), for: .normal); button.setTitle(title, for: .normal)
            button.tintColor = InspectorTheme.accent; button.backgroundColor = selected ? InspectorTheme.tint : InspectorTheme.surface
            button.layer.cornerRadius = 22; button.contentEdgeInsets = UIEdgeInsets(top: 10, left: 12, bottom: 10, right: 12)
            return
        }
        var configuration: UIButton.Configuration
        let symbolImage = UIImage(systemName: symbol)
        let accent = InspectorTheme.accent, disabled = InspectorTheme.secondary.withAlphaComponent(0.4)
        // The floating latest action retains its existing reading surface. Inline
        // search/filter actions use the same system material as navigation actions.
        if #available(iOS 26, *), (!compact || button !== pause), InspectorTheme.allowsTransparentMaterial {
            configuration = .glass()
            configuration.baseBackgroundColor = selected ? InspectorTheme.tint : nil
            // Glass can replace a template symbol's tint even with a color
            // transformer. Preserve the palette in the symbol itself; UIKit
            // still owns the material, layout and native state feedback.
            let enabledImage = symbolImage?.withTintColor(accent, renderingMode: .alwaysOriginal)
            let disabledImage = symbolImage?.withTintColor(disabled, renderingMode: .alwaysOriginal)
            configuration.image = button.isEnabled ? enabledImage : disabledImage
            button.configurationUpdateHandler = { button in
                button.configuration?.image = button.isEnabled ? enabledImage : disabledImage
            }
        } else {
            configuration = .plain()
            configuration.background.backgroundColor = selected ? InspectorTheme.tint : InspectorTheme.surface
            configuration.image = symbolImage
            button.configurationUpdateHandler = nil
        }
        configuration.preferredSymbolConfigurationForImage = UIImage.SymbolConfiguration(pointSize: 17, weight: .medium)
        configuration.title = title; configuration.imagePadding = 5
        configuration.titleLineBreakMode = .byTruncatingTail
        let font = UIFont.preferredFont(forTextStyle: .subheadline, compatibleWith: traitCollection)
        configuration.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { [weak button] attributes in
            var attributes = attributes; attributes.font = font
            attributes.foregroundColor = button?.isEnabled == false ? disabled : accent
            return attributes
        }
        configuration.baseForegroundColor = InspectorTheme.accent
        button.tintColor = InspectorTheme.accent
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
final class InspectorFilterToken: UIButton {
    private let operation: () -> Void
    private var titleSize: CGSize?
    override var intrinsicContentSize: CGSize { titleSize ?? super.intrinsicContentSize }
    override var isHighlighted: Bool {
        didSet { backgroundColor = isHighlighted ? .tertiarySystemFill : .clear }
    }
    func fitTitle(in available: CGFloat) {
        guard let label = titleLabel, currentTitle != nil else { return }
        let insets = contentEdgeInsets
        let natural = ceil(label.sizeThatFits(CGSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude)).width)
        // Very long tokens can scroll horizontally without taking over the feed vertically.
        let width = min(natural, max(ceil(natural / 2), available - insets.left - insets.right))
        let height = label.sizeThatFits(CGSize(width: max(1, width), height: CGFloat.greatestFiniteMagnitude)).height
        titleSize = CGSize(width: max(44, width + insets.left + insets.right), height: max(44, ceil(height + insets.top + insets.bottom)))
        invalidateIntrinsicContentSize()
    }
    init(operation: @escaping () -> Void) {
        self.operation = operation; super.init(frame: .zero)
        tintColor = InspectorTheme.accent; setTitleColor(InspectorTheme.accent, for: .normal)
        titleLabel?.font = .preferredFont(forTextStyle: .caption1); titleLabel?.adjustsFontForContentSizeCategory = true
        contentEdgeInsets = UIEdgeInsets(top: 4, left: 10, bottom: 4, right: 10)
        addTarget(self, action: #selector(run), for: .touchUpInside)
    }
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
        if self.badge.text != badge { self.badge.text = badge }
        self.badge.textColor = color ?? InspectorTheme.accent
        let formatted = InspectorValuePresentation.eventTime(time)
        if timestamp.text != formatted { timestamp.text = formatted }
        if primary.text != title { primary.text = title }
        if secondary.text != subtitle { secondary.text = subtitle }
        accessibilityLabel = [badge, title, subtitle, timestamp.text ?? ""].joined(separator: ", ")
    }
}
#endif

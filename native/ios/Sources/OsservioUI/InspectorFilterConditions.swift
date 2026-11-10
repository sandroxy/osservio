#if canImport(UIKit)
import UIKit

/// Keeps the draft's editors alive while individual conditions expand in place.
@MainActor
final class InspectorFilterConditions: UIStackView {
    let exclude = UITextField(), path = UITextField(), value = UITextField(), duration = UITextField()
    private let excludeSummary = InspectorTheme.label(.subheadline, secondary: true)
    private let fieldSummary = InspectorTheme.label(.subheadline, secondary: true)
    private let excludeEditor = UIStackView(), fieldEditor = UIStackView()
    private let excludeToggle = UIButton(type: .system), fieldToggle = UIButton(type: .system)
    var changed: (() -> Void)?
    var layoutChanged: (() -> Void)?

    init(query: InspectorQuery, delegate: UITextFieldDelegate) {
        super.init(frame: .zero); axis = .vertical; spacing = 8
        let title = InspectorTheme.label(.footnote, secondary: true)
        title.text = InspectorLocalization.text("ui_content_filters"); addArrangedSubview(title)
        let card = UIStackView(); card.axis = .vertical; card.spacing = 0
        card.backgroundColor = InspectorTheme.surface; card.layer.cornerRadius = 20; card.clipsToBounds = true
        for (field, key) in [(exclude, "ui_exclude"), (path, "ui_field_path"), (value, "ui_field_value"), (duration, "ui_duration")] {
            field.borderStyle = .roundedRect; field.placeholder = InspectorLocalization.text(key); field.accessibilityLabel = InspectorLocalization.text(key)
            field.font = .preferredFont(forTextStyle: .body); field.adjustsFontForContentSizeCategory = true
            field.autocorrectionType = .no; field.autocapitalizationType = .none; field.returnKeyType = .done; field.delegate = delegate
            field.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
            field.addTarget(self, action: #selector(edit), for: .editingChanged)
        }
        duration.keyboardType = .numberPad
        configure(excludeToggle, key: "ui_exclude", summary: excludeSummary, editor: excludeEditor, card: card)
        excludeEditor.addArrangedSubview(exclude)
        let separator = UIView(); separator.backgroundColor = .separator
        separator.heightAnchor.constraint(equalToConstant: 0.5).isActive = true; card.addArrangedSubview(separator)
        configure(fieldToggle, key: "ui_json_field", summary: fieldSummary, editor: fieldEditor, card: card)
        let hint = InspectorTheme.label(.caption1, secondary: true); hint.text = InspectorLocalization.text("ui_field_hint")
        fieldEditor.addArrangedSubview(hint); fieldEditor.addArrangedSubview(path); fieldEditor.addArrangedSubview(value)
        addArrangedSubview(card)
        excludeEditor.isHidden = query.excludedText.isEmpty; fieldEditor.isHidden = query.fieldPath.isEmpty && query.fieldValue.isEmpty
        sync(query)
    }
    @available(*, unavailable)
    required init(coder: NSCoder) { fatalError("Programmatic construction only") }
    private func configure(_ button: UIButton, key: String, summary: UILabel, editor: UIStackView, card: UIStackView) {
        button.accessibilityLabel = InspectorLocalization.text(key)
        button.addTarget(self, action: #selector(toggle(_:)), for: .touchUpInside)
        let title = InspectorTheme.label(.body); title.text = InspectorLocalization.text(key)
        let labels = UIStackView(arrangedSubviews: [title, summary]); labels.axis = .vertical; labels.spacing = 4
        labels.isUserInteractionEnabled = false; labels.translatesAutoresizingMaskIntoConstraints = false
        button.addSubview(labels)
        NSLayoutConstraint.activate([labels.leadingAnchor.constraint(equalTo: button.leadingAnchor, constant: 16),
            labels.trailingAnchor.constraint(equalTo: button.trailingAnchor, constant: -44),
            labels.topAnchor.constraint(equalTo: button.topAnchor, constant: 12), labels.bottomAnchor.constraint(equalTo: button.bottomAnchor, constant: -12)])
        let arrow = UIImageView(image: UIImage(systemName: "chevron.down")); arrow.tintColor = InspectorTheme.secondary
        arrow.translatesAutoresizingMaskIntoConstraints = false; arrow.tag = 1; button.addSubview(arrow)
        NSLayoutConstraint.activate([arrow.trailingAnchor.constraint(equalTo: button.trailingAnchor, constant: -16),
            arrow.centerYAnchor.constraint(equalTo: button.centerYAnchor), arrow.widthAnchor.constraint(equalToConstant: 12), arrow.heightAnchor.constraint(equalToConstant: 8)])
        editor.axis = .vertical; editor.spacing = 10; editor.isLayoutMarginsRelativeArrangement = true
        editor.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 0, leading: 16, bottom: 16, trailing: 16)
        card.addArrangedSubview(button); card.addArrangedSubview(editor)
    }
    @objc private func toggle(_ button: UIButton) {
        let editor = button === excludeToggle ? excludeEditor : fieldEditor
        if !editor.isHidden { editor.endEditing(true) }
        editor.isHidden.toggle(); updateSummaries(); layoutChanged?()
    }
    @objc private func edit() {
        guard [exclude, path, value, duration].allSatisfy({ $0.markedTextRange == nil }) else { return }
        for field in [exclude, path, value, duration] {
            let limit = field === path ? 256 : field === duration ? 20 : 512
            if let text = field.text, text.count > limit { field.text = String(text.prefix(limit)) }
        }
        updateSummaries(); changed?()
    }
    func sync(_ query: InspectorQuery) {
        exclude.text = query.excludedText; path.text = query.fieldPath; value.text = query.fieldValue
        duration.text = query.minimumDurationMillis == 0 ? "" : String(query.minimumDurationMillis)
        updateSummaries()
    }
    private func updateSummaries() {
        let empty = InspectorLocalization.text("ui_not_set")
        excludeSummary.text = exclude.text?.isEmpty == false ? String(exclude.text!.prefix(80)) : empty
        fieldSummary.text = path.text?.isEmpty == false ? String((path.text! + " = " + (value.text ?? "")).prefix(120)) : empty
        for (button, summary, editor) in [(excludeToggle, excludeSummary, excludeEditor), (fieldToggle, fieldSummary, fieldEditor)] {
            summary.numberOfLines = 2; button.accessibilityValue = summary.text
            (button.viewWithTag(1) as? UIImageView)?.image = UIImage(systemName: editor.isHidden ? "chevron.down" : "chevron.up")
        }
        layoutChanged?()
    }
}
#endif

#if canImport(UIKit)
import UIKit

/// The first row of a searchable section. Results remain reusable sibling rows.
@MainActor
final class InspectorSearchGroupCell: UITableViewCell {
    private let heading = InspectorTheme.label(.headline)
    private let summary = InspectorTheme.label(.caption1, secondary: true)
    private let message = InspectorTheme.label(.subheadline, secondary: true)
    private let search: UISearchTextField

    init(search: UISearchTextField) {
        self.search = search
        super.init(style: .default, reuseIdentifier: nil)
        selectionStyle = .none; backgroundColor = InspectorTheme.surface
        heading.accessibilityTraits.insert(.header)
        search.backgroundColor = InspectorTheme.canvas
        search.layer.cornerRadius = 12; search.clipsToBounds = true
        let stack = UIStackView(arrangedSubviews: [heading, summary, search, message])
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
    func configure(title: String, summary: String?, searchable: Bool = true, message: String?) {
        heading.text = title
        self.summary.text = summary; self.summary.isHidden = summary?.isEmpty != false
        search.isHidden = !searchable
        self.message.text = message; self.message.isHidden = message == nil
    }
}
#endif

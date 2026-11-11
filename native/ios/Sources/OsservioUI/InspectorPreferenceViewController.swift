#if canImport(UIKit)
import UIKit

/// Preferences are pages, with an explicit selected value and a shared minimize action.
@MainActor
final class InspectorPreferenceViewController: UITableViewController {
    private let language: Bool
    private lazy var style = InspectorStyleObserver { [weak self] in self?.relabel() }
    init(language: Bool) { self.language = language; super.init(style: .insetGrouped) }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func viewDidLoad() {
        super.viewDidLoad(); tableView.rowHeight = UITableView.automaticDimension; tableView.estimatedRowHeight = 56
        style.changed()
    }
    private func relabel() {
        InspectorTheme.apply(self); title = InspectorLocalization.text(language ? "ui_language" : "ui_appearance")
        tableView.reloadData()
    }
    override func numberOfSections(in tableView: UITableView) -> Int { language ? 1 : 2 }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { section == 0 ? 2 : 1 }
    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        language ? nil : InspectorLocalization.text(section == 0 ? "ui_display_mode" : "ui_palette")
    }
    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        section == (language ? 0 : 1) ? InspectorLocalization.text("ui_preferences_hint") : nil
    }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = UITableViewCell(style: .default, reuseIdentifier: nil)
        if sectionIsPalette(indexPath.section) {
            cell.selectionStyle = .none; cell.backgroundColor = InspectorTheme.surface
            let grid = InspectorPaletteGrid(); grid.translatesAutoresizingMaskIntoConstraints = false
            cell.contentView.addSubview(grid)
            NSLayoutConstraint.activate([
                grid.leadingAnchor.constraint(equalTo: cell.contentView.leadingAnchor, constant: 16),
                grid.trailingAnchor.constraint(equalTo: cell.contentView.trailingAnchor, constant: -16),
                grid.topAnchor.constraint(equalTo: cell.contentView.topAnchor, constant: 12),
                grid.bottomAnchor.constraint(equalTo: cell.contentView.bottomAnchor, constant: -12)
            ])
            return cell
        }
        let keys = language ? ["ui_chinese", "ui_english"] : ["ui_light", "ui_dark"]
        cell.textLabel?.text = InspectorLocalization.text(keys[indexPath.row]); cell.backgroundColor = InspectorTheme.surface
        cell.textLabel?.font = .preferredFont(forTextStyle: .body); cell.textLabel?.adjustsFontForContentSizeCategory = true
        cell.textLabel?.numberOfLines = 0
        let selected = language ? InspectorPreferences.shared.language == .english : InspectorPreferences.shared.appearance == .dark
        cell.accessoryType = indexPath.row == (selected ? 1 : 0) ? .checkmark : .none
        cell.accessibilityTraits = cell.accessoryType == .checkmark ? [.button, .selected] : .button
        return cell
    }
    private func sectionIsPalette(_ section: Int) -> Bool { !language && section == 1 }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        guard !sectionIsPalette(indexPath.section) else { return }
        if language { InspectorPreferences.shared.language = indexPath.row == 0 ? .chinese : .english }
        else { InspectorPreferences.shared.appearance = indexPath.row == 0 ? .light : .dark }
    }
}
#endif

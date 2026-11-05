#if canImport(UIKit)
import OsservioCore
import UIKit

/// Environment reads are explicit and one-shot. Hiding the inspector does not enable monitoring.
@MainActor
final class EnvironmentInspectorViewController: UITableViewController {
    private let provider: any EnvironmentProvider
    private var snapshot: EnvironmentSnapshot?
    private var reading = false
    private var notice = RuntimePresentation.text("runtime_environment_hint")
    private let keys = EnvironmentKey.allCases

    init(provider: any EnvironmentProvider) { self.provider = provider; super.init(style: .insetGrouped) }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func viewDidLoad() {
        super.viewDidLoad()
        tableView.rowHeight = UITableView.automaticDimension
        tableView.estimatedRowHeight = 64
    }
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        if snapshot == nil && !reading { read() }
    }
    private func read() {
        guard !reading else { return }
        reading = true; notice = RuntimePresentation.text("runtime_reading"); tableView.reloadData()
        provider.read { [weak self] result in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.reading = false
                switch result {
                case .success(let value):
                    self.snapshot = value
                    self.notice = RuntimePresentation.text("runtime_snapshot") + " · " + RuntimePresentation.date(value.timestampMillis)
                case .failure(let error):
                    self.notice = RuntimePresentation.text(error == .busy ? "busy" : "runtime_read_failed")
                    if let snapshot = self.snapshot { self.notice += "\n" + RuntimePresentation.text("runtime_previous") + " · " + RuntimePresentation.date(snapshot.timestampMillis) }
                }
                self.tableView.reloadData()
            }
        }
    }
    override func numberOfSections(in tableView: UITableView) -> Int { 2 }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { section == 0 ? 2 : snapshot == nil ? 0 : keys.count }
    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        section == 0 ? notice : RuntimePresentation.text("runtime_environment_hint")
    }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
        cell.textLabel?.font = .preferredFont(forTextStyle: .body)
        cell.textLabel?.adjustsFontForContentSizeCategory = true; cell.textLabel?.numberOfLines = 0
        cell.detailTextLabel?.font = .preferredFont(forTextStyle: .caption1)
        cell.detailTextLabel?.adjustsFontForContentSizeCategory = true; cell.detailTextLabel?.numberOfLines = 0
        if indexPath.section == 0 {
            let enabled = indexPath.row == 0 ? !reading : snapshot != nil
            cell.textLabel?.text = RuntimePresentation.text(indexPath.row == 0 ? "http_refresh" : "runtime_copy_environment")
            cell.textLabel?.textColor = enabled ? view.tintColor : .secondaryLabel
            cell.isUserInteractionEnabled = enabled
            cell.accessibilityTraits.insert(.button)
            if !enabled { cell.accessibilityTraits.insert(.notEnabled) }
        } else if let snapshot {
            let key = keys[indexPath.row]
            cell.textLabel?.text = RuntimePresentation.text("env_" + key.rawValue)
            cell.detailTextLabel?.text = RuntimePresentation.value(snapshot.values[key]!, key: key)
            cell.selectionStyle = .none
        }
        return cell
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        guard indexPath.section == 0 else { return }
        if indexPath.row == 0 { read() }
        else if let snapshot { UIPasteboard.general.string = RuntimePresentation.environment(snapshot) }
    }
}
#endif

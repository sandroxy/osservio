#if canImport(UIKit)
import OsservioCore
import UIKit

/// Presents a frozen attachment. No provider is queried while reviewing saved evidence.
@MainActor
final class EnvironmentSnapshotViewController: UITableViewController {
    private let snapshot: EnvironmentSnapshot
    init(snapshot: EnvironmentSnapshot) { self.snapshot = snapshot; super.init(style: .insetGrouped) }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func viewDidLoad() {
        super.viewDidLoad(); title = RuntimePresentation.text("runtime_environment")
        tableView.rowHeight = UITableView.automaticDimension; tableView.estimatedRowHeight = 64
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: RuntimePresentation.text("runtime_copy_environment"), style: .plain,
            target: self, action: #selector(copySnapshot))
    }
    @objc private func copySnapshot() { UIPasteboard.general.string = RuntimePresentation.environment(snapshot) }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { EnvironmentKey.allCases.count }
    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        snapshot.platform.rawValue + " · " + RuntimePresentation.date(snapshot.timestampMillis)
    }
    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? { RuntimePresentation.text("incident_snapshot_hint") }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "environment-fact") ?? UITableViewCell(style: .subtitle, reuseIdentifier: "environment-fact")
        PersistencePresentation.style(cell); cell.selectionStyle = .none
        let key = EnvironmentKey.allCases[indexPath.row]
        cell.textLabel?.text = RuntimePresentation.text("env_" + key.rawValue)
        cell.detailTextLabel?.text = snapshot.values[key].map { RuntimePresentation.value($0, key: key) }
        return cell
    }
}
#endif

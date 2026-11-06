#if canImport(UIKit)
import OsservioCore
import UIKit

/// Source facts from the saved snapshot; no live query or inferred historical context.
@MainActor
final class SystemDiagnosticViewController: UITableViewController {
    private let diagnostic: SystemDiagnostic
    private let fields: [(title: String, value: String)]
    init(_ diagnostic: SystemDiagnostic) {
        self.diagnostic = diagnostic; fields = SystemDiagnosticPresentation.fields(diagnostic)
        super.init(style: .insetGrouped)
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func viewDidLoad() {
        super.viewDidLoad(); title = RuntimePresentation.text("diagnostic_title")
        tableView.rowHeight = UITableView.automaticDimension; tableView.estimatedRowHeight = 64
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: RuntimePresentation.text("archive_copy_summary"), style: .plain,
            target: self, action: #selector(copyDiagnostic))
    }
    @objc private func copyDiagnostic() { UIPasteboard.general.string = SystemDiagnosticPresentation.summary(diagnostic) }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { fields.count }
    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? { SystemDiagnosticPresentation.hint(diagnostic) }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "diagnostic-fact") ?? UITableViewCell(style: .subtitle, reuseIdentifier: "diagnostic-fact")
        PersistencePresentation.style(cell); cell.selectionStyle = .none; cell.accessibilityTraits = .staticText
        cell.textLabel?.text = fields[indexPath.row].title; cell.detailTextLabel?.text = fields[indexPath.row].value
        return cell
    }
}
#endif

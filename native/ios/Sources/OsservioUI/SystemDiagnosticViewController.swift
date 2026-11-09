#if canImport(UIKit)
import OsservioCore
import UIKit

/// Source facts from the saved snapshot; no live query or inferred historical context.
@MainActor
final class SystemDiagnosticViewController: UITableViewController {
    private let diagnostic: SystemDiagnostic
    private var fields: [(title: String, value: String)]
    init(_ diagnostic: SystemDiagnostic) {
        self.diagnostic = diagnostic; fields = SystemDiagnosticPresentation.fields(diagnostic)
        super.init(style: .insetGrouped)
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func viewDidLoad() {
        super.viewDidLoad(); InspectorTheme.apply(self); title = RuntimePresentation.text("diagnostic_title")
        tableView.rowHeight = UITableView.automaticDimension; tableView.estimatedRowHeight = 64
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: RuntimePresentation.text("archive_copy_summary"), style: .plain,
            target: self, action: #selector(copyDiagnostic))
    }
    @objc private func copyDiagnostic() { UIPasteboard.general.string = SystemDiagnosticPresentation.summary(diagnostic) }
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated); InspectorTheme.apply(self)
        title = RuntimePresentation.text("diagnostic_title"); fields = SystemDiagnosticPresentation.fields(diagnostic)
        navigationItem.rightBarButtonItem?.title = RuntimePresentation.text("archive_copy_summary"); tableView.reloadData()
    }
    override func numberOfSections(in tableView: UITableView) -> Int { diagnostic.appleSignal != nil || diagnostic.androidExit?.tombstone != nil ? 2 : 1 }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        section == 0 ? fields.count : diagnostic.appleSignal == nil ? 1 : 2
    }
    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        section == 0 ? SystemDiagnosticPresentation.hint(diagnostic) : nil
    }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "diagnostic-fact") ?? UITableViewCell(style: .subtitle, reuseIdentifier: "diagnostic-fact")
        PersistencePresentation.style(cell); cell.accessoryType = .none
        if indexPath.section == 1, let crash = diagnostic.appleSignal {
            let count = indexPath.row == 0 ? crash.frames.count : crash.images.count
            cell.textLabel?.text = RuntimePresentation.text(indexPath.row == 0 ? "crash_frames" : "crash_images")
            cell.detailTextLabel?.text = count == 0 ? RuntimePresentation.text("runtime_unavailable") : String(count)
            cell.accessoryType = count == 0 ? .none : .disclosureIndicator
            cell.selectionStyle = count == 0 ? .none : .default; cell.accessibilityTraits = count == 0 ? .staticText : .button
        } else if indexPath.section == 1, let trace = diagnostic.androidExit?.tombstone {
            cell.textLabel?.text = RuntimePresentation.text("crash_frames")
            cell.detailTextLabel?.text = SignalCrashPresentation.count(trace.frames.count, limited: trace.frameLimitReached)
            cell.accessoryType = trace.frames.isEmpty ? .none : .disclosureIndicator
            cell.selectionStyle = trace.frames.isEmpty ? .none : .default; cell.accessibilityTraits = trace.frames.isEmpty ? .staticText : .button
        } else {
            cell.selectionStyle = .none; cell.accessibilityTraits = .staticText
            cell.textLabel?.text = fields[indexPath.row].title; cell.detailTextLabel?.text = fields[indexPath.row].value
        }
        return cell
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        guard indexPath.section == 1 else { return }
        if let crash = diagnostic.appleSignal, !(indexPath.row == 0 ? crash.frames.isEmpty : crash.images.isEmpty) {
            navigationController?.pushViewController(SignalCrashViewController(crash, content: indexPath.row == 0 ? .frames : .images), animated: true)
        } else if let trace = diagnostic.androidExit?.tombstone, !trace.frames.isEmpty {
            navigationController?.pushViewController(SignalCrashViewController(trace), animated: true)
        }
    }
}
#endif

#if canImport(UIKit)
import OsservioCore
import UIKit

/// A frozen attachment never queries the current provider to fill absent historical facts.
@MainActor
final class EnvironmentSnapshotViewController: InspectorDetailViewController {
    private let snapshot: EnvironmentSnapshot
    init(snapshot: EnvironmentSnapshot) { self.snapshot = snapshot; super.init() }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated); title = InspectorLocalization.text("runtime_environment")
        inspectorPrimaryAction = UIBarButtonItem(title: InspectorLocalization.text("runtime_copy_environment"), style: .plain, target: self, action: #selector(copySnapshot))
        sections = [Section(title: InspectorLocalization.text("ui_snapshot_time"), rows: [
            Row(InspectorLocalization.date(snapshot.timestampMillis) + " · " + snapshot.platform.rawValue)
        ], footer: InspectorLocalization.text("incident_snapshot_hint"))] + EnvironmentPresentation.sections(snapshot)
    }
    @objc private func copySnapshot() { UIPasteboard.general.string = RuntimePresentation.environment(snapshot) }
}
#endif

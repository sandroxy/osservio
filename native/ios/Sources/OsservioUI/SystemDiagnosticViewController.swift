#if canImport(UIKit)
import OsservioCore
import UIKit

/// Prioritizes source facts; technical identifiers remain available without a live query.
@MainActor
final class SystemDiagnosticViewController: InspectorDetailViewController {
    private let diagnostic: SystemDiagnostic
    init(_ diagnostic: SystemDiagnostic) { self.diagnostic = diagnostic; super.init() }
    @available(*, unavailable) required init?(coder: NSCoder) { return nil }
    override func viewDidLoad() { super.viewDidLoad(); render() }
    override func viewWillAppear(_ animated: Bool) { super.viewWillAppear(animated); render() }
    private func render() {
        title = RuntimePresentation.text("diagnostic_title")
        inspectorPrimaryAction = inspectorCopyItem(target: self, action: #selector(copyDiagnostic), label: "archive_copy_summary")
        let fields = SystemDiagnosticPresentation.fields(diagnostic)
        sections = [Section(title: RuntimePresentation.text("ui_overview"), rows: fields.filter(\.primary).map { Row($0.title, $0.value) }, footer: SystemDiagnosticPresentation.hint(diagnostic))]
        var evidence: [Row] = []
        if let crash = diagnostic.appleSignal {
            for images in [false, true] {
                let count = images ? crash.images.count : crash.frames.count
                evidence.append(Row(RuntimePresentation.text(images ? "crash_images" : "crash_frames"), count == 0 ? RuntimePresentation.text("runtime_unavailable") : String(count), action: count == 0 ? nil : { [weak self] in
                    self?.inspectorPush(SignalCrashViewController(crash, content: images ? .images : .frames))
                }))
            }
        } else if let trace = diagnostic.androidExit?.tombstone {
            evidence.append(Row(RuntimePresentation.text("crash_frames"), SignalCrashPresentation.count(trace.frames.count, limited: trace.frameLimitReached), action: trace.frames.isEmpty ? nil : { [weak self] in self?.inspectorPush(SignalCrashViewController(trace)) }))
        }
        if !evidence.isEmpty { sections.append(Section(title: RuntimePresentation.text("ui_evidence_details"), rows: evidence)) }
        sections.append(Section(title: RuntimePresentation.text("ui_technical_details"), rows: fields.filter { !$0.primary }.map { Row($0.title, $0.value) }, collapsible: true))
    }
    @objc private func copyDiagnostic() { UIPasteboard.general.string = SystemDiagnosticPresentation.summary(diagnostic) }
}
#endif

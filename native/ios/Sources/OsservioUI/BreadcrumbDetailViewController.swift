#if canImport(UIKit)
import OsservioCore
import UIKit

/// A frozen admitted event, shared by live history and saved evidence.
@MainActor
final class BreadcrumbDetailViewController: InspectorDetailViewController {
    private let event: BreadcrumbEvent
    private var technicalExpanded = false
    private lazy var style = InspectorStyleObserver { [weak self] in self?.render() }
    init(event: BreadcrumbEvent) { self.event = event; super.init() }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func viewDidLoad() { super.viewDidLoad(); style.changed() }
    private func render() {
        InspectorTheme.apply(self); title = RuntimePresentation.kind(event)
        let copy = UIBarButtonItem(image: UIImage(systemName: "doc.on.doc"), style: .plain, target: self, action: #selector(copySnapshot))
        copy.accessibilityLabel = InspectorLocalization.text("ui_copy_all"); inspectorPrimaryAction = copy
        var overview = [Row(InspectorLocalization.text("ui_event_type"), RuntimePresentation.kind(event)),
            Row(InspectorLocalization.text("ui_event_time"), RuntimePresentation.date(event.timestampMillis)),
            Row(InspectorLocalization.text("crumb_label"), event.label),
            Row(InspectorLocalization.text("crumb_elapsed"), event.sessionElapsedMillis.map { "\($0) ms" } ?? InspectorLocalization.text("runtime_unavailable"))]
        if let value = event.value { overview.append(Row(InspectorLocalization.text("crumb_value"), String(value))) }
        var result = [Section(title: InspectorLocalization.text("ui_overview"), rows: overview,
                              footer: InspectorLocalization.text("crumb_detail_hint"))]
        if let message = event.message {
            let preview = String(message.prefix(320)) + (message.count > 320 ? "…" : "")
            let flags = [event.isPrivate ? InspectorLocalization.text("crumb_private") : "", event.isTruncated ? InspectorLocalization.text("truncated") : ""].filter { !$0.isEmpty }.joined(separator: " · ")
            result.append(Section(title: InspectorLocalization.text("ui_event_message"), rows: [Row(InspectorLocalization.text("ui_open_reader"), preview) { [weak self] in
                guard let self else { return }
                self.read(InspectorLocalization.text("ui_event_message"), content: InspectorReadContent(text: message, notice: flags, allowsJSON: !self.event.isPrivate && !self.event.isTruncated))
            }], footer: flags.isEmpty ? nil : flags))
        }
        result.append(Section(title: InspectorLocalization.text("crumb_retention"), rows: [Row(InspectorLocalization.text(event.retention == .restartRelevant ? "crumb_restart_relevant" : "crumb_memory_only"))]))
        var technical = [Row(InspectorLocalization.text(technicalExpanded ? "ui_hide_identifiers" : "ui_show_identifiers")) { [weak self] in
            guard let self else { return }; self.technicalExpanded.toggle(); self.render()
        }]
        if technicalExpanded {
            technical += [Row("ID", event.id), Row(InspectorLocalization.text("crumb_session"), event.sessionID), Row(InspectorLocalization.text("crumb_sequence"), String(event.sequence))]
        }
        result.append(Section(title: InspectorLocalization.text("ui_technical_details"), rows: technical)); sections = result
    }
    @objc private func copySnapshot() { UIPasteboard.general.string = RuntimePresentation.breadcrumb(event) }
}
#endif

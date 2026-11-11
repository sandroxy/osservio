#if canImport(UIKit)
import OsservioCore
import UIKit

/// Standalone WebSocket viewer with the same queries and lifecycle as the Network destination.
@MainActor
public final class WebSocketInspectorViewController: UITableViewController {
    private let content: NetworkInspectorContent
    public init(recorder: WebSocketRecorder, history: any WebSocketHistory) {
        content = NetworkInspectorContent(sockets: (recorder, history)); super.init(style: .plain)
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    public override func loadView() { super.loadView(); tableView = content.table }
    public override func viewDidLoad() { super.viewDidLoad(); content.attach(to: self) }
    public override func viewWillAppear(_ animated: Bool) { super.viewWillAppear(animated); content.show() }
    public override func viewDidAppear(_ animated: Bool) { super.viewDidAppear(animated); content.show() }
    public override func viewWillDisappear(_ animated: Bool) { super.viewWillDisappear(animated); content.hide() }
}

@MainActor
final class WebSocketDetailViewController: InspectorDetailViewController {
    var showConnection: (() -> Void)?
    private let event: WebSocketEvent
    private let history: (any WebSocketHistory)?
    private let savedPayloads: [String: IncidentPayload]?
    private let selection = InspectorScopeControl(titles: ["", ""])
    private var noticeKey: String?
    private lazy var copyButton = UIBarButtonItem(image: UIImage(systemName: "doc.on.doc"), style: .plain, target: self, action: #selector(copyMetadata))
    init(event: WebSocketEvent, history: any WebSocketHistory) {
        self.event = event; self.history = history; savedPayloads = nil; super.init()
    }
    init(event: WebSocketEvent, savedPayloads: [String: IncidentPayload]) {
        self.event = event; self.savedPayloads = savedPayloads; history = nil; noticeKey = "incident_frozen_detail"; super.init()
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func viewDidLoad() {
        super.viewDidLoad()
        inspectorActions = [copyButton]
        let header = UIStackView(arrangedSubviews: [selection]); header.axis = .vertical; header.isLayoutMarginsRelativeArrangement = true
        header.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 12, leading: 16, bottom: 8, trailing: 16)
        tableView.tableHeaderView = header; selection.onSelect = { [weak self] _ in self?.render() }; render()
    }
    override func viewWillAppear(_ animated: Bool) { super.viewWillAppear(animated); render() }
    private func render() {
        title = InspectorLocalization.text(event.kind == .message || event.kind == .messageResult ? "ui_message_details" : "ui_connection_event")
        selection.setTitles(["ui_overview", "ws_payload"].map(InspectorLocalization.text))
        copyButton.accessibilityLabel = InspectorLocalization.text("ui_copy_metadata")
        func field(_ key: String, _ value: String?) -> Row { Row(InspectorLocalization.text(key), value ?? InspectorLocalization.text("http_none")) }
        selection.isHidden = event.payload == nil
        if selection.selectedIndex == 0 {
            var overview = [Row(WebSocketPresentation.summary(event)),
                Row(InspectorLocalization.text("ui_request_url"), InspectorRecordPresentation.address(event.url), action: { [weak self] in
                    guard let self else { return }; self.read(titleKey: "ui_request_url", content: InspectorReadContent(text: self.event.url, context: { [event = self.event] in InspectorRecordPresentation.context(event) }))
                }), field("ws_time", InspectorValuePresentation.eventTime(event.timestampMillis, full: true)),
                Row(InspectorLocalization.text(showConnection == nil ? "ws_connection" : "ui_connection_context"), event.connectionID, action: showConnection)]
            if let status = event.statusCode { overview.append(field("http_status", String(status))) }
            if let code = event.closeCode { overview.append(field("ws_close_code", String(code))) }
            if let failure = event.failure { overview.append(field("http_failure", InspectorLocalization.text("http_" + failure.rawValue))) }
            if let related = event.relatedMessageID { overview.append(field("ws_related", related)) }
            sections = []
            if event.kind == .message {
                let content = payloadContent()
                sections.append(Section(title: InspectorLocalization.text("ws_payload"), rows: [
                    Row(content.isAvailable ? content.text.isEmpty ? InspectorLocalization.text("ui_content_empty") : String(content.text.prefix(600)) : content.notice,
                        action: content.isAvailable ? { [weak self] in self?.openPayload() } : nil)
                ], footer: content.isAvailable ? content.notice : nil))
            }
            sections += [Section(title: InspectorLocalization.text("ui_overview"), rows: overview, footer: noticeKey.map(InspectorLocalization.text)),
                Section(title: InspectorLocalization.text("ui_technical_details"), rows: [
                    field("http_source", InspectorFilterOptions.label(.source, event.source.rawValue)), field("ws_event", event.id),
                    field("http_session", event.sessionID + " · #\(event.sequence)")
                ], footer: InspectorLocalization.text("ws_evidence_note"), collapsible: true)]
            if !event.headers.isEmpty || event.headersLimited {
                sections.append(Section(title: InspectorLocalization.text("ws_headers"), rows: event.headers.map { Row($0.name, $0.value) },
                    footer: event.headersLimited ? InspectorLocalization.text("http_headers_limited") : nil))
            }
        } else {
            let content = payloadContent()
            sections = [Section(title: InspectorLocalization.text("ws_payload"), rows: [
                Row(content.isAvailable ? content.text.isEmpty ? InspectorLocalization.text("ui_content_empty") : String(content.text.prefix(600)) : content.notice,
                    action: content.isAvailable ? { [weak self] in self?.openPayload() } : nil)
            ], footer: content.isAvailable ? content.notice : nil)]
        }
    }
    private func payloadContent() -> InspectorReadContent {
        var content: InspectorReadContent
        if let savedPayloads { content = WebSocketPresentation.payload(event.payload, savedPayloads: savedPayloads) }
        else if let history { content = WebSocketPresentation.payload(event.payload, history: history) }
        else { content = InspectorReadContent(notice: { InspectorLocalization.text("incident_payload_missing") }) }
        if let noticeKey { content = content.withNoticePrefix { InspectorLocalization.text(noticeKey) } }
        content.context = { [event] in InspectorRecordPresentation.context(event) }
        return content
    }
    private func openPayload() { read(titleKey: "ws_payload", content: payloadContent()) }
    @objc private func copyMetadata() { UIPasteboard.general.string = WebSocketPresentation.overview(event) }
}
#endif

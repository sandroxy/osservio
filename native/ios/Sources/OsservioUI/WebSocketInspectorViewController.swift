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
        title = WebSocketPresentation.kind(event)
        selection.setTitles(["ui_overview", "ws_payload"].map(InspectorLocalization.text))
        copyButton.accessibilityLabel = InspectorLocalization.text("ui_copy_metadata")
        func field(_ key: String, _ value: String?) -> Row { Row(InspectorLocalization.text(key), value ?? InspectorLocalization.text("http_none")) }
        if selection.selectedIndex == 0 {
            sections = [Section(title: InspectorLocalization.text("ui_overview"), rows: [
                Row(WebSocketPresentation.summary(event)),
                Row(InspectorLocalization.text("ui_request_url"), event.url, action: { [weak self] in guard let self else { return }; self.read("WebSocket", content: self.event.url) }),
                field("ws_time", InspectorLocalization.date(event.timestampMillis)),
                field("http_status", event.statusCode.map(String.init)), field("ws_close_code", event.closeCode.map(String.init)),
                field("http_failure", event.failure.map { InspectorLocalization.text("http_" + $0.rawValue) })
            ], footer: noticeKey.map(InspectorLocalization.text)),
            Section(title: InspectorLocalization.text("ui_metadata"), rows: [
                field("http_source", InspectorFilterOptions.label(.source, event.source.rawValue)),
                field("ws_connection", event.connectionID), field("ws_event", event.id), field("ws_related", event.relatedMessageID),
                field("http_session", event.sessionID + " · #\(event.sequence)")
            ], footer: InspectorLocalization.text("ws_evidence_note")),
            Section(title: InspectorLocalization.text("ws_headers"), rows: event.headers.isEmpty
                ? [Row(InspectorLocalization.text("http_none"))] : event.headers.map { Row($0.name, $0.value) },
                footer: event.headersLimited ? InspectorLocalization.text("http_headers_limited") : nil)]
        } else {
            sections = [Section(title: InspectorLocalization.text("ws_payload"), rows: [
                Row(event.payload.map(WebSocketPresentation.payloadDescription) ?? InspectorLocalization.text("ws_no_payload")),
                Row(InspectorLocalization.text("ui_open_reader"), action: { [weak self] in self?.openPayload() })
            ], footer: noticeKey.map(InspectorLocalization.text))]
        }
    }
    private func openPayload() {
        var content: InspectorReadContent
        if let savedPayloads { content = WebSocketPresentation.payload(event.payload, savedPayloads: savedPayloads) }
        else if let history { content = WebSocketPresentation.payload(event.payload, history: history) }
        else { content = InspectorReadContent(notice: InspectorLocalization.text("incident_payload_missing")) }
        if let noticeKey { content.notice = InspectorLocalization.text(noticeKey) + "\n" + content.notice }
        read(InspectorLocalization.text("ws_payload"), content: content)
    }
    @objc private func copyMetadata() { UIPasteboard.general.string = WebSocketPresentation.overview(event) }
}
#endif

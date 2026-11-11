#if canImport(UIKit)
import OsservioCore
import UIKit

/// Standalone HTTP viewer with the same queries and lifecycle as the Network destination.
@MainActor
public final class HTTPInspectorViewController: UITableViewController {
    private let content: NetworkInspectorContent
    public init(recorder: HTTPRecorder, history: any HTTPHistory) {
        content = NetworkInspectorContent(http: (recorder, history)); super.init(style: .plain)
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
final class HTTPDetailViewController: InspectorDetailViewController {
    private let event: HTTPEvent
    private let history: (any HTTPHistory)?
    private let savedPayloads: [String: IncidentPayload]?
    private let selection = InspectorScopeControl(titles: ["", "", ""])
    private var noticeKey: String?
    private lazy var copyButton = UIBarButtonItem(image: UIImage(systemName: "doc.on.doc"), style: .plain, target: self, action: #selector(copyMetadata))
    init(event: HTTPEvent, history: any HTTPHistory) {
        self.event = event; self.history = history; savedPayloads = nil
        super.init()
    }
    init(event: HTTPEvent, savedPayloads: [String: IncidentPayload]) {
        self.event = event; self.savedPayloads = savedPayloads; history = nil
        noticeKey = "incident_frozen_detail"; super.init()
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func viewDidLoad() {
        super.viewDidLoad()
        inspectorActions = [copyButton]
        let header = UIStackView(arrangedSubviews: [selection]); header.axis = .vertical; header.isLayoutMarginsRelativeArrangement = true
        header.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 12, leading: 16, bottom: 8, trailing: 16)
        tableView.tableHeaderView = header
        selection.onSelect = { [weak self] _ in self?.render() }
        render()
    }
    override func viewWillAppear(_ animated: Bool) { super.viewWillAppear(animated); render() }
    private func render() {
        title = InspectorLocalization.text("ui_request_details")
        selection.setTitles(["ui_overview", "ui_request", "ui_response"].map(InspectorLocalization.text))
        copyButton.accessibilityLabel = InspectorLocalization.text("ui_copy_metadata")
        func field(_ key: String, _ value: String?) -> Row { Row(InspectorLocalization.text(key), value ?? InspectorLocalization.text("http_none")) }
        if selection.selectedIndex == 0 {
            var overview = [
                Row(InspectorLocalization.text("ui_request_url"), InspectorRecordPresentation.address(event.url), action: { [weak self] in self?.openAddress() }),
                field("ui_http_method", event.method), field("ui_state", HTTPPresentation.state(event.state)),
                field("http_time", InspectorValuePresentation.eventTime(event.timestampMillis, full: true))
            ]
            if let status = event.statusCode { overview.append(field("http_status", String(status))) }
            if let duration = event.durationMillis { overview.append(field("http_duration", "\(duration) ms")) }
            if let failure = event.failure { overview.append(field("http_failure", InspectorLocalization.text("http_" + failure.rawValue))) }
            if let final = event.responseURL, final != event.url {
                overview.append(Row(InspectorLocalization.text("http_final_url"), InspectorRecordPresentation.address(final), action: { [weak self] in
                    guard let self else { return }; self.read(titleKey: "http_final_url", content: InspectorReadContent(text: final, context: { [event = self.event] in InspectorRecordPresentation.context(event) }))
                }))
            }
            sections = [Section(title: InspectorLocalization.text("ui_overview"), rows: overview, footer: noticeKey.map(InspectorLocalization.text)),
                Section(title: InspectorLocalization.text("ui_technical_details"), rows: [
                    field("http_source", InspectorFilterOptions.label(.source, event.source.rawValue)),
                    field("http_id", event.id), field("http_session", event.sessionID + " · #\(event.sequence)")
                ], footer: InspectorLocalization.text("http_result_note"), collapsible: true)]
        } else {
            let headers = selection.selectedIndex == 1 ? event.requestHeaders : event.responseHeaders
            let content = bodyContent()
            let preview = content.isAvailable ? content.text.isEmpty ? InspectorLocalization.text("ui_content_empty") : String(content.text.prefix(600)) : content.notice
            sections = [Section(title: InspectorLocalization.text("ui_body"), rows: [
                Row(preview, action: content.isAvailable ? { [weak self] in self?.openBody() } : nil)
            ], footer: content.isAvailable ? content.notice : nil),
                Section(title: InspectorLocalization.text(selection.selectedIndex == 1 ? "http_request_headers" : "http_response_headers"), rows: headers.isEmpty
                    ? [Row(InspectorLocalization.text("ui_headers_empty"))] : headers.map { Row($0.name, $0.value) },
                    footer: event.headersLimited ? InspectorLocalization.text("http_headers_limited") : nil)]
        }
    }
    private func openAddress() {
        read(titleKey: "ui_request_url", content: InspectorReadContent(text: event.url, context: { [event] in InspectorRecordPresentation.context(event) }))
    }
    private func bodyContent() -> InspectorReadContent {
        let reference = selection.selectedIndex == 1 ? event.requestBody : event.responseBody
        var content: InspectorReadContent
        if let savedPayloads { content = HTTPPresentation.body(reference, savedPayloads: savedPayloads) }
        else if let history { content = HTTPPresentation.body(reference, history: history) }
        else { content = InspectorReadContent(notice: InspectorLocalization.text("incident_payload_missing")) }
        if let noticeKey { content.notice = InspectorLocalization.text(noticeKey) + "\n" + content.notice }
        content.context = { [event] in InspectorRecordPresentation.context(event) }
        return content
    }
    private func openBody() {
        read(titleKey: selection.selectedIndex == 1 ? "http_request_body" : "http_response_body", content: bodyContent())
    }
    @objc private func copyMetadata() { UIPasteboard.general.string = HTTPPresentation.overview(event) }
}
#endif

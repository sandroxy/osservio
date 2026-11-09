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
        title = event.method + " · " + (event.statusCode.map(String.init) ?? HTTPPresentation.state(event.state))
        selection.setTitles(["ui_overview", "ui_request", "ui_response"].map(InspectorLocalization.text))
        copyButton.accessibilityLabel = InspectorLocalization.text("ui_copy_metadata")
        func field(_ key: String, _ value: String?) -> Row { Row(InspectorLocalization.text(key), value ?? InspectorLocalization.text("http_none")) }
        if selection.selectedIndex == 0 {
            sections = [Section(title: InspectorLocalization.text("ui_overview"), rows: [
                field("ui_state", HTTPPresentation.state(event.state)), field("http_final_url", event.responseURL),
                Row(InspectorLocalization.text("ui_request_url"), event.url, action: { [weak self] in guard let self else { return }; self.read(self.event.method, content: self.event.url) }),
                field("http_status", event.statusCode.map(String.init)), field("http_duration", event.durationMillis.map { "\($0) ms" }),
                field("http_failure", event.failure.map { InspectorLocalization.text("http_" + $0.rawValue) })
            ], footer: noticeKey.map(InspectorLocalization.text)),
            Section(title: InspectorLocalization.text("ui_metadata"), rows: [
                field("http_time", InspectorLocalization.date(event.timestampMillis)),
                field("http_source", InspectorFilterOptions.label(.source, event.source.rawValue)),
                field("http_id", event.id), field("http_session", event.sessionID + " · #\(event.sequence)")
            ], footer: InspectorLocalization.text(event.source == .host ? "http_host_note" : event.source == .okHttp ? "http_okhttp_note" : "http_urlsession_note")
                + "\n\n" + InspectorLocalization.text("http_result_note"))]
        } else {
            let headers = selection.selectedIndex == 1 ? event.requestHeaders : event.responseHeaders
            let reference = selection.selectedIndex == 1 ? event.requestBody : event.responseBody
            sections = [Section(title: InspectorLocalization.text("ui_headers"), rows: headers.isEmpty
                ? [Row(InspectorLocalization.text("http_none"))] : headers.map { fieldHeader in Row(fieldHeader.name, fieldHeader.value) },
                footer: event.headersLimited ? InspectorLocalization.text("http_headers_limited") : nil),
                Section(title: InspectorLocalization.text("ui_body"), rows: [
                    Row(HTTPPresentation.bodyDescription(reference)),
                    Row(InspectorLocalization.text("ui_open_reader"), action: { [weak self] in self?.openBody() })
                ], footer: noticeKey.map(InspectorLocalization.text))]
        }
    }
    private func openBody() {
        let reference = selection.selectedIndex == 1 ? event.requestBody : event.responseBody
        var content: InspectorReadContent
        if let savedPayloads { content = HTTPPresentation.body(reference, savedPayloads: savedPayloads) }
        else if let history { content = HTTPPresentation.body(reference, history: history) }
        else { content = InspectorReadContent(notice: InspectorLocalization.text("incident_payload_missing")) }
        if let noticeKey { content.notice = InspectorLocalization.text(noticeKey) + "\n" + content.notice }
        read(InspectorLocalization.text(selection.selectedIndex == 1 ? "http_request_body" : "http_response_body"),
            content: content)
    }
    @objc private func copyMetadata() { UIPasteboard.general.string = HTTPPresentation.overview(event) }
}
#endif

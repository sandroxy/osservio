#if canImport(UIKit)
import OsservioCore
import UIKit

/// Metadata is frozen before editing. Opted-in bodies are copied once from those references.
@MainActor
final class IncidentComposerViewController: UITableViewController, UITextViewDelegate {
    private let archive: any IncidentArchive
    private let provider: any EnvironmentProvider
    private let mark: IncidentSnapshot
    private let httpHistory: (any HTTPHistory)?
    private let webSocketHistory: (any WebSocketHistory)?
    private let saved: @MainActor () -> Void
    private let includeEnvironment = UISwitch()
    private let includeBreadcrumbs = UISwitch()
    private let includeNote = UISwitch()
    private let includeLogs = UISwitch()
    private let includeHTTP = UISwitch()
    private let includeHTTPBodies = UISwitch()
    private let includeWebSockets = UISwitch()
    private let includeWebSocketPayloads = UISwitch()
    private let editor = UITextView()
    // Each accessory belongs to one stable cell. Moving a switch between old and replacement
    // cells during a table reload can make UIKit repeatedly reparent it during layout.
    private let environmentCell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
    private let breadcrumbsCell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
    private let noteToggleCell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
    private let editorCell = UITableViewCell(style: .default, reuseIdentifier: nil)
    private let logsCell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
    private let httpCell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
    private let httpBodiesCell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
    private let webSocketsCell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
    private let webSocketPayloadsCell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
    private var httpWithBodies: IncidentHTTP?
    private var socketsWithPayloads: IncidentWebSockets?
    private var httpContentFailed = false
    private var socketContentFailed = false
    private var environment: EnvironmentSnapshot?
    private var environmentPending = true
    private var environmentGeneration = 0
    private var saving = false
    private var notice = ""

    init(mark: IncidentSnapshot, archive: any IncidentArchive, provider: any EnvironmentProvider,
         httpHistory: (any HTTPHistory)? = nil, webSocketHistory: (any WebSocketHistory)? = nil, saved: @escaping @MainActor () -> Void) {
        self.mark = mark; self.archive = archive; self.provider = provider; self.saved = saved
        self.httpHistory = httpHistory; self.webSocketHistory = webSocketHistory
        super.init(style: .insetGrouped)
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func viewDidLoad() {
        super.viewDidLoad(); InspectorTheme.apply(self); title = RuntimePresentation.text("incident_mark")
        tableView.rowHeight = UITableView.automaticDimension; tableView.estimatedRowHeight = 80
        tableView.keyboardDismissMode = .interactive
        navigationItem.leftBarButtonItem = inspectorDismissItem(target: self, action: #selector(cancel))
        inspectorPrimaryAction = UIBarButtonItem(title: RuntimePresentation.text("incident_save"), style: .done, target: self, action: #selector(save))
        includeEnvironment.isOn = true; includeBreadcrumbs.isOn = true; includeNote.isOn = false
        includeEnvironment.accessibilityLabel = RuntimePresentation.text("incident_include_environment")
        includeBreadcrumbs.accessibilityLabel = RuntimePresentation.text("incident_include_breadcrumbs")
        includeNote.accessibilityLabel = RuntimePresentation.text("incident_include_note")
        for control in controls {
            control.onTintColor = InspectorTheme.filledAccent
            control.addTarget(self, action: #selector(optionsChanged), for: .valueChanged)
        }
        editor.delegate = self; editor.font = .preferredFont(forTextStyle: .body); editor.adjustsFontForContentSizeCategory = true
        editor.isScrollEnabled = false; editor.accessibilityLabel = RuntimePresentation.text("incident_note")
        editor.autocorrectionType = .no; editor.spellCheckingType = .no
        editor.heightAnchor.constraint(greaterThanOrEqualToConstant: 120).isActive = true
        for cell in [environmentCell, breadcrumbsCell, noteToggleCell] { PersistencePresentation.style(cell); cell.selectionStyle = .none }
        environmentCell.textLabel?.text = RuntimePresentation.text("incident_include_environment"); environmentCell.accessoryView = includeEnvironment
        breadcrumbsCell.textLabel?.text = RuntimePresentation.text("incident_include_breadcrumbs"); breadcrumbsCell.accessoryView = includeBreadcrumbs
        breadcrumbsCell.detailTextLabel?.text = mark.breadcrumbs == nil ? RuntimePresentation.text("incident_breadcrumbs_failed") : IncidentPresentation.counts(mark.breadcrumbs)
        noteToggleCell.textLabel?.text = RuntimePresentation.text("incident_include_note"); noteToggleCell.accessoryView = includeNote
        configure(logsCell, control: includeLogs, key: "incident_include_logs", selected: mark.logs != nil,
            detail: mark.logs.map { IncidentPresentation.window(time: $0.observedAtMillis, count: $0.events.count, omitted: $0.omittedEventCount, evicted: $0.sourceEvictedCount) })
        configure(httpCell, control: includeHTTP, key: "incident_include_http", selected: mark.http != nil,
            detail: mark.http.map { IncidentPresentation.window(time: $0.observedAtMillis, count: $0.events.count, omitted: $0.omittedEventCount, evicted: $0.sourceEvictedEvents, payloadEvicted: $0.sourceEvictedBodies) })
        configure(webSocketsCell, control: includeWebSockets, key: "incident_include_websockets", selected: mark.webSockets != nil,
            detail: mark.webSockets.map { IncidentPresentation.window(time: $0.observedAtMillis, count: $0.events.count, omitted: $0.omittedEventCount, evicted: $0.sourceEvictedEvents, payloadEvicted: $0.sourceEvictedPayloads) })
        configure(httpBodiesCell, control: includeHTTPBodies, key: "incident_include_http_bodies", selected: false,
            detail: RuntimePresentation.text(mark.http?.payloads.isEmpty == false && httpHistory != nil ? "incident_body_choice_hint" : "incident_body_none"))
        configure(webSocketPayloadsCell, control: includeWebSocketPayloads, key: "incident_include_ws_payloads", selected: false,
            detail: RuntimePresentation.text(mark.webSockets?.payloads.isEmpty == false && webSocketHistory != nil ? "incident_body_choice_hint" : "incident_body_none"))
        editorCell.selectionStyle = .none; editor.translatesAutoresizingMaskIntoConstraints = false; editorCell.contentView.addSubview(editor)
        NSLayoutConstraint.activate([
            editor.leadingAnchor.constraint(equalTo: editorCell.contentView.layoutMarginsGuide.leadingAnchor),
            editor.trailingAnchor.constraint(equalTo: editorCell.contentView.layoutMarginsGuide.trailingAnchor),
            editor.topAnchor.constraint(equalTo: editorCell.contentView.topAnchor, constant: 8),
            editor.bottomAnchor.constraint(equalTo: editorCell.contentView.bottomAnchor, constant: -8)
        ])
        environmentCell.detailTextLabel?.text = RuntimePresentation.text("incident_environment_loading")
        readEnvironment(); updateControls()
    }
    private var controls: [UISwitch] { [includeEnvironment, includeBreadcrumbs, includeLogs, includeHTTP, includeHTTPBodies, includeWebSockets, includeWebSocketPayloads, includeNote] }
    private var optionCells: [UITableViewCell] { [environmentCell, breadcrumbsCell, logsCell, httpCell, httpBodiesCell, webSocketsCell, webSocketPayloadsCell, noteToggleCell] }
    private func configure(_ cell: UITableViewCell, control: UISwitch, key: String, selected: Bool, detail: String?) {
        PersistencePresentation.style(cell); cell.selectionStyle = .none
        cell.textLabel?.text = RuntimePresentation.text(key); cell.detailTextLabel?.text = detail ?? RuntimePresentation.text("incident_window_unavailable")
        cell.accessoryView = control; control.accessibilityLabel = RuntimePresentation.text(key); control.isOn = selected
    }
    private func readEnvironment() {
        environmentGeneration += 1; let request = environmentGeneration
        provider.read { [weak self] result in
            Task { @MainActor [weak self] in
                guard let self, self.environmentGeneration == request, self.environmentPending else { return }
                self.environmentPending = false
                if case .success(let snapshot) = result { self.environment = snapshot }
                self.environmentReceived()
            }
        }
        // A custom host provider must not leave the manual workflow waiting indefinitely.
        DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in
            guard let self, self.environmentGeneration == request, self.environmentPending else { return }
            self.environmentPending = false; self.environmentGeneration += 1
            self.environmentReceived()
        }
    }
    private func environmentReceived() {
        environmentCell.detailTextLabel?.text = environment.map { RuntimePresentation.date($0.timestampMillis) } ?? RuntimePresentation.text("incident_environment_failed")
        updateControls(); tableView.beginUpdates(); tableView.endUpdates()
    }
    @objc private func optionsChanged(_ sender: UISwitch) {
        if !includeNote.isOn { editor.resignFirstResponder() }
        if sender === includeHTTPBodies, includeHTTPBodies.isOn, httpWithBodies == nil, let history = httpHistory {
            do {
                httpWithBodies = try mark.http?.includingBodies(from: history, payloadByteLimit: 12_288)
                httpBodiesCell.detailTextLabel?.text = httpWithBodies.map { contentStatus($0.payloads, bytes: $0.retainedPayloadBytes) }
            } catch {
                httpContentFailed = true; includeHTTPBodies.isOn = false
                httpBodiesCell.detailTextLabel?.text = RuntimePresentation.text("incident_body_failed")
            }
        }
        if sender === includeWebSocketPayloads, includeWebSocketPayloads.isOn, socketsWithPayloads == nil, let history = webSocketHistory {
            do {
                socketsWithPayloads = try mark.webSockets?.includingPayloads(from: history, payloadByteLimit: 12_288)
                webSocketPayloadsCell.detailTextLabel?.text = socketsWithPayloads.map { contentStatus($0.payloads, bytes: $0.retainedPayloadBytes) }
            } catch {
                socketContentFailed = true; includeWebSocketPayloads.isOn = false
                webSocketPayloadsCell.detailTextLabel?.text = RuntimePresentation.text("incident_body_failed")
            }
        }
        updateControls()
        if sender === includeNote {
            let row = IndexPath(row: 0, section: 2)
            if includeNote.isOn { tableView.insertRows(at: [row], with: .automatic) }
            else { tableView.deleteRows(at: [row], with: .automatic) }
        } else { tableView.beginUpdates(); tableView.endUpdates() }
    }
    private func contentStatus(_ payloads: [String: IncidentPayload], bytes: Int) -> String {
        String(format: RuntimePresentation.text("incident_body_selected"), payloads.values.filter { $0.state == .available }.count, payloads.count, bytes)
            + "\n" + RuntimePresentation.text("incident_body_choice_hint")
    }
    private func updateControls() {
        inspectorPrimaryAction?.isEnabled = !saving && (!includeEnvironment.isOn || !environmentPending)
        navigationItem.leftBarButtonItem?.isEnabled = !saving
        for control in controls { control.isEnabled = !saving }
        includeLogs.isEnabled = !saving && mark.logs != nil
        includeHTTP.isEnabled = !saving && mark.http != nil
        includeWebSockets.isEnabled = !saving && mark.webSockets != nil
        includeHTTPBodies.isEnabled = !saving && includeHTTP.isOn && httpHistory != nil && mark.http?.payloads.isEmpty == false && !httpContentFailed
        includeWebSocketPayloads.isEnabled = !saving && includeWebSockets.isOn && webSocketHistory != nil && mark.webSockets?.payloads.isEmpty == false && !socketContentFailed
        editor.isEditable = !saving; isModalInPresentation = saving
    }
    @objc private func cancel() { environmentGeneration += 1; dismiss(animated: true) }
    @objc private func save() {
        guard !saving, !includeEnvironment.isOn || !environmentPending else { return }
        let snapshot: IncidentSnapshot
        do {
            let note = try includeNote.isOn && !editor.text.isEmpty ? IncidentNote.capture(.publicText(editor.text)) : nil
            snapshot = try IncidentSnapshot.restore(id: mark.id, timestampMillis: mark.timestampMillis, note: note,
                environment: includeEnvironment.isOn ? environment : nil, breadcrumbs: includeBreadcrumbs.isOn ? mark.breadcrumbs : nil,
                logs: includeLogs.isOn ? mark.logs : nil,
                http: includeHTTP.isOn ? (includeHTTPBodies.isOn ? httpWithBodies : mark.http) : nil,
                webSockets: includeWebSockets.isOn ? (includeWebSocketPayloads.isOn ? socketsWithPayloads : mark.webSockets) : nil)
        } catch { notice = RuntimePresentation.text("incident_mark_failed"); tableView.reloadData(); return }
        saving = true; notice = RuntimePresentation.text("incident_saving"); view.endEditing(true); updateControls(); tableView.reloadData()
        archive.save(snapshot) { [weak self] result in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.saving = false; self.updateControls()
                switch result {
                case .success:
                    self.editor.text = ""; self.saved(); self.dismiss(animated: true)
                case .failure(let error):
                    self.notice = PersistencePresentation.error(error); self.tableView.reloadData()
                }
            }
        }
    }
    func textView(_ textView: UITextView, shouldChangeTextIn range: NSRange, replacementText text: String) -> Bool {
        guard Array(text.utf8.prefix(4_097)).count <= 4_096 else { limitNotice(); return false }
        let existing = textView.text as NSString
        guard range.location <= existing.length, range.length <= existing.length - range.location else { return false }
        let proposed = existing.replacingCharacters(in: range, with: text)
        guard Array(proposed.utf8.prefix(4_097)).count <= 4_096 else { limitNotice(); return false }
        return true
    }
    private func limitNotice() {
        UIAccessibility.post(notification: .announcement, argument: RuntimePresentation.text("incident_note_limit"))
        notice = RuntimePresentation.text("incident_note_limit")
        tableView.beginUpdates(); tableView.endUpdates()
        // Reload only the status section so a rejected paste does not disturb marked text or keyboard focus.
        tableView.reloadSections(IndexSet(integer: 0), with: .none)
    }
    func textViewDidChange(_ textView: UITextView) { tableView.beginUpdates(); tableView.endUpdates() }
    override func numberOfSections(in tableView: UITableView) -> Int { 3 }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        if section == 0 { return 1 }
        return section == 1 ? optionCells.count : includeNote.isOn ? 1 : 0
    }
    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        if section == 0 { return RuntimePresentation.text("incident_mark_hint") + (notice.isEmpty ? "" : "\n\n" + notice) }
        if section == 2 { return RuntimePresentation.text("incident_note_hint") + "\n" + RuntimePresentation.text("incident_note_limit") }
        return nil
    }
    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        section == 2 && includeNote.isOn ? RuntimePresentation.text("incident_note") : nil
    }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        if indexPath.section == 2 {
            return editorCell
        }
        if indexPath.section == 1 { return optionCells[indexPath.row] }
        let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil); PersistencePresentation.style(cell); cell.selectionStyle = .none
        cell.textLabel?.text = RuntimePresentation.text("incident_frozen_time")
        cell.detailTextLabel?.text = RuntimePresentation.date(mark.timestampMillis)
        return cell
    }
}
#endif

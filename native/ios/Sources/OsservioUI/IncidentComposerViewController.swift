#if canImport(UIKit)
import OsservioCore
import UIKit

/// Metadata is frozen before editing. Opted-in bodies are copied once from those references.
@MainActor
final class IncidentComposerViewController: UITableViewController, UITextViewDelegate, UIAdaptivePresentationControllerDelegate {
    private let archive: any IncidentArchive
    private let provider: any EnvironmentProvider
    private let mark: IncidentSnapshot
    private let httpHistory: (any HTTPHistory)?
    private let webSocketHistory: (any WebSocketHistory)?
    private let logHistory: (any LogDetailsHistory)?
    private let saved: @MainActor () -> Void
    private let includeEnvironment = UISwitch()
    private let includeBreadcrumbs = UISwitch()
    private let includeNote = UISwitch()
    private let includeLogs = UISwitch()
    private let includeLogDetails = UISwitch()
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
    private let logDetailsCell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
    private let httpCell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
    private let httpBodiesCell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
    private let webSocketsCell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
    private let webSocketPayloadsCell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
    private var httpWithBodies: IncidentHTTP?
    private var logsWithDetails: IncidentLogs?
    private var logContentFailed = false
    private var socketsWithPayloads: IncidentWebSockets?
    private var httpContentFailed = false
    private var socketContentFailed = false
    private var environment: EnvironmentSnapshot?
    private var environmentPending = true
    private var environmentGeneration = 0
    private var saving = false
    private var notice = ""
    private var initialOptions: [Bool] = []

    init(mark: IncidentSnapshot, archive: any IncidentArchive, provider: any EnvironmentProvider,
         httpHistory: (any HTTPHistory)? = nil, webSocketHistory: (any WebSocketHistory)? = nil, logHistory: (any LogDetailsHistory)? = nil, saved: @escaping @MainActor () -> Void) {
        self.mark = mark; self.archive = archive; self.provider = provider; self.saved = saved
        self.httpHistory = httpHistory; self.webSocketHistory = webSocketHistory
        self.logHistory = logHistory
        super.init(style: .insetGrouped)
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func viewDidLoad() {
        super.viewDidLoad(); InspectorTheme.apply(self); title = RuntimePresentation.text("incident_mark")
        tableView.rowHeight = UITableView.automaticDimension; tableView.estimatedRowHeight = 80
        tableView.keyboardDismissMode = .interactive
        navigationItem.leftBarButtonItem = UIBarButtonItem(title: RuntimePresentation.text("archive_cancel"), style: .plain, target: self, action: #selector(cancel))
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
        breadcrumbsCell.detailTextLabel?.text = mark.breadcrumbs == nil ? RuntimePresentation.text("incident_breadcrumbs_failed") : IncidentPresentation.breadcrumbSummary(mark.breadcrumbs)
        noteToggleCell.textLabel?.text = RuntimePresentation.text("incident_include_note"); noteToggleCell.accessoryView = includeNote
        configure(logsCell, control: includeLogs, key: "incident_include_logs", selected: mark.logs != nil,
            detail: mark.logs.map { IncidentPresentation.selectionWindow(time: $0.observedAtMillis, count: $0.events.count, omitted: $0.omittedEventCount, evicted: $0.sourceEvictedCount) })
        configure(logDetailsCell, control: includeLogDetails, key: "incident_include_log_details", selected: false,
            detail: RuntimePresentation.text(logHistory != nil ? "incident_body_choice_hint" : "incident_body_none"))
        configure(httpCell, control: includeHTTP, key: "incident_include_http", selected: mark.http != nil,
            detail: mark.http.map { IncidentPresentation.selectionWindow(time: $0.observedAtMillis, count: $0.events.count, omitted: $0.omittedEventCount, evicted: $0.sourceEvictedEvents, payloadEvicted: $0.sourceEvictedBodies) })
        configure(webSocketsCell, control: includeWebSockets, key: "incident_include_websockets", selected: mark.webSockets != nil,
            detail: mark.webSockets.map { IncidentPresentation.selectionWindow(time: $0.observedAtMillis, count: $0.events.count, omitted: $0.omittedEventCount, evicted: $0.sourceEvictedEvents, payloadEvicted: $0.sourceEvictedPayloads) })
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
        initialOptions = controls.map(\.isOn)
        readEnvironment(); updateControls()
    }
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        navigationController?.presentationController?.delegate = self
    }
    private var hasChanges: Bool { controls.map(\.isOn) != initialOptions || !editor.text.isEmpty }
    func presentationControllerShouldDismiss(_ presentationController: UIPresentationController) -> Bool { !saving && !hasChanges }
    func presentationControllerDidAttemptToDismiss(_ presentationController: UIPresentationController) { cancel() }
    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) { environmentGeneration += 1 }
    override func accessibilityPerformEscape() -> Bool { cancel(); return true }
    private var hasLogDetails: Bool { mark.logs?.payloads.isEmpty == false }
    private var controls: [UISwitch] { [includeEnvironment, includeBreadcrumbs, includeLogs, includeLogDetails, includeHTTP, includeHTTPBodies, includeWebSockets, includeWebSocketPayloads, includeNote] }

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
        if sender === includeLogDetails, includeLogDetails.isOn, logsWithDetails == nil, let history = logHistory {
            do {
                logsWithDetails = try mark.logs?.includingDetails(from: history, payloadByteLimit: 12_288)
                logDetailsCell.detailTextLabel?.text = logsWithDetails.map { contentStatus($0.payloads, bytes: $0.retainedPayloadBytes) }
            } catch {
                logContentFailed = true; includeLogDetails.isOn = false
                logDetailsCell.detailTextLabel?.text = RuntimePresentation.text("incident_body_failed")
            }
        }
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
            let row = IndexPath(row: 1, section: 4)
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
        includeLogDetails.isEnabled = !saving && includeLogs.isOn && logHistory != nil && hasLogDetails && !logContentFailed
        includeHTTP.isEnabled = !saving && mark.http != nil
        includeWebSockets.isEnabled = !saving && mark.webSockets != nil
        includeHTTPBodies.isEnabled = !saving && includeHTTP.isOn && httpHistory != nil && mark.http?.payloads.isEmpty == false && !httpContentFailed
        includeWebSocketPayloads.isEnabled = !saving && includeWebSockets.isOn && webSocketHistory != nil && mark.webSockets?.payloads.isEmpty == false && !socketContentFailed
        editor.isEditable = !saving; isModalInPresentation = saving
        navigationController?.isModalInPresentation = saving
    }
    @objc private func cancel() {
        guard !saving, presentedViewController == nil else { return }
        guard hasChanges else { abandon(); return }
        view.endEditing(true)
        let alert = UIAlertController(title: RuntimePresentation.text("incident_discard_title"),
            message: RuntimePresentation.text("incident_discard_hint"), preferredStyle: .alert)
        alert.overrideUserInterfaceStyle = overrideUserInterfaceStyle
        alert.view.tintColor = InspectorTheme.accent
        alert.addAction(UIAlertAction(title: RuntimePresentation.text("incident_keep_editing"), style: .cancel))
        alert.addAction(UIAlertAction(title: RuntimePresentation.text("incident_discard"), style: .destructive) { [weak self] _ in self?.abandon() })
        present(alert, animated: true)
    }
    private func abandon() {
        environmentGeneration += 1
        if let presenter = navigationController?.presentingViewController ?? presentingViewController { presenter.dismiss(animated: true) }
        else { dismiss(animated: true) }
    }
    @objc private func save() {
        guard !saving, !includeEnvironment.isOn || !environmentPending else { return }
        let snapshot: IncidentSnapshot
        do {
            let note = try includeNote.isOn && !editor.text.isEmpty ? IncidentNote.capture(.publicText(editor.text)) : nil
            snapshot = try IncidentSnapshot.restore(id: mark.id, timestampMillis: mark.timestampMillis, note: note,
                environment: includeEnvironment.isOn ? environment : nil, breadcrumbs: includeBreadcrumbs.isOn ? mark.breadcrumbs : nil,
                logs: includeLogs.isOn ? (includeLogDetails.isOn ? logsWithDetails : mark.logs) : nil,
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
    override func numberOfSections(in tableView: UITableView) -> Int { 5 }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        switch section { case 0: return 1; case 1: return hasLogDetails ? 4 : 3; case 2, 3: return 2; default: return includeNote.isOn ? 2 : 1 }
    }
    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        if section == 0 { return RuntimePresentation.text("incident_mark_hint") + (notice.isEmpty ? "" : "\n\n" + notice) }
        if section == 4 { return RuntimePresentation.text("incident_note_hint") + "\n" + RuntimePresentation.text("incident_note_limit") }
        return nil
    }
    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        switch section { case 1: return RuntimePresentation.text("ui_evidence_available"); case 2: return "HTTP"; case 3: return "WebSocket"; case 4: return RuntimePresentation.text("incident_note"); default: return nil }
    }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        switch indexPath.section {
        case 1: return [environmentCell, breadcrumbsCell, logsCell, logDetailsCell][indexPath.row]
        case 2: return [httpCell, httpBodiesCell][indexPath.row]
        case 3: return [webSocketsCell, webSocketPayloadsCell][indexPath.row]
        case 4: return indexPath.row == 0 ? noteToggleCell : editorCell
        default:
            let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil); PersistencePresentation.style(cell); cell.selectionStyle = .none
            cell.textLabel?.text = RuntimePresentation.text("incident_frozen_time")
            cell.detailTextLabel?.text = InspectorValuePresentation.eventTime(mark.timestampMillis, full: true)
            return cell
        }
    }
}
#endif

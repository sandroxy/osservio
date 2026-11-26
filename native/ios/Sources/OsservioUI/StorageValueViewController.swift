#if canImport(UIKit)
import OsservioCore
import UIKit

@MainActor
final class StorageValueViewController: UIViewController {
    private let storage: KeyValueInspection
    private let domain: KeyValueInspection.Domain
    private let key: String
    private var request: KeyValueInspection.Request?
    private var observation: KeyValueInspection.Request?
    private var generation = 0
    private var reader: InspectorContentViewController?
    private lazy var refreshButton = UIBarButtonItem(barButtonSystemItem: .refresh, target: self, action: #selector(readValue))
    init(storage: KeyValueInspection, domain: KeyValueInspection.Domain, key: String) {
        self.storage = storage; self.domain = domain; self.key = key; super.init(nibName: nil, bundle: nil)
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    deinit { request?.cancel(); observation?.cancel() }
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated); InspectorTheme.apply(self); title = InspectorLocalization.text("storage_value")
        observation = storage.observeClose { [weak self] in Task { @MainActor [weak self] in self?.readValue() } }
        if reader == nil { readValue() }
    }
    override func viewWillDisappear(_ animated: Bool) { super.viewWillDisappear(animated); generation += 1; request?.cancel(); request = nil; observation?.cancel(); observation = nil }
    override func viewDidDisappear(_ animated: Bool) { super.viewDidDisappear(animated); removeReader() }
    private func removeReader() {
        reader?.inspectorActions?.forEach { $0.isEnabled = false }
        inspectorActions = nil; reader?.willMove(toParent: nil); reader?.view.removeFromSuperview(); reader?.removeFromParent(); reader = nil
    }
    private func display(_ value: InspectorReadContent) {
        removeReader()
        let reader = InspectorContentViewController(titleKey: "storage_value", value: value); self.reader = reader
        addChild(reader); reader.view.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(reader.view)
        NSLayoutConstraint.activate([reader.view.topAnchor.constraint(equalTo: view.topAnchor), reader.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            reader.view.leadingAnchor.constraint(equalTo: view.leadingAnchor), reader.view.trailingAnchor.constraint(equalTo: view.trailingAnchor)])
        reader.didMove(toParent: self)
        inspectorActions = (reader.inspectorActions ?? []) + [refreshButton]; refreshButton.tintColor = InspectorTheme.accent
        refreshButton.accessibilityLabel = InspectorLocalization.text("http_refresh")
    }
    @objc private func readValue() {
        request?.cancel(); generation += 1; let ticket = generation
        guard !storage.isClosed else { display(InspectorReadContent(notice: { InspectorLocalization.text("storage_closed") })); refreshButton.isEnabled = false; return }
        display(InspectorReadContent(notice: { InspectorLocalization.text("runtime_reading") })); refreshButton.isEnabled = false
        request = storage.read(domain: domain.id, key: key) { [weak self] result in
            Task { @MainActor [weak self] in
                guard let self, self.generation == ticket else { return }; self.request = nil
                self.refreshButton.isEnabled = !self.storage.isClosed
                guard !self.storage.isClosed else { self.display(InspectorReadContent(notice: { InspectorLocalization.text("storage_closed") })); return }
                switch result {
                case .success(let read): self.received(read)
                case .failure(let error): self.display(InspectorReadContent(notice: { InspectorLocalization.text(error == .busy ? "busy" : "runtime_read_failed") }))
                }
            }
        }
    }
    private func received(_ read: KeyValueInspection.Read) {
        let value = read.value, domain = domain, key = key
        display(InspectorReadContent(text: value.state == .available ? value.text : nil, notice: {
            var lines = [InspectorLocalization.text("storage_read_at") + " · " + InspectorLocalization.date(read.readAtMillis)]
            if let kind = value.kind { lines.append(InspectorLocalization.text("storage_kind_" + kind.rawValue.lowercased())) }
            switch value.state {
            case .available: if value.text.isEmpty { lines.append(InspectorLocalization.text("storage_empty_value")) }
            case .missing: lines.append(InspectorLocalization.text("storage_missing"))
            case .unsupported: lines.append(InspectorLocalization.text("storage_unsupported"))
            case .tooLarge: lines.append(InspectorLocalization.text("storage_too_large"))
            }
            if value.truncated { lines.append(InspectorLocalization.text("storage_truncated")) }
            lines.append(InspectorLocalization.text("storage_read_hint")); return lines.joined(separator: "\n")
        }, allowsJSON: !value.truncated, context: { domain.name + " · " + domain.backend + " · " + domain.scope + "\n" + key }))
    }
}
#endif

#if canImport(UIKit)
import OsservioCore
import UIKit

/// Explicit, one-shot reads. Hiding invalidates delivery without altering provider or capture lifetime.
@MainActor
final class EnvironmentInspectorViewController: InspectorDetailViewController {
    private let provider: any EnvironmentProvider
    private var snapshot: EnvironmentSnapshot?
    private var reading = false
    private var generation = 0
    private var noticeKey = "runtime_environment_hint"
    private lazy var refreshButton = UIBarButtonItem(barButtonSystemItem: .refresh, target: self, action: #selector(refresh))
    init(provider: any EnvironmentProvider) { self.provider = provider; super.init() }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func viewDidLoad() { super.viewDidLoad(); render() }
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated); render(); if snapshot == nil && !reading { read() }
    }
    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated); generation += 1; reading = false
    }
    private func read() {
        guard !reading else { return }; reading = true; generation += 1; let request = generation
        noticeKey = "runtime_reading"; render()
        provider.read { [weak self] result in
            Task { @MainActor [weak self] in
                guard let self, self.generation == request else { return }
                self.reading = false
                switch result {
                case .success(let value): self.snapshot = value; self.noticeKey = "runtime_environment_hint"
                case .failure(let error): self.noticeKey = error == .busy ? "busy" : "runtime_read_failed"
                }
                self.render()
            }
        }
    }
    private func render() {
        title = InspectorLocalization.text("runtime_environment")
        inspectorPrimaryAction = refreshButton; refreshButton.tintColor = InspectorTheme.accent
        inspectorPrimaryAction?.accessibilityLabel = InspectorLocalization.text("http_refresh")
        inspectorPrimaryAction?.isEnabled = !reading
        sections = [Section(title: InspectorLocalization.text("ui_snapshot_time"), rows: [
            Row(snapshot.map { InspectorLocalization.date($0.timestampMillis) } ?? InspectorLocalization.text("runtime_unavailable"))
        ], footer: InspectorLocalization.text(noticeKey))]
        if let snapshot {
            sections += EnvironmentPresentation.sections(snapshot)
            sections.append(Section(title: "", rows: [
                Row(InspectorLocalization.text("runtime_copy_environment"), action: { UIPasteboard.general.string = RuntimePresentation.environment(snapshot) })
            ]))
        }
    }
    @objc private func refresh() { read() }
}
#endif

#if canImport(UIKit)
import OsservioCore
import UIKit

/// Runtime context uses only Core query contracts. Hosts own collectors and their lifetimes.
@MainActor
public final class RuntimeInspectorViewController: UIViewController {
    private let environment: EnvironmentInspectorViewController
    private let breadcrumbs: BreadcrumbInspectorViewController
    private let capture: CaptureController
    private let archive: BreadcrumbArchiveViewController?
    private let content = UIView()
    private let selection = UISegmentedControl(items: [RuntimePresentation.text("runtime_environment"), RuntimePresentation.text("runtime_breadcrumbs")])
    private var current: UIViewController?
    public init(provider: any EnvironmentProvider, recorder: BreadcrumbRecorder, history: any BreadcrumbHistory,
                archive: (any BreadcrumbArchive)? = nil) {
        capture = recorder.captureController
        environment = EnvironmentInspectorViewController(provider: provider)
        breadcrumbs = BreadcrumbInspectorViewController(recorder: recorder, history: history)
        self.archive = archive.map { BreadcrumbArchiveViewController(archive: $0) }
        super.init(nibName: nil, bundle: nil)
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    public override func viewDidLoad() {
        super.viewDidLoad(); InspectorTheme.apply(self)
        title = RuntimePresentation.text("runtime_title")
        let settings = UIBarButtonItem(image: UIImage(systemName: "slider.horizontal.3"), style: .plain, target: self, action: #selector(showSettings))
        settings.accessibilityLabel = InspectorLocalization.text("ui_capture_settings"); inspectorPrimaryAction = settings
        view.backgroundColor = .systemBackground
        if archive != nil { selection.insertSegment(withTitle: RuntimePresentation.text("archive_title"), at: 2, animated: false) }
        selection.selectedSegmentIndex = 0
        selection.addTarget(self, action: #selector(selectSection), for: .valueChanged)
        let stack = UIStackView(arrangedSubviews: [selection, content])
        stack.axis = .vertical; stack.spacing = 8; stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            stack.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 8),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -8)
        ])
        selectSection()
    }
    @objc private func showSettings() { inspectorPush(InspectorCaptureSettingsViewController(baseline: capture, controllers: [("runtime_breadcrumbs", capture)])) }
    @objc private func selectSection() {
        current?.willMove(toParent: nil)
        current?.view.removeFromSuperview(); current?.removeFromParent()
        let next: UIViewController
        if selection.selectedSegmentIndex == 0 { next = environment }
        else if selection.selectedSegmentIndex == 2, let archive { next = archive }
        else { next = breadcrumbs }
        addChild(next); next.view.translatesAutoresizingMaskIntoConstraints = false; content.addSubview(next.view)
        NSLayoutConstraint.activate([
            next.view.topAnchor.constraint(equalTo: content.topAnchor), next.view.bottomAnchor.constraint(equalTo: content.bottomAnchor),
            next.view.leadingAnchor.constraint(equalTo: content.leadingAnchor), next.view.trailingAnchor.constraint(equalTo: content.trailingAnchor)
        ])
        next.didMove(toParent: self); current = next
    }
}
#endif

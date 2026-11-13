#if canImport(UIKit)
import OsservioCore
import UIKit

/// Four native destinations share host-owned histories. Opening or closing never changes capture.
@MainActor
public final class InspectorViewController: UITabBarController, UITabBarControllerDelegate {
    private let debugControl: InspectorDebugControl
    private lazy var debugLoop = InspectorRefreshLoop { [weak self] in self?.debugControl.refresh() }
    private var sections: [(UIViewController, String, String)] = []
    weak var session: InspectorSession? {
        didSet {
            for navigation in viewControllers?.compactMap({ $0 as? InspectorNavigationController }) ?? [] { navigation.session = session }
            if isViewLoaded { relabel() }
        }
    }
    private lazy var style = InspectorStyleObserver { [weak self] in self?.relabel() }

    public convenience init(logRecorder: LogRecorder, logHistory: any LogHistory,
                            httpRecorder: HTTPRecorder, httpHistory: any HTTPHistory) {
        self.init(logRecorder: logRecorder, logHistory: logHistory, http: (httpRecorder, httpHistory), sockets: nil,
            environment: nil, breadcrumbs: nil, breadcrumbArchive: nil, incidentArchive: nil, incidentExporter: nil)
    }
    public convenience init(logRecorder: LogRecorder, logHistory: any LogHistory,
                            httpRecorder: HTTPRecorder, httpHistory: any HTTPHistory,
                            webSocketRecorder: WebSocketRecorder, webSocketHistory: any WebSocketHistory) {
        self.init(logRecorder: logRecorder, logHistory: logHistory, http: (httpRecorder, httpHistory), sockets: (webSocketRecorder, webSocketHistory),
            environment: nil, breadcrumbs: nil, breadcrumbArchive: nil, incidentArchive: nil, incidentExporter: nil)
    }
    public convenience init(logRecorder: LogRecorder, logHistory: any LogHistory,
                            httpRecorder: HTTPRecorder, httpHistory: any HTTPHistory,
                            webSocketRecorder: WebSocketRecorder, webSocketHistory: any WebSocketHistory,
                            environment: any EnvironmentProvider, breadcrumbRecorder: BreadcrumbRecorder, breadcrumbHistory: any BreadcrumbHistory,
                            breadcrumbArchive: (any BreadcrumbArchive)? = nil, incidentArchive: (any IncidentArchive)? = nil,
                            incidentExporter: (any IncidentExporter)? = nil) {
        self.init(logRecorder: logRecorder, logHistory: logHistory, http: (httpRecorder, httpHistory), sockets: (webSocketRecorder, webSocketHistory),
            environment: environment, breadcrumbs: (breadcrumbRecorder, breadcrumbHistory), breadcrumbArchive: breadcrumbArchive,
            incidentArchive: incidentArchive, incidentExporter: incidentExporter)
    }
    private init(logRecorder: LogRecorder, logHistory: any LogHistory, http: NetworkInspectorContent.HTTP, sockets: NetworkInspectorContent.Socket?,
                 environment: (any EnvironmentProvider)?, breadcrumbs: (recorder: BreadcrumbRecorder, history: any BreadcrumbHistory)?,
                 breadcrumbArchive: (any BreadcrumbArchive)?, incidentArchive: (any IncidentArchive)?, incidentExporter: (any IncidentExporter)?) {
        debugControl = InspectorDebugControl(controllers: [logRecorder.captureController, http.recorder.captureController]
            + (sockets.map { [$0.recorder.captureController] } ?? []))
        super.init(nibName: nil, bundle: nil)
        let logs = LogInspectorViewController(recorder: logRecorder, history: logHistory)
        let network = NetworkInspectorViewController(content: NetworkInspectorContent(http: http, sockets: sockets))
        let problems: UIViewController
        if let incidentArchive, let environment, let breadcrumbs {
            problems = IncidentInspectorViewController(archive: incidentArchive, environment: environment, breadcrumbs: breadcrumbs.history,
                logs: logHistory, http: http.history, webSockets: sockets?.history, exporter: incidentExporter)
        } else { problems = InspectorUnavailableViewController() }
        var controllers = [("logs", logRecorder.captureController)]
        for (name, controller) in [("HTTP", Optional(http.recorder.captureController)), ("WebSocket", sockets?.recorder.captureController),
                                   ("runtime_breadcrumbs", breadcrumbs?.recorder.captureController)] {
            if let controller, !controllers.contains(where: { $0.1 === controller }) { controllers.append((name, controller)) }
        }
        debugControl.presenter = self
        let app = AppInspectorViewController(environment: environment, breadcrumbs: breadcrumbs, archive: breadcrumbArchive, controllers: controllers)
        sections = [(logs, "logs", "doc.text"), (network, "network", "network"),
                    (problems, "incident_title", "flag"), (app, "ui_app", "square.grid.2x2")]
        viewControllers = sections.enumerated().map { index, section in
            let navigation = InspectorNavigationController(rootViewController: section.0)
            navigation.tabBarItem = UITabBarItem(title: InspectorLocalization.text(section.1), image: UIImage(systemName: section.2), tag: index)
            section.0.navigationItem.leftBarButtonItem = debugControl.item()
            section.0.inspectorMinimalBackTitle()
            navigation.visiblePageChanged = { [weak self] in self?.updateDebugPolling() }
            return navigation
        }
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    public override func viewDidLoad() { super.viewDidLoad(); delegate = self; style.changed() }
    public override func viewDidAppear(_ animated: Bool) { super.viewDidAppear(animated); updateDebugPolling() }
    public override func viewWillDisappear(_ animated: Bool) { super.viewWillDisappear(animated); debugLoop.hide() }
    public func tabBarController(_ tabBarController: UITabBarController, didSelect viewController: UIViewController) { InspectorPerformance.mark(.tab); updateDebugPolling() }
    private func updateDebugPolling() {
        guard isViewLoaded else { return }
        if let navigation = selectedViewController as? UINavigationController, navigation.viewControllers.count == 1 {
            debugControl.refresh(); debugLoop.show(in: view)
        } else { debugLoop.hide() }
    }
    private func relabel() {
        InspectorTheme.apply(self)
        let appearance = UITabBarAppearance(); appearance.configureWithOpaqueBackground(); appearance.backgroundColor = InspectorTheme.surface
        tabBar.standardAppearance = appearance
        if #available(iOS 15, *) { tabBar.scrollEdgeAppearance = appearance }
        tabBar.tintColor = InspectorTheme.accent
        debugControl.refresh()
        for (index, section) in sections.enumerated() {
            viewControllers?[index].tabBarItem.title = InspectorLocalization.text(section.1)
            let actions = InspectorNavigationActions.state(for: section.0)
            if session == nil {
                let close = inspectorDismissItem(target: self, action: #selector(closeInspector), label: "ui_close")
                close.accessibilityIdentifier = "Osservio.CloseInspector"; actions.close = close
            } else {
                actions.close = nil
            }
            actions.render()
            section.0.inspectorMinimalBackTitle()
        }
    }
    @objc private func closeInspector() {
        if let session { session.collapse() } else { dismiss(animated: true) }
    }
}
#endif

#if canImport(UIKit)
import OsservioCore
import UIKit

/// Native tabs share the host's histories and capture controller. Presentation does not start capture.
@MainActor
public final class InspectorViewController: UITabBarController {
    public init(logRecorder: LogRecorder, logHistory: any LogHistory,
                httpRecorder: HTTPRecorder, httpHistory: any HTTPHistory) {
        super.init(nibName: nil, bundle: nil)
        let logs = LogInspectorViewController(recorder: logRecorder, history: logHistory)
        let network = HTTPInspectorViewController(recorder: httpRecorder, history: httpHistory)
        viewControllers = [(logs, "logs", "doc.text"), (network, "network", "network")].map { controller, key, icon in
            section(controller, key: key, icon: icon)
        }
    }
    public convenience init(logRecorder: LogRecorder, logHistory: any LogHistory,
                            httpRecorder: HTTPRecorder, httpHistory: any HTTPHistory,
                            webSocketRecorder: WebSocketRecorder, webSocketHistory: any WebSocketHistory) {
        self.init(logRecorder: logRecorder, logHistory: logHistory, httpRecorder: httpRecorder, httpHistory: httpHistory)
        let sockets = WebSocketInspectorViewController(recorder: webSocketRecorder, history: webSocketHistory)
        viewControllers?.append(section(sockets, key: "ws_title", icon: "arrow.left.arrow.right"))
    }
    private func section(_ controller: UIViewController, key: String, icon: String) -> UINavigationController {
        let navigation = UINavigationController(rootViewController: controller)
        navigation.tabBarItem = UITabBarItem(title: HTTPPresentation.text(key), image: UIImage(systemName: icon), tag: 0)
        controller.navigationItem.leftBarButtonItem = UIBarButtonItem(barButtonSystemItem: .done, target: self,
                                                                      action: #selector(closeInspector))
        return navigation
    }
    public convenience init(logRecorder: LogRecorder, logHistory: any LogHistory,
                            httpRecorder: HTTPRecorder, httpHistory: any HTTPHistory,
                            webSocketRecorder: WebSocketRecorder, webSocketHistory: any WebSocketHistory,
                            environment: any EnvironmentProvider, breadcrumbRecorder: BreadcrumbRecorder, breadcrumbHistory: any BreadcrumbHistory,
                            breadcrumbArchive: (any BreadcrumbArchive)? = nil, incidentArchive: (any IncidentArchive)? = nil,
                            incidentExporter: (any IncidentExporter)? = nil) {
        self.init(logRecorder: logRecorder, logHistory: logHistory, httpRecorder: httpRecorder, httpHistory: httpHistory,
                  webSocketRecorder: webSocketRecorder, webSocketHistory: webSocketHistory)
        let runtime = RuntimeInspectorViewController(provider: environment, recorder: breadcrumbRecorder, history: breadcrumbHistory, archive: breadcrumbArchive)
        viewControllers?.append(section(runtime, key: "runtime_title", icon: "info.circle"))
        if let incidentArchive {
            let incidents = IncidentInspectorViewController(archive: incidentArchive, environment: environment, breadcrumbs: breadcrumbHistory,
                logs: logHistory, http: httpHistory, webSockets: webSocketHistory, exporter: incidentExporter)
            viewControllers?.append(section(incidents, key: "incident_title", icon: "flag"))
        }
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    @objc private func closeInspector() { dismiss(animated: true) }
}
#endif

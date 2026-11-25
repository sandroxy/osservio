#if canImport(UIKit)
import OsservioCore
import UIKit

/// The Inspector owns recording; detail pages never discover host services globally.
@MainActor
final class InspectorIncidentRecording {
    let archive: any IncidentArchive
    let environment: any EnvironmentProvider
    let breadcrumbs: any BreadcrumbHistory
    let logs: (any LogDetailsHistory)?
    let http: any HTTPHistory
    let sockets: (any WebSocketHistory)?
    init(archive: any IncidentArchive, environment: any EnvironmentProvider, breadcrumbs: any BreadcrumbHistory,
         logs: (any LogDetailsHistory)?, http: any HTTPHistory, sockets: (any WebSocketHistory)?) {
        self.archive = archive; self.environment = environment; self.breadcrumbs = breadcrumbs
        self.logs = logs; self.http = http; self.sockets = sockets
    }
    func record(_ selection: InspectorIncidentSelection, from owner: UIViewController) {
        guard owner.presentedViewController == nil, owner.viewIfLoaded?.window != nil else { return }
        do {
            let mark = try selection.mark(breadcrumbs: breadcrumbs.snapshot())
            let composer = IncidentComposerViewController(mark: mark, archive: archive, provider: environment,
                httpHistory: http, webSocketHistory: sockets, logHistory: logs) { [weak owner] in
                guard let owner, owner.viewIfLoaded?.window?.isHidden == false, owner.presentedViewController == nil else { return }
                owner.inspectorNotice(InspectorLocalization.text("incident_saved"))
            }
            owner.inspectorPresent(composer)
        } catch InspectorIncidentSelection.PreparationError.tooLarge { owner.inspectorNotice(InspectorLocalization.text("incident_selection_failed")) }
        catch { owner.inspectorNotice(InspectorLocalization.text("incident_mark_failed")) }
    }
}

extension InspectorIncidentSelection {
    var summary: String {
        let row: InspectorRecordPresentation.Row
        switch self {
        case .log(let event): row = InspectorRecordPresentation.log(event)
        case .http(let event): row = InspectorRecordPresentation.http(event)
        case .socket(let event): row = InspectorRecordPresentation.socket(event)
        }
        return row.badge + " · " + InspectorValuePresentation.eventTime(row.time, full: true) + "\n" + row.title
    }
    @MainActor
    func openSaved(_ snapshot: IncidentSnapshot, from owner: UIViewController) {
        switch self {
        case .log(let event): owner.inspectorPush(LogDetailViewController(event: event, saved: snapshot.logs?.payloads ?? [:],
            neighbors: snapshot.logs.flatMap { InspectorLogContext(source: $0.events, target: event) }))
        case .http(let event): owner.inspectorPush(HTTPDetailViewController(event: event, savedPayloads: snapshot.http?.payloads ?? [:]))
        case .socket(let event): owner.inspectorPush(WebSocketDetailViewController(event: event, savedPayloads: snapshot.webSockets?.payloads ?? [:]))
        }
    }
}

extension UIViewController {
    var inspectorIncidentRecording: InspectorIncidentRecording? { (navigationController as? InspectorNavigationController)?.incidentRecording }
    func inspectorRecordItem(action: Selector) -> UIBarButtonItem {
        let item = UIBarButtonItem(image: UIImage(systemName: "flag"), style: .plain, target: self, action: action)
        item.accessibilityLabel = InspectorLocalization.text("incident_from_record")
        return item
    }
}
#endif

#if canImport(UIKit)
import OsservioCore
import UIKit

/// Search and details operate on one frozen group; this view has no live-source or capture dependency.
@MainActor
final class IncidentEvidenceViewController: UITableViewController, UISearchResultsUpdating {
    enum Evidence { case logs(IncidentLogs), http(IncidentHTTP), webSockets(IncidentWebSockets) }
    private let evidence: Evidence
    private var indices: [Int] = []
    private let search = UISearchController(searchResultsController: nil)
    init(_ evidence: Evidence) { self.evidence = evidence; super.init(style: .insetGrouped) }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func viewDidLoad() {
        super.viewDidLoad()
        switch evidence {
        case .logs: title = RuntimePresentation.text("logs")
        case .http: title = "HTTP"
        case .webSockets: title = RuntimePresentation.text("ws_title")
        }
        tableView.rowHeight = UITableView.automaticDimension; tableView.estimatedRowHeight = 80
        search.searchResultsUpdater = self; search.obscuresBackgroundDuringPresentation = false
        search.searchBar.placeholder = RuntimePresentation.text("incident_evidence_search")
        navigationItem.searchController = search; navigationItem.hidesSearchBarWhenScrolling = false; definesPresentationContext = true
        updateSearchResults(for: search)
    }
    func updateSearchResults(for searchController: UISearchController) {
        let query = searchController.searchBar.text ?? ""
        let count: Int
        switch evidence {
        case .logs(let value): count = value.events.count
        case .http(let value): count = value.events.count
        case .webSockets(let value): count = value.events.count
        }
        indices = (0..<count).reversed().filter { query.isEmpty || searchText($0).localizedCaseInsensitiveContains(query) }
        tableView.reloadData()
    }
    private func searchText(_ index: Int) -> String {
        // Search only admitted text; do not format dates or inspect network bodies for each keystroke.
        switch evidence {
        case .logs(let value):
            let event = value.events[index]; return event.level.rawValue + " " + event.category + " " + event.message
        case .http(let value):
            let event = value.events[index]; return event.method + " " + event.url
        case .webSockets(let value):
            let event = value.events[index]; return WebSocketPresentation.summary(event) + " " + event.url + " " + event.connectionID
        }
    }
    private func row(_ index: Int) -> (title: String, subtitle: String) {
        switch evidence {
        case .logs(let value):
            let event = value.events[index], title = event.level.rawValue.uppercased() + " · " + event.category
            return (title, RuntimePresentation.date(event.timestampMillis) + " · #\(event.sequence)\n" + event.message)
        case .http(let value):
            let event = value.events[index], title = event.method + " " + event.url
            return (title, HTTPPresentation.summary(event) + "\n" + RuntimePresentation.date(event.timestampMillis))
        case .webSockets(let value):
            let event = value.events[index], title = WebSocketPresentation.summary(event)
            return (title, event.url + "\n" + RuntimePresentation.date(event.timestampMillis) + "\n" + event.connectionID)
        }
    }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { indices.count }
    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        switch evidence {
        case .logs(let value): return IncidentPresentation.window(time: value.observedAtMillis, count: value.events.count, omitted: value.omittedEventCount, evicted: value.sourceEvictedCount)
        case .http(let value): return IncidentPresentation.window(time: value.observedAtMillis, count: value.events.count, omitted: value.omittedEventCount,
            evicted: value.sourceEvictedEvents, payloadEvicted: value.sourceEvictedBodies)
        case .webSockets(let value): return IncidentPresentation.window(time: value.observedAtMillis, count: value.events.count, omitted: value.omittedEventCount,
            evicted: value.sourceEvictedEvents, payloadEvicted: value.sourceEvictedPayloads)
        }
    }
    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        (indices.isEmpty ? RuntimePresentation.text((search.searchBar.text ?? "").isEmpty ? "incident_evidence_empty" : "ws_no_matches") + "\n\n" : "")
            + RuntimePresentation.text("incident_frozen_detail")
    }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "frozen-event") ?? UITableViewCell(style: .subtitle, reuseIdentifier: "frozen-event")
        PersistencePresentation.style(cell); cell.accessoryType = .disclosureIndicator; cell.accessibilityTraits = .button
        let value = row(indices[indexPath.row]); cell.textLabel?.text = value.title; cell.detailTextLabel?.text = value.subtitle
        cell.textLabel?.numberOfLines = 2; cell.detailTextLabel?.numberOfLines = 3
        return cell
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        let index = indices[indexPath.row], controller: UIViewController
        switch evidence {
        case .logs(let value): controller = LogDetailViewController(event: value.events[index])
        case .http(let value): controller = HTTPDetailViewController(event: value.events[index], savedPayloads: value.payloads)
        case .webSockets(let value): controller = WebSocketDetailViewController(event: value.events[index], savedPayloads: value.payloads)
        }
        navigationController?.pushViewController(controller, animated: true)
    }
}
#endif

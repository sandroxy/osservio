#if canImport(UIKit)
import OsservioCore
import UIKit

enum SignalCrashPresentation {
    static func address(_ value: UInt64) -> String { "0x" + String(value, radix: 16) }
    static func count(_ value: Int, limited: Bool) -> String {
        if value == 0 { return RuntimePresentation.text("runtime_unavailable") }
        return String(value) + (limited ? " · " + RuntimePresentation.text("crash_limit_reached") : "")
    }
    static func image(_ image: AppleSignalCrash.Image) -> String {
        let unavailable = RuntimePresentation.text("runtime_unavailable")
        return [("crash_base_address", address(image.baseAddress)),
                ("crash_image_size", image.size == 0 ? unavailable : "\(image.size) B"),
                ("crash_cpu_type", image.cpuType.map(String.init) ?? unavailable),
                ("crash_cpu_subtype", image.cpuSubtype.map(String.init) ?? unavailable)]
            .map { RuntimePresentation.text($0.0) + ": " + $0.1 }.joined(separator: "\n")
    }
}

/// Formats only visible immutable rows; copying the whole bounded list is explicit.
@MainActor
final class SignalCrashViewController: UITableViewController {
    enum Content { case frames, images }
    private let crash: AppleSignalCrash
    private let content: Content
    init(_ crash: AppleSignalCrash, content: Content) {
        self.crash = crash; self.content = content; super.init(style: .insetGrouped)
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    override func viewDidLoad() {
        super.viewDidLoad(); title = RuntimePresentation.text(content == .frames ? "crash_frames" : "crash_images")
        tableView.rowHeight = UITableView.automaticDimension; tableView.estimatedRowHeight = 100
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: RuntimePresentation.text("crash_copy_all"), style: .plain,
            target: self, action: #selector(copyAll))
    }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        content == .frames ? crash.frames.count : crash.images.count
    }
    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        RuntimePresentation.text(content == .frames ? "crash_frames_hint" : "crash_images_hint") + "\n" + RuntimePresentation.text("crash_copy_hint")
    }
    private func row(_ index: Int) -> (String, String) {
        if content == .frames { return ("#\(index)", SignalCrashPresentation.address(crash.frames[index])) }
        let image = crash.images[index]
        return ("#\(index) · " + (image.uuid ?? RuntimePresentation.text("runtime_unavailable")), SignalCrashPresentation.image(image))
    }
    func copyText(at index: Int) -> String {
        let value = row(index); return value.0 + "\n" + value.1
    }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "signal-evidence") ?? UITableViewCell(style: .subtitle, reuseIdentifier: "signal-evidence")
        PersistencePresentation.style(cell)
        let value = row(indexPath.row); cell.textLabel?.text = value.0; cell.detailTextLabel?.text = value.1
        cell.accessibilityTraits = .button; cell.accessibilityHint = RuntimePresentation.text("crash_copy_hint")
        return cell
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true); copy(copyText(at: indexPath.row))
    }
    @objc private func copyAll() {
        copy((0..<tableView(tableView, numberOfRowsInSection: 0)).map { copyText(at: $0) }.joined(separator: "\n\n"))
    }
    private func copy(_ value: String) {
        UIPasteboard.general.string = value
        UIAccessibility.post(notification: .announcement, argument: RuntimePresentation.text("crash_copied"))
    }
}
#endif

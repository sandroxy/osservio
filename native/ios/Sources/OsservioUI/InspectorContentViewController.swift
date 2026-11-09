#if canImport(UIKit)
import UIKit

@MainActor
class InspectorContentViewController: UIViewController, UITextFieldDelegate {
    private let content: String
    private let code: Bool
    private let text = UITextView()
    private let search = UISearchTextField()
    private let controls = UIScrollView()
    private let count = InspectorTheme.label(.caption1, secondary: true)
    private let previous = UIButton(type: .system), nextButton = UIButton(type: .system)
    private var matches = InspectorTextMatches(content: "", query: "")
    private var position = 0
    private var work: DispatchWorkItem?
    private var bottom: NSLayoutConstraint?

    init(title: String, content: String, code: Bool) {
        self.content = content; self.code = code
        super.init(nibName: nil, bundle: nil); self.title = title
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    deinit { work?.cancel(); NotificationCenter.default.removeObserver(self) }
    override func viewDidLoad() {
        super.viewDidLoad(); InspectorTheme.apply(self)
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: InspectorLocalization.text("ui_copy_all"), style: .plain, target: self, action: #selector(copyContent))
        search.placeholder = InspectorLocalization.text("ui_search_content"); search.delegate = self
        search.font = .preferredFont(forTextStyle: .body); search.adjustsFontForContentSizeCategory = true
        search.autocorrectionType = .no; search.autocapitalizationType = .none; search.returnKeyType = .done
        search.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        search.addTarget(self, action: #selector(searchChanged), for: .editingChanged)
        previous.setTitle(InspectorLocalization.text("ui_previous_match"), for: .normal)
        nextButton.setTitle(InspectorLocalization.text("ui_next_match"), for: .normal)
        previous.addTarget(self, action: #selector(previousMatch), for: .touchUpInside)
        nextButton.addTarget(self, action: #selector(nextMatch), for: .touchUpInside)
        let stack = UIStackView(arrangedSubviews: [search, count, InspectorControls(buttons: [previous, nextButton])])
        stack.axis = .vertical; stack.spacing = 6; stack.isLayoutMarginsRelativeArrangement = true
        stack.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16)
        stack.translatesAutoresizingMaskIntoConstraints = false; controls.addSubview(stack)
        controls.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(controls)
        text.isEditable = false; text.isSelectable = true; text.adjustsFontForContentSizeCategory = true
        text.backgroundColor = InspectorTheme.surface; text.keyboardDismissMode = .onDrag
        text.textContainerInset = UIEdgeInsets(top: 16, left: 12, bottom: 16, right: 12)
        text.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(text)
        let readingArea = UILayoutGuide(); view.addLayoutGuide(readingArea)
        let desired = controls.heightAnchor.constraint(equalTo: stack.heightAnchor); desired.priority = .defaultHigh
        bottom = readingArea.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor)
        NSLayoutConstraint.activate([
            readingArea.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            readingArea.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
            readingArea.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
            controls.topAnchor.constraint(equalTo: readingArea.topAnchor),
            controls.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
            controls.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
            controls.heightAnchor.constraint(lessThanOrEqualTo: readingArea.heightAnchor, multiplier: 0.5), desired,
            stack.topAnchor.constraint(equalTo: controls.contentLayoutGuide.topAnchor), stack.bottomAnchor.constraint(equalTo: controls.contentLayoutGuide.bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: controls.contentLayoutGuide.leadingAnchor), stack.trailingAnchor.constraint(equalTo: controls.contentLayoutGuide.trailingAnchor),
            stack.widthAnchor.constraint(equalTo: controls.frameLayoutGuide.widthAnchor),
            text.topAnchor.constraint(equalTo: controls.bottomAnchor), bottom!, text.bottomAnchor.constraint(equalTo: readingArea.bottomAnchor),
            text.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor), text.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor)
        ])
        NotificationCenter.default.addObserver(self, selector: #selector(keyboard(_:)), name: UIResponder.keyboardWillChangeFrameNotification, object: nil)
        render()
    }
    override func viewWillDisappear(_ animated: Bool) { super.viewWillDisappear(animated); view.endEditing(true); work?.cancel() }
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated); InspectorTheme.apply(self)
        navigationItem.rightBarButtonItem?.title = InspectorLocalization.text("ui_copy_all")
        search.placeholder = InspectorLocalization.text("ui_search_content")
        previous.setTitle(InspectorLocalization.text("ui_previous_match"), for: .normal)
        nextButton.setTitle(InspectorLocalization.text("ui_next_match"), for: .normal); render()
    }
    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        if isViewLoaded { render() }
    }
    @objc private func keyboard(_ notification: Notification) {
        guard view.window != nil, let frame = notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect else { return }
        let intersection = view.bounds.intersection(view.convert(frame, from: nil))
        bottom?.constant = intersection.maxY >= view.bounds.maxY - 1 ? -max(0, intersection.height - view.safeAreaInsets.bottom) : 0
    }
    @objc private func copyContent() { UIPasteboard.general.string = content }
    @objc private func searchChanged() {
        guard search.markedTextRange == nil else { return }
        work?.cancel()
        let query = String((search.text ?? "").prefix(512)); if query != search.text { search.text = query }
        let task = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.matches = InspectorTextMatches(content: self.content, query: query); self.position = 0; self.render()
        }
        work = task; DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(120), execute: task)
    }
    @objc private func previousMatch() { guard !matches.ranges.isEmpty else { return }; position = (position + matches.ranges.count - 1) % matches.ranges.count; render() }
    @objc private func nextMatch() { guard !matches.ranges.isEmpty else { return }; position = (position + 1) % matches.ranges.count; render() }
    private func render() {
        let font = code ? UIFontMetrics(forTextStyle: .body).scaledFont(for: .monospacedSystemFont(ofSize: 15, weight: .regular), compatibleWith: traitCollection)
            : UIFont.preferredFont(forTextStyle: .body, compatibleWith: traitCollection)
        let value = NSMutableAttributedString(string: content, attributes: [.font: font, .foregroundColor: UIColor.label])
        if !matches.ranges.isEmpty { value.addAttribute(.backgroundColor, value: InspectorTheme.tint, range: matches.ranges[position]) }
        let offset = text.contentOffset; text.attributedText = value
        if !matches.ranges.isEmpty { text.scrollRangeToVisible(matches.ranges[position]) } else { text.setContentOffset(offset, animated: false) }
        previous.isEnabled = !matches.ranges.isEmpty; nextButton.isEnabled = !matches.ranges.isEmpty
        count.text = matches.ranges.isEmpty ? String(format: InspectorLocalization.text("ui_matches"), 0)
            : String(format: InspectorLocalization.text("ui_match_position"), position + 1, matches.ranges.count)
        if matches.limited { count.text! += " · " + String(format: InspectorLocalization.text("ui_match_limit"), matches.ranges.count) }
    }
    func textFieldShouldReturn(_ textField: UITextField) -> Bool { textField.resignFirstResponder(); return true }
}
#endif

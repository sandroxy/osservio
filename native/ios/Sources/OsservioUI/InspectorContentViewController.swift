#if canImport(UIKit)
import UIKit

@MainActor
class InspectorContentViewController: UIViewController, UITextFieldDelegate {
    private let value: InspectorReadContent
    private var content: String { value.text }
    private let code: Bool
    private let text = UITextView()
    private let search = UISearchTextField()
    private let controls = UIScrollView()
    private var titleKey: String?
    private let mode = InspectorScopeControl(titles: ["", ""])
    private let origin = InspectorTheme.label(.caption1, secondary: true)
    private let notice = InspectorTheme.label(.caption1, secondary: true)
    private let tree = InspectorJSONTreeView()
    private let form = InspectorFormView()
    private var formParsing: InspectorFormTask?
    private var searchable: String { mode.selectedIndex == 0 ? form.document?.searchText ?? content : content }
    private let count = InspectorTheme.label(.caption1, secondary: true)
    private let previous = UIButton(type: .system), nextButton = UIButton(type: .system)
    private lazy var matchNavigation = InspectorControls(buttons: [previous, nextButton])
    private var matches = InspectorTextMatches(content: "", query: "")
    private var position = 0
    private var appliedQuery = ""
    private var work: DispatchWorkItem?
    private var bottom: NSLayoutConstraint?
    private var parsing: InspectorJSONTask?
    private var parsed = false
    private var active = false
    private var generation = 0
    private var parserNotice: String?

    init(title: String, content: String, code: Bool) {
        value = InspectorReadContent(text: content, allowsJSON: code); self.code = code
        super.init(nibName: nil, bundle: nil); self.title = title
    }
    init(title: String, value: InspectorReadContent) {
        self.value = value; code = true
        super.init(nibName: nil, bundle: nil); self.title = title
    }
    convenience init(titleKey: String, content: String, code: Bool) {
        self.init(title: InspectorLocalization.text(titleKey), content: content, code: code); self.titleKey = titleKey
    }
    convenience init(titleKey: String, value: InspectorReadContent) {
        self.init(title: InspectorLocalization.text(titleKey), value: value); self.titleKey = titleKey
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { return nil }
    deinit { work?.cancel(); parsing?.cancel(); formParsing?.cancel(); NotificationCenter.default.removeObserver(self) }
    override func viewDidLoad() {
        super.viewDidLoad(); InspectorTheme.apply(self)
        inspectorPrimaryAction = UIBarButtonItem(image: UIImage(systemName: "doc.on.doc"), style: .plain, target: self, action: #selector(copyContent))
        inspectorPrimaryAction?.accessibilityLabel = InspectorLocalization.text("ui_copy_all")
        search.placeholder = InspectorLocalization.text("ui_search_content"); search.delegate = self
        search.font = .preferredFont(forTextStyle: .body); search.adjustsFontForContentSizeCategory = true
        search.autocorrectionType = .no; search.autocapitalizationType = .none; search.returnKeyType = .done
        search.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        search.addTarget(self, action: #selector(searchChanged), for: .editingChanged)
        previous.setTitle(InspectorLocalization.text("ui_previous_match"), for: .normal)
        nextButton.setTitle(InspectorLocalization.text("ui_next_match"), for: .normal)
        previous.addTarget(self, action: #selector(previousMatch), for: .touchUpInside)
        nextButton.addTarget(self, action: #selector(nextMatch), for: .touchUpInside)
        mode.isHidden = true; mode.onSelect = { [weak self] _ in self?.applySearch() }
        form.open = { [weak self] field in
            self?.inspectorPush(InspectorContentViewController(titleKey: "form_field", value: InspectorReadContent(text: field.value,
                notice: { field.hasEquals ? "" : InspectorLocalization.text("form_missing_equals") }, allowsJSON: true, context: { field.name })))
        }
        origin.numberOfLines = 3
        let stack = UIStackView(arrangedSubviews: [origin, mode, notice, search, count, matchNavigation])
        stack.axis = .vertical; stack.spacing = 6; stack.isLayoutMarginsRelativeArrangement = true
        stack.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16)
        stack.translatesAutoresizingMaskIntoConstraints = false; controls.addSubview(stack)
        controls.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(controls)
        text.isEditable = false; text.isSelectable = true; text.adjustsFontForContentSizeCategory = true
        text.backgroundColor = InspectorTheme.canvas; text.keyboardDismissMode = .onDrag
        text.textContainerInset = UIEdgeInsets(top: 16, left: 12, bottom: 16, right: 12)
        text.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(text)
        text.accessibilityIdentifier = "Osservio.JSON.Raw"
        tree.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(tree)
        form.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(form)
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
            text.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor), text.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
            tree.leadingAnchor.constraint(equalTo: text.leadingAnchor), tree.trailingAnchor.constraint(equalTo: text.trailingAnchor),
            tree.topAnchor.constraint(equalTo: text.topAnchor), tree.bottomAnchor.constraint(equalTo: text.bottomAnchor),
            form.leadingAnchor.constraint(equalTo: text.leadingAnchor), form.trailingAnchor.constraint(equalTo: text.trailingAnchor),
            form.topAnchor.constraint(equalTo: text.topAnchor), form.bottomAnchor.constraint(equalTo: text.bottomAnchor)
        ])
        NotificationCenter.default.addObserver(self, selector: #selector(keyboard(_:)), name: UIResponder.keyboardWillChangeFrameNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(suspendParsing), name: UIApplication.willResignActiveNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(resumeParsing), name: UIApplication.didBecomeActiveNotification, object: nil)
        render()
    }
    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated); active = false; view.endEditing(true); work?.cancel(); suspendParsing()
    }
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated); active = true; InspectorTheme.apply(self)
        if let titleKey { title = InspectorLocalization.text(titleKey) }
        inspectorPrimaryAction?.accessibilityLabel = InspectorLocalization.text("ui_copy_all")
        search.placeholder = InspectorLocalization.text("ui_search_content")
        previous.setTitle(InspectorLocalization.text("ui_previous_match"), for: .normal)
        nextButton.setTitle(InspectorLocalization.text("ui_next_match"), for: .normal)
        let query = search.text ?? ""
        if query != appliedQuery { matches = InspectorTextMatches(content: searchable, query: query); appliedQuery = query; position = 0 }
        tree.refreshPresentation(); form.refreshPresentation(); startParsing(); render()
    }
    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        if isViewLoaded { tree.refreshPresentation(); form.refreshPresentation(); render() }
    }
    @objc private func keyboard(_ notification: Notification) {
        guard view.window != nil, let frame = notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect else { return }
        let intersection = view.bounds.intersection(view.convert(frame, from: nil))
        bottom?.constant = intersection.maxY >= view.bounds.maxY - 1 ? -max(0, intersection.height - view.safeAreaInsets.bottom) : 0
    }
    @objc private func copyContent() { UIPasteboard.general.string = content }
    @objc private func suspendParsing() { generation &+= 1; parsing?.cancel(); parsing = nil; formParsing?.cancel(); formParsing = nil }
    @objc private func resumeParsing() {
        guard active, viewIfLoaded?.window?.isHidden == false else { return }; startParsing()
    }
    private func startParsing() {
        guard !parsed, parsing == nil, formParsing == nil, value.allowsJSON else { return }
        if value.isForm { startFormParsing(); return }
        let first = content.utf16.prefix(128).first { ![9, 10, 13, 32].contains($0) }
        if let first, first != 123 && first != 91 { parsed = true; return }
        generation &+= 1; let ticket = generation
        parserNotice = "json_loading"
        parsing = InspectorJSONTask.submit(content) { [weak self] result in
            guard let self, self.active, self.generation == ticket else { return }
            self.parsing = nil; self.parsed = true
            switch result {
            case .success(let document): self.tree.display(document); self.parserNotice = nil; self.mode.isHidden = false
            case .failure(let reason):
                switch reason {
                case .notJSON: self.parserNotice = nil
                case .invalid: self.parserNotice = "json_invalid"
                case .limited: self.parserNotice = "json_limited"
                case .cancelled: self.parsed = false; return
                }
            }
            self.render(reveal: !self.matches.ranges.isEmpty)
        }
        if parsing == nil { parserNotice = "json_busy" }
    }
    private func startFormParsing() {
        generation &+= 1; let ticket = generation; parserNotice = "form_loading"
        formParsing = InspectorFormTask.submit(content) { [weak self] result in
            guard let self, self.active, self.generation == ticket else { return }
            self.formParsing = nil; self.parsed = true
            switch result {
            case .success(let document): self.form.display(document); self.parserNotice = nil; self.mode.isHidden = false
            case .failure(let reason):
                switch reason {
                case .invalid: self.parserNotice = "form_invalid"
                case .limited: self.parserNotice = "form_limited"
                case .cancelled: self.parsed = false; return
                }
            }
            self.applySearch()
        }
        if formParsing == nil { parserNotice = "json_busy" }
    }
    private func applySearch() {
        let query = search.text ?? ""
        matches = InspectorTextMatches(content: searchable, query: query); appliedQuery = query; position = 0
        render(reveal: true)
    }
    @objc private func searchChanged() {
        guard search.markedTextRange == nil else { return }
        work?.cancel()
        let query = String((search.text ?? "").prefix(512)); if query != search.text { search.text = query }
        let task = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.applySearch()
        }
        work = task; DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(120), execute: task)
    }
    @objc private func previousMatch() { guard !matches.ranges.isEmpty else { return }; position = (position + matches.ranges.count - 1) % matches.ranges.count; render(reveal: true) }
    @objc private func nextMatch() { guard !matches.ranges.isEmpty else { return }; position = (position + 1) % matches.ranges.count; render(reveal: true) }
    private func render(reveal: Bool = false) {
        origin.text = value.context(); origin.isHidden = origin.text?.isEmpty != false
        mode.setTitles([value.isForm ? "form_fields" : "json_structure", "json_raw"].map(InspectorLocalization.text))
        let showForm = form.document != nil && mode.selectedIndex == 0
        let structured = tree.document != nil && mode.selectedIndex == 0
        form.isHidden = !showForm; tree.isHidden = !structured; text.isHidden = structured || showForm
        let notes = [value.notice, parserNotice.map(InspectorLocalization.text) ?? "", structured ? InspectorLocalization.text("json_hint") : showForm ? InspectorLocalization.text(form.document?.fields.isEmpty == true ? "form_empty" : "form_hint") : ""].filter { !$0.isEmpty }
        notice.text = notes.joined(separator: "\n"); notice.isHidden = notes.isEmpty
        let match = matches.ranges.isEmpty ? nil : matches.ranges[position]
        if structured { tree.focus(match, reveal: reveal) }
        if showForm { form.focus(match, reveal: reveal) }
        let font = code ? UIFontMetrics(forTextStyle: .body).scaledFont(for: .monospacedSystemFont(ofSize: 15, weight: .regular), compatibleWith: traitCollection)
            : UIFont.preferredFont(forTextStyle: .body, compatibleWith: traitCollection)
        if !structured && !showForm {
            let value = NSMutableAttributedString(string: content, attributes: [.font: font, .foregroundColor: UIColor.label])
            if let match { value.addAttribute(.backgroundColor, value: InspectorTheme.tint, range: match) }
            let offset = text.contentOffset; text.attributedText = value
            if reveal, let match { text.scrollRangeToVisible(match) } else { text.setContentOffset(offset, animated: false) }
        }
        previous.isEnabled = !matches.ranges.isEmpty; nextButton.isEnabled = !matches.ranges.isEmpty
        count.isHidden = appliedQuery.isEmpty; matchNavigation.isHidden = appliedQuery.isEmpty
        count.text = matches.ranges.isEmpty ? String(format: InspectorLocalization.text("ui_matches"), 0)
            : String(format: InspectorLocalization.text("ui_match_position"), position + 1, matches.ranges.count)
        if matches.limited { count.text! += " · " + String(format: InspectorLocalization.text("ui_match_limit"), matches.ranges.count) }
    }
    func textFieldShouldReturn(_ textField: UITextField) -> Bool { textField.resignFirstResponder(); return true }
}
#endif

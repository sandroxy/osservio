import Foundation

/// These preferences affect SDK presentation only; they never alter host defaults.
final class InspectorPreferences {
    enum Language: String, CaseIterable { case chinese = "zh-Hans", english = "en" }
    enum Appearance: String, CaseIterable { case light, dark }
    static let shared = InspectorPreferences(defaults: .standard)
    static let didChange = Notification.Name("com.sandrox.osservio.inspector.preferences")
    private let defaults: UserDefaults
    init(defaults: UserDefaults) { self.defaults = defaults }

    var language: Language {
        get { defaults.string(forKey: "osservio.ui.language").flatMap(Language.init) ?? .chinese }
        set {
            guard newValue != language else { return }
            defaults.set(newValue.rawValue, forKey: "osservio.ui.language")
            NotificationCenter.default.post(name: Self.didChange, object: self)
        }
    }
    var appearance: Appearance {
        get { defaults.string(forKey: "osservio.ui.appearance").flatMap(Appearance.init) ?? .light }
        set {
            guard newValue != appearance else { return }
            defaults.set(newValue.rawValue, forKey: "osservio.ui.appearance")
            NotificationCenter.default.post(name: Self.didChange, object: self)
        }
    }
}

enum InspectorLocalization {
    static func text(_ key: String) -> String {
        guard let path = Bundle.module.path(forResource: InspectorPreferences.shared.language.rawValue, ofType: "lproj"),
              let bundle = Bundle(path: path) else { return key }
        return bundle.localizedString(forKey: key, value: nil, table: key.hasPrefix("ui_") ? "Inspector" : nil)
    }
    static func date(_ millis: Int64, timeOnly: Bool = false) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: InspectorPreferences.shared.language.rawValue)
        formatter.dateStyle = timeOnly ? .none : .medium
        formatter.timeStyle = .medium
        return formatter.string(from: Date(timeIntervalSince1970: Double(millis) / 1_000))
    }
}

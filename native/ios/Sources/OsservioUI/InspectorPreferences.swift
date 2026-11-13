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
    var palette: InspectorPalette {
        get { defaults.string(forKey: "osservio.ui.palette").flatMap(InspectorPalette.init) ?? .purple }
        set {
            guard newValue != palette else { return }
            defaults.set(newValue.rawValue, forKey: "osservio.ui.palette")
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
    private static let bundles: [String: Bundle] = {
        var result: [String: Bundle] = [:]
        for language in InspectorPreferences.Language.allCases {
            // SwiftPM may normalize language-directory casing; use the bundle's declared identifier.
            guard let identifier = Bundle.module.localizations.first(where: { $0.caseInsensitiveCompare(language.rawValue) == .orderedSame }),
                  let path = Bundle.module.path(forResource: identifier, ofType: "lproj"),
                  let bundle = Bundle(path: path) else { continue }
            result[language.rawValue] = bundle
        }
        return result
    }()
    static func text(_ key: String) -> String {
        guard let bundle = bundles[InspectorPreferences.shared.language.rawValue] else { return key }
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

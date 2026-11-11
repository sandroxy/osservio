#if canImport(UIKit)
import OsservioCore
import UIKit

@MainActor
enum EnvironmentPresentation {
    static func sections(_ snapshot: EnvironmentSnapshot) -> [InspectorDetailViewController.Section] {
        let groups: [(String, [EnvironmentKey])] = [
            ("ui_app", [.appIdentifier, .appVersion, .appBuild]),
            ("ui_device", [.osVersion, .manufacturer, .deviceModel, .architecture, .physicalMemoryBytes]),
            ("ui_process", [.processID, .processResidentBytes, .processProportionalBytes, .processFootprintBytes, .runtimeHeapBytes, .runtimeHeapLimitBytes]),
            ("ui_power", [.batteryPercent, .lowPowerMode, .thermalState])
        ]
        var unsupported: [InspectorDetailViewController.Row] = []
        var result: [InspectorDetailViewController.Section] = []
        for (title, keys) in groups {
            var rows: [InspectorDetailViewController.Row] = []
            for key in keys {
                guard let value = snapshot.values[key] else { continue }
                let row = InspectorDetailViewController.Row(InspectorLocalization.text("env_" + key.rawValue), RuntimePresentation.value(value, key: key, compact: true))
                if case .unsupported = value.fact { unsupported.append(row) } else { rows.append(row) }
            }
            if !rows.isEmpty { result.append(InspectorDetailViewController.Section(title: InspectorLocalization.text(title), rows: rows)) }
        }
        let exact = EnvironmentKey.allCases.compactMap { key -> InspectorDetailViewController.Row? in
            guard key.rawValue.hasSuffix("Bytes"), let value = snapshot.values[key], case .number(let number) = value.fact else { return nil }
            return InspectorDetailViewController.Row(InspectorLocalization.text("env_" + key.rawValue), "\(number) B")
        }
        if !exact.isEmpty { result.append(InspectorDetailViewController.Section(title: InspectorLocalization.text("ui_original_value"), rows: exact, collapsible: true)) }
        if !unsupported.isEmpty { result.append(InspectorDetailViewController.Section(title: InspectorLocalization.text("ui_unsupported_fields"), rows: unsupported, collapsible: true)) }
        return result
    }
}
#endif

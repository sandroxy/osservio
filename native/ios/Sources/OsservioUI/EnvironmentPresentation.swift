#if canImport(UIKit)
import OsservioCore
import UIKit

@MainActor
enum EnvironmentPresentation {
    static func sections(_ snapshot: EnvironmentSnapshot) -> [InspectorDetailViewController.Section] {
        let groups: [(String, [EnvironmentKey])] = [
            ("ui_app", [.appIdentifier, .appVersion, .appBuild]),
            ("ui_device", [.osVersion, .manufacturer, .deviceModel, .architecture]),
            ("ui_process", [.processID, .physicalMemoryBytes, .processResidentBytes, .processProportionalBytes, .processFootprintBytes, .runtimeHeapBytes, .runtimeHeapLimitBytes]),
            ("ui_power", [.batteryPercent, .lowPowerMode, .thermalState])
        ]
        return groups.map { title, keys in
            InspectorDetailViewController.Section(title: InspectorLocalization.text(title), rows: keys.map { key in
                InspectorDetailViewController.Row(InspectorLocalization.text("env_" + key.rawValue),
                    snapshot.values[key].map { RuntimePresentation.value($0, key: key) } ?? InspectorLocalization.text("runtime_unavailable"))
            })
        }
    }
}
#endif

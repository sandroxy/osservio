#if canImport(UIKit)
import Darwin
import OsservioCore
import UIKit

/// Explicit one-shot reads. No monitoring, permission prompts, stable device identifiers, or user-assigned names.
public final class RuntimeEnvironment: EnvironmentProvider, @unchecked Sendable {
    private let queue = DispatchQueue(label: "io.github.sandroxy.osservio.environment", qos: .utility)
    private let pending = DispatchSemaphore(value: 1)
    public init() {}
    public func read(completion: @escaping @Sendable (Result<EnvironmentSnapshot, EnvironmentError>) -> Void) {
        guard pending.wait(timeout: .now()) == .success else { completion(.failure(.busy)); return }
        queue.async { [self] in
            var facts = Dictionary(uniqueKeysWithValues: EnvironmentKey.allCases.map { ($0, EnvironmentFact.unsupported) })
            func text(_ value: String?) -> EnvironmentFact { value.flatMap { $0.isEmpty ? nil : .text($0) } ?? .unavailable }
            let bundle = Bundle.main
            facts[.appIdentifier] = text(bundle.bundleIdentifier)
            facts[.appVersion] = text(bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String)
            facts[.appBuild] = text(bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String)
            facts[.osVersion] = .text(ProcessInfo.processInfo.operatingSystemVersionString)
            facts[.manufacturer] = .text("Apple")
            var system = utsname()
            if uname(&system) == 0 {
                facts[.deviceModel] = withUnsafeBytes(of: system.machine) { bytes in
                    .text(String(decoding: bytes.prefix { $0 != 0 }, as: UTF8.self))
                }
            } else { facts[.deviceModel] = .unavailable }
            #if arch(arm64)
            facts[.architecture] = .text("arm64")
            #elseif arch(x86_64)
            facts[.architecture] = .text("x86_64")
            #else
            facts[.architecture] = .unavailable
            #endif
            facts[.physicalMemoryBytes] = Int64(exactly: ProcessInfo.processInfo.physicalMemory).map(EnvironmentFact.number) ?? .unavailable
            facts[.processID] = .number(Int64(getpid()))
            facts[.lowPowerMode] = .flag(ProcessInfo.processInfo.isLowPowerModeEnabled)
            switch ProcessInfo.processInfo.thermalState {
            case .nominal: facts[.thermalState] = .text("nominal")
            case .fair: facts[.thermalState] = .text("fair")
            case .serious: facts[.thermalState] = .text("serious")
            case .critical: facts[.thermalState] = .text("critical")
            @unknown default: facts[.thermalState] = .unavailable
            }
            var memory = task_vm_info_data_t()
            var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<integer_t>.size)
            let memoryResult = withUnsafeMutablePointer(to: &memory) {
                $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count) }
            }
            facts[.processResidentBytes] = memoryResult == KERN_SUCCESS ? Int64(exactly: memory.resident_size).map(EnvironmentFact.number) ?? .unavailable : .unavailable
            facts[.processFootprintBytes] = memoryResult == KERN_SUCCESS ? Int64(exactly: memory.phys_footprint).map(EnvironmentFact.number) ?? .unavailable : .unavailable
            let backgroundFacts = facts
            DispatchQueue.main.async { [self] in
                var facts = backgroundFacts
                let device = UIDevice.current
                let battery = device.isBatteryMonitoringEnabled ? device.batteryLevel : -1
                facts[.batteryPercent] = battery >= 0 && battery <= 1 ? .number(Int64((battery * 100).rounded())) : .unavailable
                let result: Result<EnvironmentSnapshot, EnvironmentError>
                do { result = .success(try EnvironmentSnapshot.capture(platform: .ios, publicFacts: facts)) }
                catch { result = .failure(.unavailable) }
                pending.signal()
                completion(result)
            }
        }
    }
}
#endif

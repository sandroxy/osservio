#if canImport(UIKit)
import OsservioCore
import UIKit

enum TombstonePresentation {
    static func state(_ state: SystemDiagnostic.TombstoneState) -> String { RuntimePresentation.text("tombstone_" + state.rawValue) }
    static func architecture(_ code: Int32) -> String {
        let known = ["ARM32", "ARM64", "X86", "X86_64", "RISCV64", "NONE"]
        return code < known.count ? known[Int(code)] : "#\(code)"
    }
    static func frame(_ frame: AndroidTombstone.Frame) -> String {
        [("tombstone_pc", SignalCrashPresentation.address(frame.programCounter)),
         ("tombstone_relative_pc", SignalCrashPresentation.address(frame.relativeProgramCounter)),
         ("tombstone_build_id", frame.buildID ?? RuntimePresentation.text("runtime_unavailable"))]
            .map { RuntimePresentation.text($0.0) + ": " + $0.1 }.joined(separator: "\n")
    }
}
#endif

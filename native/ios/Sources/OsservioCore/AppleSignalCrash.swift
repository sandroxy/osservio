import Foundation

/// Address-only evidence from an Apple BSD fatal-signal recorder. Contains no raw stack memory,
/// register values, image paths, exception text, or current-launch context.
public struct AppleSignalCrash: Sendable {
    public enum Signal: String, Sendable {
        case abort = "SIGABRT", bus = "SIGBUS", floatingPoint = "SIGFPE"
        case illegalInstruction = "SIGILL", segmentationViolation = "SIGSEGV", trap = "SIGTRAP"
    }
    public struct Image: Sendable {
        public let baseAddress: UInt64
        /// Zero means that no usable address extent was supplied.
        public let size: UInt64
        /// Lowercase 32-digit Mach-O UUID, without separators. Nil means unavailable.
        public let uuid: String?
        /// Mach CPU identifiers, preserved exactly. Both are nil when unavailable.
        public let cpuType: UInt64?
        public let cpuSubtype: UInt64?

        public static func restore(baseAddress: UInt64, size: UInt64, uuid: String?,
                                   cpuType: UInt64?, cpuSubtype: UInt64?) throws -> Self {
            guard size <= UInt64.max - baseAddress, (cpuType == nil) == (cpuSubtype == nil) else {
                throw PersistenceError.invalidRecord
            }
            if let uuid {
                let bytes = Array(uuid.utf8.prefix(33))
                guard bytes.count == 32, bytes.allSatisfy({ (48...57).contains($0) || (97...102).contains($0) }) else {
                    throw PersistenceError.invalidRecord
                }
            }
            return Self(baseAddress: baseAddress, size: size, uuid: uuid, cpuType: cpuType, cpuSubtype: cpuSubtype)
        }
    }

    public let reportID: String
    /// Recorder time, at whole-second precision. Recovery observation time belongs to the enclosing incident.
    public let timestampMillis: Int64
    public let signal: Signal
    /// Reviewed symbolic code or '#' followed by a canonical signed 32-bit decimal value.
    public let code: String
    public let faultAddress: UInt64
    /// Recorder-local enumeration index, not an OS thread ID. Nil means that no faulting thread was recovered.
    public let threadNumber: UInt32?
    /// Instruction addresses, newest frame first. Empty means unavailable, never a complete empty call stack.
    public let frames: [UInt64]
    public let images: [Image]
    public let metadata: [SystemDiagnostic.MetadataKey: SystemDiagnostic.Text]
    /// The backend excluded at least one loader-cache entry. The number and reason are unavailable.
    public let imageCacheIncomplete: Bool
    public var frameLimitReached: Bool { frames.count == 128 }
    public var imageLimitReached: Bool { images.count == 512 }
    public var textByteCount: Int {
        reportID.utf8.count + signal.rawValue.utf8.count + code.utf8.count
            + images.reduce(0) { $0 + ($1.uuid?.utf8.count ?? 0) }
            + metadata.values.reduce(0) { $0 + $1.value.utf8.count }
    }

    public static func restore(reportID: String, timestampMillis: Int64, signal: Signal, code: String,
                               faultAddress: UInt64, threadNumber: UInt32?, frames: [UInt64], images: [Image],
                               metadata: [SystemDiagnostic.MetadataKey: SystemDiagnostic.Text],
                               imageCacheIncomplete: Bool) throws -> Self {
        guard Array(reportID.utf8.prefix(37)).count == 36,
              UUID(uuidString: reportID)?.uuidString.lowercased() == reportID,
              timestampMillis > 0, timestampMillis % 1_000 == 0, validCode(code, signal: signal),
              frames.count <= 128, images.count <= 512, threadNumber != nil || frames.isEmpty,
              metadata.values.allSatisfy({ !$0.isTruncated && !$0.value.utf8.contains(0) }) else {
            throw PersistenceError.invalidRecord
        }
        return Self(reportID: reportID, timestampMillis: timestampMillis, signal: signal, code: code,
                    faultAddress: faultAddress, threadNumber: threadNumber, frames: frames, images: images,
                    metadata: metadata, imageCacheIncomplete: imageCacheIncomplete)
    }

    private static func validCode(_ code: String, signal: Signal) -> Bool {
        guard Array(code.utf8.prefix(17)).count <= 16 else { return false }
        if code.first == "#", let value = Int32(code.dropFirst()), "#\(value)" == code { return true }
        switch signal {
        case .abort: return false
        case .bus: return ["BUS_NOOP", "BUS_ADRALN", "BUS_ADRERR", "BUS_OBJERR"].contains(code)
        case .floatingPoint:
            return ["FPE_NOOP", "FPE_FLTDIV", "FPE_FLTOVF", "FPE_FLTUND", "FPE_FLTRES", "FPE_FLTINV", "FPE_FLTSUB", "FPE_INTDIV", "FPE_INTOVF"].contains(code)
        case .illegalInstruction:
            return ["ILL_NOOP", "ILL_ILLOPC", "ILL_ILLTRP", "ILL_PRVOPC", "ILL_ILLOPN", "ILL_ILLADR", "ILL_PRVREG", "ILL_COPROC", "ILL_BADSTK"].contains(code)
        case .segmentationViolation: return ["SEGV_NOOP", "SEGV_MAPERR", "SEGV_ACCERR"].contains(code)
        case .trap: return ["TRAP_BRKPT", "TRAP_TRACE"].contains(code)
        }
    }
}

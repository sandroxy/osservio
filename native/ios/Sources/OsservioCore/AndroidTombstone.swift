import Foundation

/// Reviewed address evidence from an Android system tombstone. This model does not collect Android data on iOS.
public struct AndroidTombstone: Sendable {
    public struct Frame: Sendable {
        public let programCounter: UInt64
        /// ELF-relative PC reported by the unwinder, not a file offset. Do not adjust it a second time.
        public let relativeProgramCounter: UInt64
        /// Lowercase ELF build ID, or nil when unavailable. Not a filename or a Mach-O UUID.
        public let buildID: String?
        public static func restore(programCounter: UInt64, relativeProgramCounter: UInt64, buildID: String?) throws -> Self {
            if let buildID {
                let bytes = Array(buildID.utf8.prefix(129))
                guard !bytes.isEmpty, bytes.count <= 128, bytes.count % 2 == 0,
                      bytes.allSatisfy({ (48...57).contains($0) || (97...102).contains($0) }) else {
                    throw PersistenceError.invalidRecord
                }
            }
            return Self(programCounter: programCounter, relativeProgramCounter: relativeProgramCounter, buildID: buildID)
        }
    }

    /// AOSP Architecture: ARM32=0, ARM64=1, X86=2, X86_64=3, RISCV64=4, NONE=5. Unknown nonnegative values are preserved.
    public let architectureCode: Int32
    public let signalNumber: Int32
    public let signalCode: Int32
    /// OS thread ID, not an enumeration index.
    public let threadID: Int32
    /// Nil when the signal has no fault address; zero is a valid reported address.
    public let faultAddress: UInt64?
    /// Faulting-thread frames, newest first. Empty means unavailable, not a complete empty stack.
    public let frames: [Frame]
    /// An unknown number of frames may be absent when the count reaches the cap.
    public var frameLimitReached: Bool { frames.count == 128 }
    public var textByteCount: Int { frames.reduce(0) { $0 + ($1.buildID?.utf8.count ?? 0) } }

    public static func restore(architectureCode: Int32, signalNumber: Int32, signalCode: Int32, threadID: Int32,
                               faultAddress: UInt64?, frames: [Frame]) throws -> Self {
        guard architectureCode >= 0, signalNumber > 0, threadID > 0, frames.count <= 128 else {
            throw PersistenceError.invalidRecord
        }
        return Self(architectureCode: architectureCode, signalNumber: signalNumber, signalCode: signalCode,
                    threadID: threadID, faultAddress: faultAddress, frames: frames)
    }
}

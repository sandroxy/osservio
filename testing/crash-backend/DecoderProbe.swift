import Foundation
@testable import OsservioCrash

guard CommandLine.arguments.count == 2 else { exit(64) }
let data = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))
let report = try CrashReportDecoder.decode(data)
guard report.signal.rawValue == "SIGABRT", !report.frames.isEmpty, !report.images.isEmpty,
      report.threadNumber != nil, report.timestampMillis > 0 else { exit(70) }
let receipt: [String: Any] = ["reportID": report.reportID, "timestampMillis": report.timestampMillis,
                            "frames": report.frames.count, "images": report.images.count, "signal": report.signal.rawValue]
let encoded = try JSONSerialization.data(withJSONObject: receipt, options: [.sortedKeys])
print(String(decoding: encoded, as: UTF8.self))

import Foundation
import OsservioCore
import OsservioCrashBackend

enum CrashReportDecodingError: Error { case invalidReport, requestedReport, unsupportedProfile }

/// Safe-launch decoding only. The byte profile and permitted fields are validated before Core receives evidence.
enum CrashReportDecoder {
    static func decode(_ data: Data) throws -> AppleSignalCrash {
        guard (8...65_536).contains(data.count) else { throw CrashReportDecodingError.invalidReport }
        let report: Osservio_PLCrashReport
        do { report = try Osservio_PLCrashReport(data: data) }
        catch { throw CrashReportDecodingError.invalidReport }
        guard report.hasReportInfo else { throw CrashReportDecodingError.invalidReport }
        guard !report.userRequested else { throw CrashReportDecodingError.requestedReport }
        let marker = Data("OsservioCrash/1".utf8)
        guard let profile = report.customData, profile.count == marker.count + 1,
              profile.prefix(marker.count) == marker, let flags = profile.last, flags & ~1 == 0 else {
            throw CrashReportDecodingError.unsupportedProfile
        }
        guard !report.hasProcessInfo, !report.hasExceptionInfo, report.machExceptionInfo == nil,
              let uuid = report.uuidRef, let system = report.systemInfo, let time = system.timestamp,
              let application = report.applicationInfo, let signal = report.signalInfo,
              let signalName = AppleSignalCrash.Signal(rawValue: signal.name),
              let threads = report.threads, threads.count <= 1, let images = report.images, images.count <= 512 else {
            throw CrashReportDecodingError.invalidReport
        }
        guard let seconds = Int64(exactly: time.timeIntervalSince1970), seconds > 0 else {
            throw CrashReportDecodingError.invalidReport
        }
        let (timestamp, overflow) = seconds.multipliedReportingOverflow(by: 1_000)
        guard !overflow else { throw CrashReportDecodingError.invalidReport }

        var threadNumber: UInt32?, frames: [UInt64] = []
        if let first = threads.first {
            guard let thread = first as? Osservio_PLCrashReportThreadInfo, thread.crashed,
                  let number = UInt32(exactly: thread.threadNumber), thread.registers?.isEmpty == true,
                  let stack = thread.stackFrames, stack.count <= 128 else { throw CrashReportDecodingError.invalidReport }
            threadNumber = number
            for entry in stack {
                guard let frame = entry as? Osservio_PLCrashReportStackFrameInfo, frame.symbolInfo == nil else {
                    throw CrashReportDecodingError.invalidReport
                }
                frames.append(frame.instructionPointer)
            }
        }
        do {
            var mappedImages: [AppleSignalCrash.Image] = []
            for entry in images {
                guard let image = entry as? Osservio_PLCrashReportBinaryImageInfo, image.imageName == "",
                      image.hasImageUUID == (image.imageUUID != nil),
                      image.codeType == nil || image.codeType.typeEncoding.rawValue == 1 else {
                    throw CrashReportDecodingError.invalidReport
                }
                mappedImages.append(try .restore(baseAddress: image.imageBaseAddress, size: image.imageSize,
                    uuid: image.imageUUID, cpuType: image.codeType?.type, cpuSubtype: image.codeType?.subtype))
            }
            var metadata: [SystemDiagnostic.MetadataKey: SystemDiagnostic.Text] = [:]
            for (key, value): (SystemDiagnostic.MetadataKey, String?) in [
                (.appVersion, application.applicationMarketingVersion), (.appBuild, application.applicationVersion),
                (.osVersion, system.operatingSystemVersion), (.deviceModel, report.machineInfo?.modelName)
            ] {
                guard let value, !value.isEmpty else { continue }
                guard !value.utf8.contains(0) else { throw CrashReportDecodingError.invalidReport }
                metadata[key] = try .restore(value: value, isTruncated: false)
            }
            return try .restore(reportID: (CFUUIDCreateString(nil, uuid) as String).lowercased(), timestampMillis: timestamp,
                signal: signalName, code: signal.code, faultAddress: signal.address, threadNumber: threadNumber,
                frames: frames, images: mappedImages, metadata: metadata, imageCacheIncomplete: flags & 1 != 0)
        } catch { throw CrashReportDecodingError.invalidReport }
    }
}

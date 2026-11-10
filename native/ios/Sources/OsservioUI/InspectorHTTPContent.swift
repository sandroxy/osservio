import Foundation
import OsservioCore

/// Classifies observed response metadata only. URL extensions and request bodies are not response-type evidence.
enum InspectorHTTPContent {
    static let categories = ["json", "text", "image", "media", "other", "unknown"]
    static func category(_ headers: [HTTPHeader]) -> String {
        let types = headers.filter { $0.name.lowercased() == "content-type" }.map {
            $0.value.split(separator: ";", maxSplits: 1, omittingEmptySubsequences: false)[0]
                .trimmingCharacters(in: .whitespaces).lowercased()
        }
        guard let type = types.first, types.allSatisfy({ $0 == type }) else { return "unknown" }
        let parts = type.split(separator: "/", omittingEmptySubsequences: false)
        let token = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz0123456789!#$%&'*+-.^_`|~")
        guard parts.count == 2, parts.allSatisfy({ !$0.isEmpty && $0.unicodeScalars.allSatisfy(token.contains) }),
              !type.contains("*") else { return "unknown" }
        if type == "application/json" || type == "text/json" || parts[1].hasSuffix("+json") { return "json" }
        if parts[0] == "image" { return "image" }
        if parts[0] == "text" || type == "application/xml" || parts[1].hasSuffix("+xml") { return "text" }
        if parts[0] == "audio" || parts[0] == "video" { return "media" }
        return "other"
    }
}

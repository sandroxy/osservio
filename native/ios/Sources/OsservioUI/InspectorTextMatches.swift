import Foundation

/// Literal, bounded indexing over the one immutable string already opened by the user.
struct InspectorTextMatches {
    let ranges: [NSRange]
    let limited: Bool
    init(content: String, query: String, limit: Int = 200) {
        guard !query.isEmpty, limit > 0,
              let expression = try? NSRegularExpression(pattern: NSRegularExpression.escapedPattern(for: query), options: .caseInsensitive) else {
            ranges = []; limited = false; return
        }
        var found: [NSRange] = [], overflow = false
        expression.enumerateMatches(in: content, range: NSRange(content.startIndex..., in: content)) { match, _, stop in
            guard let match else { return }
            if found.count == limit { overflow = true; stop.pointee = true }
            else { found.append(match.range) }
        }
        ranges = found; limited = overflow
    }
}

import Foundation

enum InspectorValuePresentation {
    private static let dates: NSCache<NSString, DateFormatter> = { let cache = NSCache<NSString, DateFormatter>(); cache.countLimit = 12; return cache }()
    static func eventTime(_ millis: Int64, full: Bool = false) -> String {
        let zone = TimeZone.current
        let key = "\(zone.identifier):\(full)" as NSString
        let formatter: DateFormatter
        if let cached = dates.object(forKey: key) { formatter = cached }
        else {
            formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_US_POSIX"); formatter.timeZone = zone
            formatter.dateFormat = full ? "yyyy-MM-dd HH:mm:ss.SSS" : "HH:mm:ss.SSS"
            dates.setObject(formatter, forKey: key)
        }
        return formatter.string(from: Date(timeIntervalSince1970: Double(millis) / 1_000))
    }
    static func bytes(_ bytes: Int64) -> String {
        if bytes < 1_024 { return "\(bytes) B" }
        let units = ["KiB", "MiB", "GiB", "TiB"]
        var number = Double(bytes) / 1_024, index = 0
        while number >= 1_024 && index < units.count - 1 { number /= 1_024; index += 1 }
        return String(format: "%.1f %@", locale: Locale(identifier: "en_US_POSIX"), number, units[index])
    }
}

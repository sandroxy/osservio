import Foundation

/// Recent applied criteria belong to one viewer and one feed. Disk storage requires an explicit owner.
@MainActor
final class InspectorRecentFilters {
    private static let io = DispatchQueue(label: "com.sandrox.osservio.recent-filters", qos: .utility)
    private nonisolated static let limit = 10, entryBytes = 16_384
    private let name: String
    private let directory: URL?
    private var memory: [InspectorQuery] = []
    private var revision = 0
    var ownerChanged: (() -> Void)?
    var owner: UUID? {
        didSet {
            guard owner != oldValue else { return }
            memory.removeAll(); revision += 1
            ownerChanged?(); ownerChanged = nil
        }
    }

    init(_ name: String, directory: URL? = nil) {
        self.name = name
        self.directory = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)
            .first?.appendingPathComponent("Osservio/RecentFilters", isDirectory: true)
    }
    private var file: URL? {
        guard let owner else { return nil }
        return directory?.appendingPathComponent(owner.uuidString + "-" + name + ".json")
    }
    func load(_ completion: @escaping ([InspectorQuery]) -> Void) {
        guard let file else { completion(memory); return }
        let stamp = revision
        Self.io.async { [weak self] in
            let items = Self.read(file)
            DispatchQueue.main.async {
                guard let self, self.file == file else { return }
                if self.revision == stamp, let items { self.memory = items }
                completion(self.memory)
            }
        }
    }
    func update(_ query: InspectorQuery, removing: Bool = false) {
        guard let preset = Self.preset(query) else { return }
        revision += 1
        memory = Self.updated(memory, preset, removing)
        guard let file else { return }
        Self.io.async {
            guard let current = Self.read(file) else { return }
            let updated = Self.updated(current, preset, removing)
            guard current != updated, let data = try? JSONEncoder().encode(updated) else { return }
            var directory = file.deletingLastPathComponent()
            do {
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                var attributes = URLResourceValues(); attributes.isExcludedFromBackup = true
                try directory.setResourceValues(attributes)
                try data.write(to: file, options: .atomic)
            } catch { /* The current viewer retains its in-memory history. */ }
        }
    }
    private nonisolated static func updated(_ original: [InspectorQuery], _ item: InspectorQuery, _ removing: Bool) -> [InspectorQuery] {
        var result = original.filter { $0 != item }
        if !removing { result.insert(item, at: 0) }
        return Array(result.prefix(limit))
    }
    nonisolated static func preset(_ query: InspectorQuery) -> InspectorQuery? {
        var value = query; value.text = ""; value.values = value.values.filter { !$0.value.isEmpty }
        guard value.activeCount > 0, [0, 5, 30, 60, 1440].contains(value.recentMinutes),
              [value.excludedText, value.fieldPath, value.fieldValue].allSatisfy({ $0.utf8.count <= entryBytes }),
              value.values.values.reduce(0, { $0 + $1.count }) <= 128,
              value.values.values.allSatisfy({ $0.allSatisfy { $0.utf8.count <= 4096 } }),
              let data = try? JSONEncoder().encode(value), data.count <= entryBytes else { return nil }
        return value
    }
    private nonisolated static func read(_ file: URL) -> [InspectorQuery]? {
        do {
            let attrs = try FileManager.default.attributesOfItem(atPath: file.path)
            guard let size = attrs[.size] as? NSNumber, size.intValue <= limit * (entryBytes + 4) else { return nil }
            let data = try Data(contentsOf: file)
            let stored = try JSONDecoder().decode([InspectorQuery].self, from: data)
            guard stored.count <= limit else { return nil }
            return stored.compactMap(preset)
        } catch let error as NSError where error.domain == NSCocoaErrorDomain && error.code == NSFileReadNoSuchFileError {
            return []
        } catch { return nil }
    }
}

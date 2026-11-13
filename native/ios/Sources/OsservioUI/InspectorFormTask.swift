import Foundation

/// At most two admitted jobs, including cancelled jobs that have not exited yet.
/// No UI or history objects are retained by the parser.
final class InspectorFormTask: Operation, @unchecked Sendable {
    private let source: String
    private let completion: (Result<InspectorFormDocument, InspectorFormDocument.Failure>) -> Void
    private init(source: String, completion: @escaping (Result<InspectorFormDocument, InspectorFormDocument.Failure>) -> Void) {
        self.source = source; self.completion = completion
    }
    static func submit(_ source: String, completion: @escaping (Result<InspectorFormDocument, InspectorFormDocument.Failure>) -> Void) -> InspectorFormTask? {
        let task = InspectorFormTask(source: source, completion: completion)
        return InspectorReadQueue.submit(task) ? task : nil
    }
    override func main() {
        let span = InspectorPerformance.begin(.formParse); defer { span?.end() }
        guard !isCancelled else { return }
        let result: Result<InspectorFormDocument, InspectorFormDocument.Failure>
        do { result = .success(try InspectorFormDocument.parse(source, cancelled: { [weak self] in self?.isCancelled ?? true })) }
        catch let failure as InspectorFormDocument.Failure { result = .failure(failure) }
        catch { assertionFailure("Unexpected form parser error"); result = .failure(.invalid) }
        guard !isCancelled else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self, !self.isCancelled else { return }; self.completion(result)
        }
    }
}

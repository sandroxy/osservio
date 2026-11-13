import Foundation

/// At most two admitted jobs, including cancelled jobs that have not exited yet.
/// No UI or history objects are retained by the parser.
final class InspectorJSONTask: Operation, @unchecked Sendable {
    private let source: String
    private let completion: (Result<InspectorJSONDocument, InspectorJSONDocument.Failure>) -> Void
    private init(source: String, completion: @escaping (Result<InspectorJSONDocument, InspectorJSONDocument.Failure>) -> Void) {
        self.source = source; self.completion = completion
    }
    static func submit(_ source: String, completion: @escaping (Result<InspectorJSONDocument, InspectorJSONDocument.Failure>) -> Void) -> InspectorJSONTask? {
        let task = InspectorJSONTask(source: source, completion: completion)
        return InspectorReadQueue.submit(task) ? task : nil
    }
    override func main() {
        guard !isCancelled else { return }
        let result: Result<InspectorJSONDocument, InspectorJSONDocument.Failure>
        do { result = .success(try InspectorJSONDocument.parse(source, cancelled: { [weak self] in self?.isCancelled ?? true })) }
        catch let failure as InspectorJSONDocument.Failure { result = .failure(failure) }
        catch { assertionFailure("Unexpected JSON parser error"); result = .failure(.invalid) }
        guard !isCancelled else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self, !self.isCancelled else { return }; self.completion(result)
        }
    }
}

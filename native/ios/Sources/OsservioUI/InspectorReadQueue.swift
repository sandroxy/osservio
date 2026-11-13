import Foundation

/// JSON and form readers share one worker and at most two admitted operations.
enum InspectorReadQueue {
    private static let admission = NSLock()
    private static let worker: OperationQueue = {
        let queue = OperationQueue(); queue.name = "com.sandrox.osservio.content-reader"
        queue.maxConcurrentOperationCount = 1; queue.qualityOfService = .userInitiated
        return queue
    }()
    static func submit(_ task: Operation) -> Bool {
        admission.lock(); defer { admission.unlock() }
        guard worker.operationCount < 2 else { return false }
        worker.addOperation(task); return true
    }
}

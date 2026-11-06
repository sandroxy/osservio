import Foundation
import OsservioCore
@testable import OsservioRuntime
import XCTest

final class SystemDiagnosticQueryTests: XCTestCase {
    func testBusyAdmissionCloseAndCompletionReentry() throws {
        let entered = DispatchSemaphore(value: 0), release = DispatchSemaphore(value: 0)
        let finished = expectation(description: "admitted read completes after close")
        let query = SystemDiagnostics {
            entered.signal()
            guard release.wait(timeout: .now() + 5) == .success else { throw SystemDiagnosticError.unavailable }
            return try SystemDiagnosticBatch(source: .metricKit, observedAtMillis: 42, diagnostics: [])
        }
        query.read { result in
            XCTAssertEqual(try? result.get().observedAtMillis, 42)
            query.read { result in
                guard case .failure(.closed) = result else { return XCTFail("Close must reject reentry") }
            }
            finished.fulfill()
        }
        XCTAssertEqual(entered.wait(timeout: .now() + 5), .success)
        query.read { result in guard case .failure(.busy) = result else { return XCTFail("Second read must be bounded") } }
        query.close(); query.close(); release.signal()
        wait(for: [finished], timeout: 5)
    }
    func testUnsupportedIsNotAnEmptyHistoryAndSourceFailureAllowsRetry() {
        let unsupported = SystemDiagnostics(source: nil)
        unsupported.read { result in guard case .failure(.unsupported) = result else { return XCTFail("Unsupported source") } }
        unsupported.close()
        let twice = expectation(description: "failure can retry without deadlock")
        let query = SystemDiagnostics { throw SystemDiagnosticError.unavailable }
        query.read { result in
            guard case .failure(.unavailable) = result else { return XCTFail("First source failure") }
            query.read { result in
                guard case .failure(.unavailable) = result else { return XCTFail("Retry source failure") }
                twice.fulfill()
            }
        }
        wait(for: [twice], timeout: 5); query.close()
    }
}

#if canImport(UIKit)
import OsservioCore
@testable import OsservioUI
import UIKit
import XCTest

final class InspectorRecordDeletionTests: XCTestCase {
    @MainActor
    func testFailureRetryAndDuplicateSubmissionKeepTheDetailUntilSuccess() async throws {
        let root = UIViewController(), detail = UIViewController()
        let navigation = UINavigationController(rootViewController: root)
        navigation.pushViewController(detail, animated: false)
        var attempts: [InspectorRecordDeletion.Completion] = [], busy = false, message: String?
        let deletion = InspectorRecordDeletion(owner: detail, label: "incident_delete", confirmation: "incident_delete_confirmation",
            operation: { attempts.append($0) }, state: { busy = $0; message = $1 })
        deletion.deleteConfirmed(); deletion.deleteConfirmed()
        XCTAssertEqual(attempts.count, 1); XCTAssertTrue(busy); XCTAssertFalse(deletion.button.isEnabled)
        attempts[0](.failure(.storageFull)); try await settle()
        XCTAssertFalse(busy); XCTAssertTrue(deletion.button.isEnabled)
        XCTAssertEqual(message, PersistencePresentation.error(.storageFull)); XCTAssertTrue(navigation.topViewController === detail)
        deletion.deleteConfirmed(); XCTAssertEqual(attempts.count, 2)
        attempts[1](.success(())); try await settle()
        XCTAssertNil(message); XCTAssertTrue(navigation.topViewController === root)
    }

    @MainActor
    func testLeavingTheDetailRejectsLateDeletionDelivery() async throws {
        let root = UIViewController(), detail = UIViewController(), next = UIViewController()
        let navigation = UINavigationController(rootViewController: root)
        navigation.pushViewController(detail, animated: false)
        var completion: InspectorRecordDeletion.Completion?, states = 0
        let deletion = InspectorRecordDeletion(owner: detail, label: "archive_delete_run", confirmation: "archive_delete_confirmation",
            operation: { completion = $0 }, state: { _, _ in states += 1 })
        deletion.deleteConfirmed(); deletion.invalidate()
        navigation.pushViewController(next, animated: false)
        let count = states
        try XCTUnwrap(completion)(.failure(.unavailable)); try await settle()
        XCTAssertEqual(states, count); XCTAssertTrue(deletion.button.isEnabled); XCTAssertTrue(navigation.topViewController === next)
    }
    @MainActor private func settle() async throws { try await Task.sleep(nanoseconds: 60_000_000) }
}
#endif

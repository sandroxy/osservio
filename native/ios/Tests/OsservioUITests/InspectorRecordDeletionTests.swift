#if canImport(UIKit)
import OsservioCore
@testable import OsservioUI
import UIKit
import XCTest

final class InspectorRecordDeletionTests: XCTestCase {
    @MainActor
    func testOpeningConfirmationPreservesEvidenceAndUsesTheOwnersAppearance() throws {
        let detail = UIViewController(), window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        detail.overrideUserInterfaceStyle = .dark; window.overrideUserInterfaceStyle = .light
        window.rootViewController = detail; window.makeKeyAndVisible(); detail.loadViewIfNeeded(); window.layoutIfNeeded()
        defer { window.isHidden = true; window.rootViewController = nil }
        let deletion = InspectorRecordDeletion(owner: detail, label: "incident_delete", confirmation: "incident_delete_confirmation",
            operation: { _ in XCTFail("Opening confirmation must not delete") }, state: { _, _ in })
        let target = try XCTUnwrap(deletion.button.target as? NSObject), action = try XCTUnwrap(deletion.button.action)
        _ = target.perform(action)
        let alert = try XCTUnwrap(detail.presentedViewController as? UIAlertController)
        XCTAssertEqual(alert.preferredStyle, .alert)
        XCTAssertEqual(alert.actions.map(\.title), [RuntimePresentation.text("archive_cancel"), RuntimePresentation.text("incident_delete")])
        XCTAssertEqual(alert.actions.map(\.style), [.cancel, .destructive])
        XCTAssertEqual(alert.overrideUserInterfaceStyle, .dark)
        XCTAssertEqual(alert.view.accessibilityIdentifier, "Osservio.TransientNotice")
        let traits = UITraitCollection(userInterfaceStyle: .dark)
        XCTAssertEqual(alert.view.tintColor.resolvedColor(with: traits), InspectorTheme.accent.resolvedColor(with: traits))
        _ = target.perform(action)
        XCTAssertTrue(detail.presentedViewController === alert, "Repeated activation must not stack confirmations")
        XCTAssertTrue(deletion.button.isEnabled)
        // Modal dismissal is exercised in a running host; package tests cannot finish UIKit alert transitions.
    }

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

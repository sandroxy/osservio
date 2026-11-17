import Foundation
import OsservioCore

/// Called synchronously inside recorder admission. Underlying errors and arbitrary user-info fields are not inspected.
enum NetworkErrorObservation {
    static func read(_ error: Error) -> NetworkErrorInput {
        let native = error as NSError
        return NetworkErrorInput(domain: native.domain, code: String(native.code),
            type: String(reflecting: type(of: error)), message: native.localizedDescription)
    }
}

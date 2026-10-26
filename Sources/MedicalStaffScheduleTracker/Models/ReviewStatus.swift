import Foundation

/// Review and verification state for extracted cells and schedule entries.
public enum ReviewStatus: Codable, Hashable, Sendable {
    case confirmed
    case needsReview(reason: String)
    case unresolved(reason: String)

    public var isConfirmed: Bool {
        if case .confirmed = self { return true }
        return false
    }

    public var isUnresolved: Bool {
        if case .unresolved = self { return true }
        return false
    }

    public var reason: String? {
        switch self {
        case .confirmed:
            return nil
        case .needsReview(let reason), .unresolved(let reason):
            return reason
        }
    }
}

import Foundation

/// Review and verification state for extracted cells and schedule entries.
public enum ReviewStatus: Codable, Hashable, Sendable {
    case observed
    case confirmed
    case needsReview(reason: String)
    case unreadable(reason: String)
    case missing
    case confirmedBlank
    case inferredNeedsConfirmation(reason: String)
    case unresolved(reason: String)

    public var isConfirmed: Bool {
        if case .confirmed = self { return true }
        if case .observed = self { return true }
        return false
    }

    public var isUnresolved: Bool {
        switch self {
        case .unresolved, .unreadable, .missing:
            return true
        default:
            return false
        }
    }

    public var reason: String? {
        switch self {
        case .confirmed, .observed, .confirmedBlank:
            return nil
        case .needsReview(let reason), .unresolved(let reason), .unreadable(let reason), .inferredNeedsConfirmation(let reason):
            return reason
        case .missing:
            return "No reliable value recovered"
        }
    }
}

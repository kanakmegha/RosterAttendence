import Foundation

/// Type of change observed when reconciling a revised roster against an active schedule.
public enum ChangeType: String, Codable, Hashable, Sendable {
    /// New entry on a date that previously had no duty.
    case added
    /// Duty changed from one valid shift/floor to another.
    case changed
    /// Previous and proposed entries match.
    case unchanged
    /// Intentionally removed according to explicit, confirmed roster semantics.
    case removedConfirmed
    /// Missing or low-confidence data in revision; NOT assumed to be a removal.
    case unresolved
    /// Conflict between a revised roster entry and an existing manual emergency duty.
    case conflict
}

/// A per-date difference record comparing current schedule to a proposed revision.
public struct DateDiff: Codable, Hashable, Sendable {
    public let date: String // YYYY-MM-DD
    public let changeType: ChangeType
    public let previousEntry: ScheduleEntry?
    public let proposedEntry: ExtractedCell?
    public let explanation: String

    public init(
        date: String,
        changeType: ChangeType,
        previousEntry: ScheduleEntry?,
        proposedEntry: ExtractedCell?,
        explanation: String
    ) {
        self.date = date
        self.changeType = changeType
        self.previousEntry = previousEntry
        self.proposedEntry = proposedEntry
        self.explanation = explanation
    }
}

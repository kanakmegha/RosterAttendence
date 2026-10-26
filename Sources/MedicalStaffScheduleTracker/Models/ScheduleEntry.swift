import Foundation

/// Distinguishes entries derived from an uploaded roster from user-entered emergency duties.
public enum EntrySourceType: String, Codable, Hashable, Sendable {
    case roster
    case manualEmergency
}

/// An entry in the active or approved monthly schedule.
public struct ScheduleEntry: Codable, Hashable, Sendable, Identifiable {
    public let id: UUID
    public let date: String // ISO-8601: "YYYY-MM-DD"
    public let floor: String?
    public let department: String?
    public let shiftCode: String?
    public let displayLabel: String
    public let sourceType: EntrySourceType
    public let reviewStatus: ReviewStatus
    public let notes: String?
    public let updatedAt: Date

    public init(
        id: UUID = UUID(),
        date: String,
        floor: String? = nil,
        department: String? = nil,
        shiftCode: String? = nil,
        displayLabel: String,
        sourceType: EntrySourceType = .roster,
        reviewStatus: ReviewStatus = .confirmed,
        notes: String? = nil,
        updatedAt: Date = Date()
    ) {
        self.id = id
        self.date = date
        self.floor = floor
        self.department = department
        self.shiftCode = shiftCode
        self.displayLabel = displayLabel
        self.sourceType = sourceType
        self.reviewStatus = reviewStatus
        self.notes = notes
        self.updatedAt = updatedAt
    }
}

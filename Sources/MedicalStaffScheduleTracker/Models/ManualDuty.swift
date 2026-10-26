import Foundation

/// Form payload for a manual emergency duty added by the user.
public struct ManualDuty: Codable, Hashable, Sendable {
    public let date: String // YYYY-MM-DD
    public let department: String
    public let shift: String
    public let floor: String
    public let notes: String?

    public init(
        date: String,
        department: String,
        shift: String,
        floor: String,
        notes: String? = nil
    ) {
        self.date = date
        self.department = department
        self.shift = shift
        self.floor = floor
        self.notes = notes
    }

    /// Converts this manual duty into a ScheduleEntry.
    public func toScheduleEntry(id: UUID = UUID()) -> ScheduleEntry {
        ScheduleEntry(
            id: id,
            date: date,
            floor: floor,
            department: department,
            shiftCode: shift,
            displayLabel: shift,
            sourceType: .manualEmergency,
            reviewStatus: .confirmed,
            notes: notes
        )
    }
}

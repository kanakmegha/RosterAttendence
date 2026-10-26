import Foundation

/// Represents a specific calendar month (year and month 1...12) for a duty roster.
public struct RosterMonth: Codable, Hashable, Comparable, Sendable, CustomStringConvertible {
    public let year: Int
    public let month: Int

    public var description: String {
        String(format: "%04d-%02d", year, month)
    }

    public init(year: Int, month: Int) throws {
        guard (1900...2200).contains(year) else {
            throw ValidationError.invalidYear(year)
        }
        guard (1...12).contains(month) else {
            throw ValidationError.invalidMonth(month)
        }
        self.year = year
        self.month = month
    }

    /// Parses from string formatted as "YYYY-MM" (e.g. "2026-10").
    public init(string: String) throws {
        let trimmed = string.trimmingCharacters(in: .whitespacesAndNewlines)
        let parts = trimmed.split(separator: "-")
        guard parts.count == 2,
              let y = Int(parts[0]),
              let m = Int(parts[1]) else {
            throw ValidationError.invalidFormat(string)
        }
        try self.init(year: y, month: m)
    }

    /// Number of days in this month, accounting for leap years.
    public var numberOfDays: Int {
        switch month {
        case 1, 3, 5, 7, 8, 10, 12:
            return 31
        case 4, 6, 9, 11:
            return 30
        case 2:
            return Self.isLeapYear(year) ? 29 : 28
        default:
            return 30
        }
    }

    /// Determines whether a given year is a leap year according to the Gregorian calendar rules.
    public static func isLeapYear(_ y: Int) -> Bool {
        if y % 400 == 0 { return true }
        if y % 100 == 0 { return false }
        return y % 4 == 0
    }

    /// Checks if a day integer is within the valid range (1...numberOfDays).
    public func isValidDay(_ day: Int) -> Bool {
        return (1...numberOfDays).contains(day)
    }

    /// Formats an ISO-8601 date string ("YYYY-MM-DD") for a given day in this month.
    public func isoDate(forDay day: Int) throws -> String {
        guard isValidDay(day) else {
            throw ValidationError.dayOutOfRange(day: day, maxDay: numberOfDays, month: description)
        }
        return String(format: "%04d-%02d-%02d", year, month, day)
    }

    /// Returns day of week (1 = Sunday, 7 = Saturday or based on Gregorian calendar).
    public func weekday(forDay day: Int) throws -> Int {
        guard isValidDay(day) else {
            throw ValidationError.dayOutOfRange(day: day, maxDay: numberOfDays, month: description)
        }
        var dateComponents = DateComponents()
        dateComponents.year = year
        dateComponents.month = month
        dateComponents.day = day
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0) ?? .current
        guard let date = calendar.date(from: dateComponents) else {
            throw ValidationError.calendarError("Could not construct date for day \(day)")
        }
        return calendar.component(.weekday, from: date)
    }

    public static func < (lhs: RosterMonth, rhs: RosterMonth) -> Bool {
        if lhs.year != rhs.year {
            return lhs.year < rhs.year
        }
        return lhs.month < rhs.month
    }

    public enum ValidationError: Error, LocalizedError, Equatable, Sendable {
        case invalidYear(Int)
        case invalidMonth(Int)
        case invalidFormat(String)
        case dayOutOfRange(day: Int, maxDay: Int, month: String)
        case calendarError(String)

        public var errorDescription: String? {
            switch self {
            case .invalidYear(let y):
                return "Invalid year: \(y). Expected between 1900 and 2200."
            case .invalidMonth(let m):
                return "Invalid month: \(m). Expected between 1 and 12."
            case .invalidFormat(let str):
                return "Invalid month format: '\(str)'. Expected 'YYYY-MM'."
            case .dayOutOfRange(let day, let maxDay, let month):
                return "Day \(day) is out of range for \(month) (must be between 1 and \(maxDay))."
            case .calendarError(let msg):
                return "Calendar calculation error: \(msg)"
            }
        }
    }
}

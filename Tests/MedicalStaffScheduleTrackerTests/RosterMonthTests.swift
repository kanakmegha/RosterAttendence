import Testing
@testable import MedicalStaffScheduleTracker

@Suite("RosterMonth Tests")
struct RosterMonthTests {

    @Test("Valid month parsing and properties")
    func validMonthParsing() throws {
        let month = try RosterMonth(string: "2026-10")
        #expect(month.year == 2026)
        #expect(month.month == 10)
        #expect(month.description == "2026-10")
        #expect(month.numberOfDays == 31)
    }

    @Test("Invalid month strings throw validation errors", arguments: [
        "",
        "2026",
        "2026/10",
        "10-2026",
        "2026-13",
        "2026-00",
        "abcd-ef",
        "1899-01",
        "2201-01"
    ])
    func invalidMonthStrings(invalidString: String) {
        #expect(throws: RosterMonth.ValidationError.self) {
            _ = try RosterMonth(string: invalidString)
        }
    }

    @Test("Month lengths and leap year rules")
    func monthLengthsAndLeapYears() throws {
        // 31-day months
        for m in [1, 3, 5, 7, 8, 10, 12] {
            let rm = try RosterMonth(year: 2025, month: m)
            #expect(rm.numberOfDays == 31)
        }

        // 30-day months
        for m in [4, 6, 9, 11] {
            let rm = try RosterMonth(year: 2025, month: m)
            #expect(rm.numberOfDays == 30)
        }

        // February leap year variations
        let feb2024 = try RosterMonth(year: 2024, month: 2) // Leap (divisible by 4)
        #expect(feb2024.numberOfDays == 29)

        let feb2000 = try RosterMonth(year: 2000, month: 2) // Leap (divisible by 400)
        #expect(feb2000.numberOfDays == 29)

        let feb2023 = try RosterMonth(year: 2023, month: 2) // Non-leap
        #expect(feb2023.numberOfDays == 28)

        let feb1900 = try RosterMonth(year: 1900, month: 2) // Non-leap (divisible by 100 but not 400)
        #expect(feb1900.numberOfDays == 28)

        let feb2100 = try RosterMonth(year: 2100, month: 2) // Non-leap (divisible by 100 but not 400)
        #expect(feb2100.numberOfDays == 28)
    }

    @Test("ISO date generation and day bounds")
    func isoDateGeneration() throws {
        let rm = try RosterMonth(year: 2026, month: 9)
        #expect(try rm.isoDate(forDay: 1) == "2026-09-01")
        #expect(try rm.isoDate(forDay: 30) == "2026-09-30")

        // Day 31 is out of range for September (which has 30 days)
        #expect(throws: RosterMonth.ValidationError.self) {
            _ = try rm.isoDate(forDay: 31)
        }

        // Day 0 or negative is invalid
        #expect(throws: RosterMonth.ValidationError.self) {
            _ = try rm.isoDate(forDay: 0)
        }
        #expect(throws: RosterMonth.ValidationError.self) {
            _ = try rm.isoDate(forDay: -1)
        }
    }

    @Test("Weekday calculation")
    func weekdayCalculation() throws {
        // Oct 1, 2026 is a Thursday (1=Sun, 2=Mon, 3=Tue, 4=Wed, 5=Thu, 6=Fri, 7=Sat)
        let oct2026 = try RosterMonth(year: 2026, month: 10)
        let weekday = try oct2026.weekday(forDay: 1)
        #expect(weekday == 5)
    }

    @Test("Comparable ordering between months")
    func comparableOrdering() throws {
        let m1 = try RosterMonth(string: "2026-09")
        let m2 = try RosterMonth(string: "2026-10")
        let m3 = try RosterMonth(string: "2027-01")

        #expect(m1 < m2)
        #expect(m2 < m3)
        #expect(!(m2 < m1))
    }
}

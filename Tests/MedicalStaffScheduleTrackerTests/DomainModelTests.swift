import Testing
import Foundation
@testable import MedicalStaffScheduleTracker

@Suite("Domain Model Tests")
struct DomainModelTests {

    @Test("BoundingBox coordinate geometry and containment")
    func boundingBoxGeometry() {
        let box = BoundingBox(x: 0.1, y: 0.2, width: 0.3, height: 0.4)
        #expect(abs(box.minX - 0.1) < 0.0001)
        #expect(abs(box.maxX - 0.4) < 0.0001)
        #expect(abs(box.minY - 0.2) < 0.0001)
        #expect(abs(box.maxY - 0.6) < 0.0001)
        #expect(abs(box.centerX - 0.25) < 0.0001)
        #expect(abs(box.centerY - 0.40) < 0.0001)

        #expect(box.contains(px: 0.2, py: 0.3))
        #expect(!box.contains(px: 0.05, py: 0.3))
        #expect(!box.contains(px: 0.2, py: 0.7))
    }

    @Test("ManualDuty converts to ScheduleEntry with manualEmergency type")
    func manualDutyConversion() {
        let duty = ManualDuty(
            date: "2026-10-15",
            department: "ICU",
            shift: "Night",
            floor: "3F",
            notes: "Covering emergency shift"
        )
        let entry = duty.toScheduleEntry()
        #expect(entry.date == "2026-10-15")
        #expect(entry.department == "ICU")
        #expect(entry.floor == "3F")
        #expect(entry.displayLabel == "Night")
        #expect(entry.sourceType == .manualEmergency)
        #expect(entry.reviewStatus == .confirmed)
        #expect(entry.notes == "Covering emergency shift")
    }

    @Test("DraftSchedule is unapproved by default")
    func draftScheduleInvariants() throws {
        let month = try RosterMonth(string: "2026-10")
        let draft = DraftSchedule(
            staffId: "ABHL341",
            month: month,
            entries: [],
            unresolvedCells: []
        )

        // INVARIANT: Newly created draft must not be approved by default
        #expect(!draft.isApproved)
        #expect(draft.approvedAt == nil)
    }

    @Test("RevisionDiff distinguishes unresolved from removed")
    func revisionDiffChangeTypes() {
        let unchanged = DateDiff(
            date: "2026-10-01",
            changeType: .unchanged,
            previousEntry: nil,
            proposedEntry: nil,
            explanation: "No change detected"
        )
        #expect(unchanged.changeType == .unchanged)

        let unresolved = DateDiff(
            date: "2026-10-02",
            changeType: .unresolved,
            previousEntry: nil,
            proposedEntry: nil,
            explanation: "OCR unreadable - requires human confirmation"
        )
        #expect(unresolved.changeType == .unresolved)

        let conflict = DateDiff(
            date: "2026-10-03",
            changeType: .conflict,
            previousEntry: nil,
            proposedEntry: nil,
            explanation: "Overlaps with manual emergency duty"
        )
        #expect(conflict.changeType == .conflict)
    }

    @Test("ExtractedCell encodes and decodes safely")
    func codableSerialization() throws {
        let box = BoundingBox(x: 0.1, y: 0.2, width: 0.05, height: 0.02)
        let cell = ExtractedCell(
            dayNumber: 5,
            isoDate: "2026-10-05",
            rawText: "M",
            interpretedMeaning: .morning,
            displayLabel: "Morning",
            floor: "4th Floor",
            department: "Cardiology",
            confidence: 0.98,
            reviewStatus: .confirmed,
            boundingBox: box,
            pageIndex: 0,
            provenanceNote: "Page 1 Row 12"
        )

        let encoder = JSONEncoder()
        let data = try encoder.encode(cell)
        let decoder = JSONDecoder()
        let decoded = try decoder.decode(ExtractedCell.self, from: data)

        #expect(decoded.dayNumber == 5)
        #expect(decoded.isoDate == "2026-10-05")
        #expect(decoded.rawText == "M")
        #expect(decoded.floor == "4th Floor")
        #expect(decoded.department == "Cardiology")
        #expect(decoded.confidence == 0.98)
        #expect(decoded.boundingBox?.x == 0.1)
    }
}

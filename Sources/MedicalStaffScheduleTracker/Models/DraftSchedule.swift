import Foundation

/// Sanitized diagnostics summarizing an extraction run without exposing sensitive staff details.
public struct ExtractionDiagnostics: Codable, Hashable, Sendable {
    public let inputType: String
    public let pageCount: Int
    public let detectedOrientationDegrees: Int
    public let observationCount: Int
    public let dateHeaderCount: Int
    public let idMatchCount: Int
    public let unresolvedCellCount: Int
    public let warnings: [String]

    public init(
        inputType: String = "unknown",
        pageCount: Int = 1,
        detectedOrientationDegrees: Int = 0,
        observationCount: Int = 0,
        dateHeaderCount: Int = 0,
        idMatchCount: Int = 0,
        unresolvedCellCount: Int = 0,
        warnings: [String] = []
    ) {
        self.inputType = inputType
        self.pageCount = pageCount
        self.detectedOrientationDegrees = detectedOrientationDegrees
        self.observationCount = observationCount
        self.dateHeaderCount = dateHeaderCount
        self.idMatchCount = idMatchCount
        self.unresolvedCellCount = unresolvedCellCount
        self.warnings = warnings
    }
}

/// A draft schedule resulting from a roster extraction run.
///
/// Invariant: A DraftSchedule never becomes active on the user's calendar without explicit approval.
public struct DraftSchedule: Codable, Hashable, Sendable {
    public let staffId: String
    public let month: RosterMonth
    public let entries: [ExtractedCell]
    public let unresolvedCells: [ExtractedCell]
    public let diagnostics: ExtractionDiagnostics
    public var isApproved: Bool
    public var approvedAt: Date?

    public init(
        staffId: String,
        month: RosterMonth,
        entries: [ExtractedCell],
        unresolvedCells: [ExtractedCell] = [],
        diagnostics: ExtractionDiagnostics = ExtractionDiagnostics(),
        isApproved: Bool = false,
        approvedAt: Date? = nil
    ) {
        self.staffId = staffId
        self.month = month
        self.entries = entries
        self.unresolvedCells = unresolvedCells
        self.diagnostics = diagnostics
        self.isApproved = isApproved
        self.approvedAt = approvedAt
    }
}

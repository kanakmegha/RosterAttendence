import Foundation

/// Represents a single cell extracted from a roster during processing.
public struct ExtractedCell: Codable, Hashable, Sendable {
    public let dayNumber: Int?
    public let isoDate: String?
    public let rawText: String
    public let interpretedMeaning: ShiftMeaning?
    public let displayLabel: String?
    public let floor: String?
    public let department: String?
    public let confidence: Double?
    public let reviewStatus: ReviewStatus
    public let boundingBox: BoundingBox?
    public let pageIndex: Int
    public let provenanceNote: String?

    public init(
        dayNumber: Int?,
        isoDate: String? = nil,
        rawText: String,
        interpretedMeaning: ShiftMeaning? = nil,
        displayLabel: String? = nil,
        floor: String? = nil,
        department: String? = nil,
        confidence: Double? = nil,
        reviewStatus: ReviewStatus = .needsReview(reason: "Extracted draft requires review"),
        boundingBox: BoundingBox? = nil,
        pageIndex: Int = 0,
        provenanceNote: String? = nil
    ) {
        self.dayNumber = dayNumber
        self.isoDate = isoDate
        self.rawText = rawText
        self.interpretedMeaning = interpretedMeaning
        self.displayLabel = displayLabel
        self.floor = floor
        self.department = department
        self.confidence = confidence
        self.reviewStatus = reviewStatus
        self.boundingBox = boundingBox
        self.pageIndex = pageIndex
        self.provenanceNote = provenanceNote
    }
}

import Foundation

/// Unified text observation emitted by OCR or PDF text extraction.
public struct TextObservation: Codable, Hashable, Sendable {
    public let text: String
    public let boundingBox: BoundingBox
    public let confidence: Double?
    public let pageIndex: Int
    public let source: ObservationSource

    public init(
        text: String,
        boundingBox: BoundingBox,
        confidence: Double? = nil,
        pageIndex: Int = 0,
        source: ObservationSource = .visionOCR
    ) {
        self.text = text
        self.boundingBox = boundingBox
        self.confidence = confidence
        self.pageIndex = pageIndex
        self.source = source
    }
}

public enum ObservationSource: String, Codable, Hashable, Sendable {
    case pdfText
    case visionOCR
    case synthetic
}

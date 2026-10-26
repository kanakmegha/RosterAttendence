import Foundation

/// Normalized bounding box using top-left origin (0,0 is top-left, 1,1 is bottom-right).
public struct BoundingBox: Codable, Hashable, Sendable {
    public let x: Double       // minX (left)
    public let y: Double       // minY (top)
    public let width: Double
    public let height: Double

    public var minX: Double { x }
    public var maxX: Double { x + width }
    public var minY: Double { y }
    public var maxY: Double { y + height }

    public var centerX: Double { x + width / 2.0 }
    public var centerY: Double { y + height / 2.0 }

    public init(x: Double, y: Double, width: Double, height: Double) {
        self.x = x
        self.y = y
        self.width = max(0.0, width)
        self.height = max(0.0, height)
    }

    /// Horizontal overlap between this box and another.
    public func horizontalDistance(to other: BoundingBox) -> Double {
        abs(self.centerX - other.centerX)
    }

    /// Vertical distance between box centers.
    public func verticalDistance(to other: BoundingBox) -> Double {
        abs(self.centerY - other.centerY)
    }

    /// Checks if a given point (px, py) falls inside this bounding box.
    public func contains(px: Double, py: Double) -> Bool {
        return px >= minX && px <= maxX && py >= minY && py <= maxY
    }
}

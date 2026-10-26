import Foundation

/// Semantic meaning of a duty roster code.
public enum ShiftMeaning: Codable, Hashable, Sendable {
    case morning
    case evening
    case night
    case off(reason: String?)
    case custom(name: String)

    public var displayLabel: String {
        switch self {
        case .morning:
            return "Morning"
        case .evening:
            return "Evening"
        case .night:
            return "Night"
        case .off(let reason):
            if let reason = reason, !reason.isEmpty {
                return "Off (\(reason))"
            }
            return "Off"
        case .custom(let name):
            return name
        }
    }

    public var isOff: Bool {
        if case .off = self { return true }
        return false
    }
}

/// The result of attempting to resolve a cell's text using the shift legend.
public enum ShiftResolution: Hashable, Sendable {
    /// Successfully matched a known code in the legend.
    case matched(meaning: ShiftMeaning, displayLabel: String, canonicalCode: String)
    /// Cell was empty or contained only whitespace. Never assumed to be Off.
    case blank
    /// Cell contained text that does not exist in the configured legend. Must not be assumed to be Off.
    case unknown(rawCode: String)
    /// Ambiguous token (e.g. multiple distinct shift codes in one cell).
    case ambiguous(candidates: [String])
}

/// Configurable mapping from roster cell codes to their semantic shift meanings.
public struct ShiftLegend: Sendable {
    private var codeMap: [String: ShiftMeaning]
    private var canonicalCodes: [String: String]

    /// Default baseline legend: M = Morning, E = Evening, N = Night, OFF = Off.
    public static var standard: ShiftLegend {
        var legend = ShiftLegend()
        legend.register(code: "M", meaning: .morning)
        legend.register(code: "E", meaning: .evening)
        legend.register(code: "N", meaning: .night)
        legend.register(code: "OFF", meaning: .off(reason: nil))
        return legend
    }

    public init() {
        self.codeMap = [:]
        self.canonicalCodes = [:]
    }

    /// Normalizes raw code text: trims whitespace, normalizes unicode full-width characters, removes surrounding brackets/punctuation.
    public static func normalizeCode(_ raw: String) -> String {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        s = s.precomposedStringWithCanonicalMapping
        s = s.replacingOccurrences(of: "Ｏ", with: "O")
             .replacingOccurrences(of: "０", with: "0")
        // Strip leading/trailing surrounding punctuation, e.g. "(M)", "[OFF]", "/E/"
        let allowed = CharacterSet.alphanumerics
        var start = s.startIndex
        while start < s.endIndex && !s[start].unicodeScalars.allSatisfy({ allowed.contains($0) }) {
            start = s.index(after: start)
        }
        var end = s.endIndex
        while end > start {
            let prev = s.index(before: end)
            if s[prev].unicodeScalars.allSatisfy({ allowed.contains($0) }) {
                break
            }
            end = prev
        }
        return String(s[start..<end]).uppercased()
    }

    /// Registers or overrides a code in the legend.
    public mutating func register(code: String, meaning: ShiftMeaning) {
        let normalized = Self.normalizeCode(code)
        guard !normalized.isEmpty else { return }
        codeMap[normalized] = meaning
        canonicalCodes[normalized] = code.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Removes a code from the legend.
    public mutating func unregister(code: String) {
        let normalized = Self.normalizeCode(code)
        codeMap.removeValue(forKey: normalized)
        canonicalCodes.removeValue(forKey: normalized)
    }

    /// Resolves raw cell text against the legend.
    ///
    /// Important invariants:
    /// - Nil, empty, or whitespace-only strings resolve to `.blank`.
    /// - Unrecognized strings resolve to `.unknown`.
    /// - Under no circumstances does a blank or unknown cell become `.off`.
    public func resolve(rawCode: String?) -> ShiftResolution {
        guard let raw = rawCode else {
            return .blank
        }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            return .blank
        }

        let normalized = Self.normalizeCode(raw)
        if !normalized.isEmpty,
           let meaning = codeMap[normalized],
           let canonical = canonicalCodes[normalized] {
            return .matched(meaning: meaning, displayLabel: meaning.displayLabel, canonicalCode: canonical)
        }

        return .unknown(rawCode: trimmed)
    }

    /// Check if a normalized code is registered.
    public func contains(code: String) -> Bool {
        codeMap[Self.normalizeCode(code)] != nil
    }
}

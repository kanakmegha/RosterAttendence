import AppKit
import Vision
import Foundation
import ImageIO
import CoreImage

/*
 Medical roster photo parser using Apple's Vision OCR.
 Runs locally on macOS; no roster image is uploaded.

 Usage:
   swift roster_parser.swift --image "/path/roster.jpeg" --id "ABHL341" --month "2026-10"
   swift roster_parser.swift --image "/path/roster.jpeg" --id "ABHL341" --month "2026-10" --out "my_schedule.json"

 Assumptions:
 - The roster is a table with one column per day and the day numbers (1...31)
   appear in a header row above the staff rows.
 - The staff ID appears in the same row as that staff member's shift entries.
 - Shift letters are M, E, N; OFF/holiday codes can be configured below.
 - Floor may be printed in a column to the left of the day columns.

 IMPORTANT:
 This is a best-effort OCR/table-layout parser. Always review the JSON, especially
 rows marked "REVIEW". It deliberately does not convert blank/unknown cells to OFF.
*/

struct OCRItem {
    let text: String
    let x: Double       // normalized, left-to-right
    let y: Double       // normalized, top-to-bottom
    let width: Double
    let height: Double

    var centerX: Double { x + width / 2.0 }
    var centerY: Double { y + height / 2.0 }
}

struct DayHeader {
    let day: Int
    let x: Double
}

struct ScheduleEntry: Codable {
    let date: String
    let floor: String
    let shift: String
    let status: String
    let sourceText: String
}

struct ScheduleOutput: Codable {
    let staffID: String
    let month: String
    let image: String
    let notes: [String]
    let schedule: [ScheduleEntry]
    let unassignedOCR: [String]
}

func fail(_ message: String, code: Int32 = 1) -> Never {
    fputs("Error: \(message)\n", stderr)
    exit(code)
}

func argument(_ name: String, in args: [String]) -> String? {
    guard let i = args.firstIndex(of: name), i + 1 < args.count else { return nil }
    return args[i + 1]
}

func normalize(_ text: String) -> String {
    text.uppercased()
        .replacingOccurrences(of: "Ｏ", with: "O")
        .replacingOccurrences(of: "０", with: "0")
        .trimmingCharacters(in: .whitespacesAndNewlines)
        .replacingOccurrences(of: #"[|:;,.]"#, with: "", options: .regularExpression)
}

func isExactID(_ text: String, id: String) -> Bool {
    let normalizedText = normalize(text)
    let normalizedID = normalize(id)
    if normalizedText == normalizedID { return true }
    // OCR can include adjacent punctuation; accept only an exact alphanumeric token.
    let tokens = normalizedText.components(separatedBy: CharacterSet.alphanumerics.inverted)
        .filter { !$0.isEmpty }
    return tokens.contains(normalizedID)
}

func dayNumber(_ text: String) -> Int? {
    let t = normalize(text)
    // A header observation must be only a day number, not part of a staff ID or year.
    guard let value = Int(t), (1...31).contains(value) else { return nil }
    return value
}


func clusterRows(_ items: [OCRItem], tolerance: Double = 0.018) -> [[OCRItem]] {
    let sorted = items.sorted { $0.centerY < $1.centerY }
    var rows: [[OCRItem]] = []
    var rowYs: [Double] = []

    for item in sorted {
        if let lastY = rowYs.last,
           abs(item.centerY - lastY) <= tolerance {
            rows[rows.count - 1].append(item)

            let n = Double(rows[rows.count - 1].count)
            rowYs[rowYs.count - 1] =
                (lastY * (n - 1) + item.centerY) / n
        } else {
            rows.append([item])
            rowYs.append(item.centerY)
        }
    }

    return rows.map { $0.sorted { $0.centerX < $1.centerX } }
}

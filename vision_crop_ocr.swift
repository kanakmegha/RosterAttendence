import AppKit
import Vision
import Foundation

// VisionCropOCR: Local macOS Apple Vision text recognizer for cropped cells
// Usage: vision_crop_ocr <image_path_1> [image_path_2 ...]
// Output: JSON array of results with text, confidence, and bounding box

struct OCRResult: Codable {
    let path: String
    let text: String
    let confidence: Float
    let wordCount: Int
    let observations: [Observation]
}

struct Observation: Codable {
    let text: String
    let confidence: Float
    let x: Float
    let y: Float
    let width: Float
    let height: Float
}

guard CommandLine.arguments.count > 1 else {
    let errorDict = ["error": "No image paths provided"]
    let data = try! JSONSerialization.data(withJSONObject: errorDict)
    print(String(data: data, encoding: .utf8)!)
    exit(1)
}

let paths = Array(CommandLine.arguments.dropFirst())
var results: [OCRResult] = []

for path in paths {
    let url = URL(fileURLWithPath: path)
    guard let img = NSImage(contentsOf: url),
          let cgImg = img.cgImage(forProposedRect: nil, context: nil, hints: nil) else {
        results.append(OCRResult(path: path, text: "", confidence: 0.0, wordCount: 0, observations: []))
        continue
    }

    let request = VNRecognizeTextRequest()
    request.recognitionLevel = .accurate
    request.usesLanguageCorrection = false
    request.minimumTextHeight = 0.01

    let handler = VNImageRequestHandler(cgImage: cgImg, options: [:])
    try? handler.perform([request])

    let rawObservations = request.results ?? []
    var obsList: [Observation] = []
    var textTokens: [String] = []
    var maxConf: Float = 0.0

    for item in rawObservations {
        guard let top = item.topCandidates(1).first else { continue }
        textTokens.append(top.string)
        if top.confidence > maxConf {
            maxConf = top.confidence
        }
        let box = item.boundingBox
        obsList.append(Observation(
            text: top.string,
            confidence: top.confidence,
            x: Float(box.minX),
            y: Float(1.0 - box.maxY),
            width: Float(box.width),
            height: Float(box.height)
        ))
    }

    let fullText = textTokens.joined(separator: " ")
    results.append(OCRResult(
        path: path,
        text: fullText,
        confidence: maxConf,
        wordCount: textTokens.count,
        observations: obsList
    ))
}

let encoder = JSONEncoder()
encoder.outputFormatting = .prettyPrinted
if let data = try? encoder.encode(results), let jsonStr = String(data: data, encoding: .utf8) {
    print(jsonStr)
} else {
    print("[]")
}

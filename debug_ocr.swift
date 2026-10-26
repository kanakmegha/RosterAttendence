
import AppKit
import Vision
import Foundation

let path = CommandLine.arguments[1]
let url = URL(fileURLWithPath: path)

guard let image = NSImage(contentsOf: url),
      let cgImage = image.cgImage(
          forProposedRect: nil,
          context: nil,
          hints: nil
      ) else {
    fatalError("Could not open image: \(path)")
}

let request = VNRecognizeTextRequest()
request.recognitionLevel = .accurate
request.usesLanguageCorrection = false
request.minimumTextHeight = 0.002

let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
try handler.perform([request])

let observations = request.results ?? []

print("Total OCR observations: \(observations.count)")
print("----- OCR TEXT WITH POSITIONS -----")

for item in observations.sorted(by: {
    $0.boundingBox.midY > $1.boundingBox.midY
}) {
    guard let text = item.topCandidates(1).first?.string else {
        continue
    }

    let box = item.boundingBox
    let x = String(format: "%.3f", box.midX)
    let y = String(format: "%.3f", 1.0 - box.midY)

    print("x=\(x), y=\(y) | \(text)")
}

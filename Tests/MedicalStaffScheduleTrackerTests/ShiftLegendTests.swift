import Testing
@testable import MedicalStaffScheduleTracker

@Suite("ShiftLegend Tests")
struct ShiftLegendTests {

    @Test("Standard legend resolves baseline codes")
    func standardLegendResolutions() {
        let legend = ShiftLegend.standard

        let morning = legend.resolve(rawCode: "M")
        #expect(morning == .matched(meaning: .morning, displayLabel: "Morning", canonicalCode: "M"))

        let evening = legend.resolve(rawCode: "E")
        #expect(evening == .matched(meaning: .evening, displayLabel: "Evening", canonicalCode: "E"))

        let night = legend.resolve(rawCode: "N")
        #expect(night == .matched(meaning: .night, displayLabel: "Night", canonicalCode: "N"))

        let off = legend.resolve(rawCode: "OFF")
        #expect(off == .matched(meaning: .off(reason: nil), displayLabel: "Off", canonicalCode: "OFF"))
    }

    @Test("Case-insensitivity and punctuation normalization")
    func caseInsensitivityAndNormalization() {
        let legend = ShiftLegend.standard

        #expect(legend.resolve(rawCode: "m") == .matched(meaning: .morning, displayLabel: "Morning", canonicalCode: "M"))
        #expect(legend.resolve(rawCode: "e") == .matched(meaning: .evening, displayLabel: "Evening", canonicalCode: "E"))
        #expect(legend.resolve(rawCode: "n") == .matched(meaning: .night, displayLabel: "Night", canonicalCode: "N"))
        #expect(legend.resolve(rawCode: "off") == .matched(meaning: .off(reason: nil), displayLabel: "Off", canonicalCode: "OFF"))
        #expect(legend.resolve(rawCode: "Off") == .matched(meaning: .off(reason: nil), displayLabel: "Off", canonicalCode: "OFF"))

        // Surrounding punctuation & whitespace
        #expect(legend.resolve(rawCode: " (M) ") == .matched(meaning: .morning, displayLabel: "Morning", canonicalCode: "M"))
        #expect(legend.resolve(rawCode: "[OFF]") == .matched(meaning: .off(reason: nil), displayLabel: "Off", canonicalCode: "OFF"))
        #expect(legend.resolve(rawCode: "/E/") == .matched(meaning: .evening, displayLabel: "Evening", canonicalCode: "E"))
    }

    @Test("Blank and whitespace cells NEVER resolve to Off")
    func blankCellsNeverResolveToOff() {
        let legend = ShiftLegend.standard

        let nilResult = legend.resolve(rawCode: nil)
        #expect(nilResult == .blank)

        let emptyResult = legend.resolve(rawCode: "")
        #expect(emptyResult == .blank)

        let whitespaceResult = legend.resolve(rawCode: "   \t\n  ")
        #expect(whitespaceResult == .blank)

        if case .matched(let meaning, _, _) = nilResult {
            Issue.record("Blank resolved to a matched shift meaning: \(meaning)")
        }
    }

    @Test("Unrecognized codes NEVER assumed Off", arguments: [
        "G", "L", "AL", "CL", "DO", "XYZ", "123", "?", "D"
    ])
    func unrecognizedCodesNeverAssumedOff(unrecognizedCode: String) {
        let legend = ShiftLegend.standard
        let res = legend.resolve(rawCode: unrecognizedCode)
        #expect(res == .unknown(rawCode: unrecognizedCode))

        if case .matched = res {
            Issue.record("Unrecognized code '\(unrecognizedCode)' was matched unexpectedly")
        }
    }

    @Test("Configurable legend additions and unregistering")
    func configurableLegend() {
        var legend = ShiftLegend.standard

        // Register custom code
        legend.register(code: "CL", meaning: .custom(name: "Casual Leave"))
        let clResult = legend.resolve(rawCode: "CL")
        #expect(clResult == .matched(meaning: .custom(name: "Casual Leave"), displayLabel: "Casual Leave", canonicalCode: "CL"))

        // Register custom off code
        legend.register(code: "PH", meaning: .off(reason: "Public Holiday"))
        let phResult = legend.resolve(rawCode: "ph")
        #expect(phResult == .matched(meaning: .off(reason: "Public Holiday"), displayLabel: "Off (Public Holiday)", canonicalCode: "PH"))

        // Unregister OFF
        legend.unregister(code: "OFF")
        #expect(legend.resolve(rawCode: "OFF") == .unknown(rawCode: "OFF"))
    }
}

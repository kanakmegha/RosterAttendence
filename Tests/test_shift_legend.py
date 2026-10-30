"""
Tests for ShiftLegend ported from Swift ShiftLegendTests.swift
"""

import pytest
from shift_legend import ShiftLegend, ShiftMeaning, ShiftMeaningType, ShiftResolution


def test_standard_legend_resolves_baseline_codes():
    legend = ShiftLegend.standard()

    res_m = legend.resolve("M")
    assert res_m.status == "matched"
    assert res_m.meaning.meaning_type == ShiftMeaningType.MORNING
    assert res_m.display_label == "Morning"
    assert res_m.canonical_code == "M"

    res_e = legend.resolve("E")
    assert res_e.status == "matched"
    assert res_e.meaning.meaning_type == ShiftMeaningType.EVENING
    assert res_e.display_label == "Evening"

    res_n = legend.resolve("N")
    assert res_n.status == "matched"
    assert res_n.meaning.meaning_type == ShiftMeaningType.NIGHT
    assert res_n.display_label == "Night"

    res_off = legend.resolve("OFF")
    assert res_off.status == "matched"
    assert res_off.meaning.meaning_type == ShiftMeaningType.OFF
    assert res_off.meaning.is_off is True
    assert res_off.display_label == "Off"


def test_case_insensitivity_and_punctuation_normalization():
    legend = ShiftLegend.standard()

    for token in ["m", " M ", "(M)", "[M]", "/m/", "“M”"]:
        res = legend.resolve(token)
        assert res.status == "matched", f"Failed for token {token}"
        assert res.meaning.meaning_type == ShiftMeaningType.MORNING

    for token in ["off", " OFF ", "(OFF)", "[off]", "OFF."]:
        res = legend.resolve(token)
        assert res.status == "matched", f"Failed for token {token}"
        assert res.meaning.meaning_type == ShiftMeaningType.OFF


def test_blank_and_whitespace_cells_never_resolve_to_off():
    legend = ShiftLegend.standard()

    for blank_val in [None, "", "   ", "\t", "\n"]:
        res = legend.resolve(blank_val)
        assert res.status == "blank", f"Failed for {blank_val!r}"
        assert res.meaning is None


@pytest.mark.parametrize(
    "unrecognized_code",
    ["CL", "AL", "XYZ", "G", "L", "DO", "D", "123", "?"]
)
def test_unrecognized_codes_never_assumed_off(unrecognized_code):
    legend = ShiftLegend.standard()

    res = legend.resolve(unrecognized_code)
    assert res.status == "unknown"
    assert res.raw_code == unrecognized_code.strip()
    assert res.meaning is None


def test_configurable_legend_additions_and_unregistering():
    legend = ShiftLegend.standard()

    legend.register("CL", ShiftMeaning(ShiftMeaningType.OFF, reason="Casual Leave"))
    res_cl = legend.resolve("CL")
    assert res_cl.status == "matched"
    assert res_cl.meaning.meaning_type == ShiftMeaningType.OFF
    assert res_cl.display_label == "Off (Casual Leave)"

    # Unregister CL
    legend.unregister("CL")
    res_cl_after = legend.resolve("CL")
    assert res_cl_after.status == "unknown"

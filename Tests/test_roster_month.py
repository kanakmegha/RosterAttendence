"""
Tests for RosterMonth ported from Swift RosterMonthTests.swift
"""

import pytest
from roster_month import RosterMonth, MonthValidationError


def test_valid_month_parsing_and_properties():
    rm = RosterMonth.from_string("2026-09")
    assert rm.year == 2026
    assert rm.month == 9
    assert rm.string_value == "2026-09"
    assert rm.display_name == "September 2026"
    assert rm.number_of_days == 30
    assert rm.is_leap_year is False


def test_month_lengths_and_leap_year_rules():
    # Feb non-leap year (2025, 2026, 2027)
    feb_2025 = RosterMonth.from_string("2025-02")
    assert feb_2025.number_of_days == 28
    assert feb_2025.is_leap_year is False

    feb_2026 = RosterMonth.from_string("2026-02")
    assert feb_2026.number_of_days == 28
    assert feb_2026.is_leap_year is False

    # Feb leap year (2024, 2028)
    feb_2024 = RosterMonth.from_string("2024-02")
    assert feb_2024.number_of_days == 29
    assert feb_2024.is_leap_year is True

    feb_2028 = RosterMonth.from_string("2028-02")
    assert feb_2028.number_of_days == 29
    assert feb_2028.is_leap_year is True

    # 31-day months
    oct_2026 = RosterMonth.from_string("2026-10")
    assert oct_2026.number_of_days == 31


def test_iso_date_generation_and_day_bounds():
    rm = RosterMonth.from_string("2026-09")
    assert rm.date_for_day(1) == "2026-09-01"
    assert rm.date_for_day(30) == "2026-09-30"

    with pytest.raises(ValueError):
        rm.date_for_day(0)

    with pytest.raises(ValueError):
        rm.date_for_day(31)


def test_weekday_calculation():
    # September 1, 2026 is Tuesday
    rm = RosterMonth.from_string("2026-09")
    assert rm.weekday_for_day(1) == "Tue"
    assert rm.weekday_for_day(5) == "Sat"
    assert rm.weekday_for_day(6) == "Sun"
    assert rm.weekday_for_day(30) == "Wed"


def test_comparable_ordering_between_months():
    m1 = RosterMonth.from_string("2026-08")
    m2 = RosterMonth.from_string("2026-09")
    m3 = RosterMonth.from_string("2027-01")

    assert m1 < m2 < m3
    assert m2 > m1


@pytest.mark.parametrize(
    "invalid_str",
    [
        "",
        "2026",
        "10-2026",
        "2026-13",
        "2026-00",
        "abcd-ef",
        "1899-01",
        "2201-01",
        "2026/10",
    ]
)
def test_invalid_month_strings_throw_validation_errors(invalid_str):
    with pytest.raises(MonthValidationError):
        RosterMonth.from_string(invalid_str)

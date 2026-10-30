"""
MedicalStaffScheduleTracker - RosterMonth Domain Model and Calendar Logic
Ported from Sources/MedicalStaffScheduleTracker/Models/RosterMonth.swift
"""

import calendar
from dataclasses import dataclass
from datetime import date, datetime
import re
from typing import List, Tuple


class MonthValidationError(ValueError):
    """Raised when an invalid year-month string is provided."""
    pass


@dataclass(frozen=True, order=True)
class RosterMonth:
    """
    Represents a calendar month for a duty roster.
    Format: YYYY-MM (e.g. '2026-09')
    """
    year: int
    month: int

    def __post_init__(self):
        if not (1900 <= self.year <= 2200):
            raise MonthValidationError(f"Year {self.year} is outside valid range (1900-2200)")
        if not (1 <= self.month <= 12):
            raise MonthValidationError(f"Month {self.month} is outside valid range (1-12)")

    @classmethod
    def from_string(cls, month_str: str) -> "RosterMonth":
        """
        Parses a 'YYYY-MM' string.
        Throws MonthValidationError on malformed input.
        """
        if not month_str or not isinstance(month_str, str):
            raise MonthValidationError(f"Invalid month string: {month_str!r}")

        trimmed = month_str.strip()
        match = re.fullmatch(r"(\d{4})-(\d{2})", trimmed)
        if not match:
            raise MonthValidationError(
                f"Month string '{trimmed}' does not match expected format YYYY-MM"
            )

        year = int(match.group(1))
        month = int(match.group(2))
        return cls(year=year, month=month)

    @property
    def string_value(self) -> str:
        """Formatted as YYYY-MM."""
        return f"{self.year:04d}-{self.month:02d}"

    @property
    def display_name(self) -> str:
        """Full month name and year (e.g. 'September 2026')."""
        return f"{calendar.month_name[self.month]} {self.year}"

    @property
    def is_leap_year(self) -> bool:
        """Returns True if the year is a leap year."""
        return calendar.isleap(self.year)

    @property
    def number_of_days(self) -> int:
        """Number of calendar days in this month (28, 29, 30, or 31)."""
        _, num_days = calendar.monthrange(self.year, self.month)
        return num_days

    def date_for_day(self, day: int) -> str:
        """
        Returns the ISO-8601 date string 'YYYY-MM-DD' for a day number.
        Raises ValueError if day is out of range.
        """
        if not (1 <= day <= self.number_of_days):
            raise ValueError(f"Day {day} out of range for {self.string_value} (1..{self.number_of_days})")
        return f"{self.year:04d}-{self.month:02d}-{day:02d}"

    def weekday_for_day(self, day: int) -> str:
        """
        Returns the 3-letter English weekday ('Mon', 'Tue', 'Wed', etc.) for a day number.
        """
        if not (1 <= day <= self.number_of_days):
            raise ValueError(f"Day {day} out of range for {self.string_value} (1..{self.number_of_days})")
        dt = date(self.year, self.month, day)
        return dt.strftime("%a")

    @property
    def all_dates(self) -> List[str]:
        """All ISO date strings in this month in chronological order."""
        return [self.date_for_day(d) for d in range(1, self.number_of_days + 1)]

    @property
    def all_days_and_weekdays(self) -> List[Tuple[int, str, str]]:
        """List of (day_number, date_string, weekday) for every day in the month."""
        return [
            (d, self.date_for_day(d), self.weekday_for_day(d))
            for d in range(1, self.number_of_days + 1)
        ]

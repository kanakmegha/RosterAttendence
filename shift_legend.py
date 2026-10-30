"""
MedicalStaffScheduleTracker - ShiftLegend Domain Model and Business Rules
Ported from Sources/MedicalStaffScheduleTracker/Models/ShiftLegend.swift

Invariants:
- Nil, empty, or whitespace-only strings resolve to blank.
- Unrecognized strings resolve to unknown.
- Under NO circumstances does a blank or unknown cell resolve to Off.
"""

from dataclasses import dataclass
from enum import Enum
import re
import unicodedata
from typing import Optional, Dict, List, Any


class ShiftMeaningType(str, Enum):
    MORNING = "morning"
    EVENING = "evening"
    NIGHT = "night"
    OFF = "off"
    CUSTOM = "custom"


@dataclass(frozen=True)
class ShiftMeaning:
    meaning_type: ShiftMeaningType
    reason: Optional[str] = None
    custom_name: Optional[str] = None

    @property
    def display_label(self) -> str:
        if self.meaning_type == ShiftMeaningType.MORNING:
            return "Morning"
        if self.meaning_type == ShiftMeaningType.EVENING:
            return "Evening"
        if self.meaning_type == ShiftMeaningType.NIGHT:
            return "Night"
        if self.meaning_type == ShiftMeaningType.OFF:
            if self.reason and self.reason.strip():
                return f"Off ({self.reason.strip()})"
            return "Off"
        if self.meaning_type == ShiftMeaningType.CUSTOM:
            return self.custom_name or "Custom"
        return "Unknown"

    @property
    def is_off(self) -> bool:
        return self.meaning_type == ShiftMeaningType.OFF


@dataclass(frozen=True)
class ShiftResolution:
    status: str  # "matched", "blank", "unknown", "ambiguous"
    meaning: Optional[ShiftMeaning] = None
    display_label: Optional[str] = None
    canonical_code: Optional[str] = None
    raw_code: Optional[str] = None
    candidates: Optional[List[str]] = None

    @classmethod
    def matched(cls, meaning: ShiftMeaning, display_label: str, canonical_code: str):
        return cls(
            status="matched",
            meaning=meaning,
            display_label=display_label,
            canonical_code=canonical_code
        )

    @classmethod
    def blank(cls):
        return cls(status="blank")

    @classmethod
    def unknown(cls, raw_code: str):
        return cls(status="unknown", raw_code=raw_code)

    @classmethod
    def ambiguous(cls, candidates: List[str]):
        return cls(status="ambiguous", candidates=candidates)


class ShiftLegend:
    """
    Configurable mapping from roster cell codes to their semantic shift meanings.
    """

    def __init__(self):
        self._code_map: Dict[str, ShiftMeaning] = {}
        self._canonical_codes: Dict[str, str] = {}

    @classmethod
    def standard(cls) -> "ShiftLegend":
        """
        Default baseline legend:
        M = Morning
        E = Evening
        N = Night
        OFF = Off
        """
        legend = cls()
        legend.register("M", ShiftMeaning(ShiftMeaningType.MORNING))
        legend.register("E", ShiftMeaning(ShiftMeaningType.EVENING))
        legend.register("N", ShiftMeaning(ShiftMeaningType.NIGHT))
        legend.register("OFF", ShiftMeaning(ShiftMeaningType.OFF))
        return legend

    @staticmethod
    def normalize_code(raw: str) -> str:
        """
        Normalizes raw code text: trims whitespace, normalizes unicode full-width characters,
        removes surrounding brackets/punctuation.
        """
        if not raw:
            return ""
        s = raw.strip()
        s = unicodedata.normalize("NFKC", s)
        s = s.replace("Ｏ", "O").replace("０", "0")

        # Strip surrounding punctuation like (M), [OFF], /E/
        s = re.sub(r"^[^a-zA-Z0-9]+", "", s)
        s = re.sub(r"[^a-zA-Z0-9]+$", "", s)
        return s.upper()

    def register(self, code: str, meaning: ShiftMeaning):
        """Registers or overrides a code in the legend."""
        normalized = self.normalize_code(code)
        if not normalized:
            return
        self._code_map[normalized] = meaning
        self._canonical_codes[normalized] = code.strip()

    def unregister(self, code: str):
        """Removes a code from the legend."""
        normalized = self.normalize_code(code)
        self._code_map.pop(normalized, None)
        self._canonical_codes.pop(normalized, None)

    def resolve(self, raw_code: Optional[str]) -> ShiftResolution:
        """
        Resolves raw cell text against the legend.

        Important invariants:
        - Nil, empty, or whitespace-only strings resolve to blank.
        - Unrecognized strings resolve to unknown.
        - Under no circumstances does a blank or unknown cell become off.
        """
        if raw_code is None:
            return ShiftResolution.blank()

        trimmed = raw_code.strip()
        if not trimmed:
            return ShiftResolution.blank()

        normalized = self.normalize_code(raw_code)
        if normalized and normalized in self._code_map:
            meaning = self._code_map[normalized]
            canonical = self._canonical_codes[normalized]
            return ShiftResolution.matched(
                meaning=meaning,
                display_label=meaning.display_label,
                canonical_code=canonical
            )

        return ShiftResolution.unknown(raw_code=trimmed)

    def contains(self, code: str) -> bool:
        return self.normalize_code(code) in self._code_map

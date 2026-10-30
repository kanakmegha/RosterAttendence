"""
MedicalStaffScheduleTracker - Core Domain Models
Ported from Sources/MedicalStaffScheduleTracker/Models/
"""

from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Optional, List, Dict, Any
import uuid

from roster_month import RosterMonth


class EntrySourceType(str, Enum):
    ROSTER = "roster"
    OCR_OBSERVED = "ocr_observed"
    SECTION_INFERENCE = "section_inference"
    MANUAL_EMERGENCY = "manual_emergency"
    MANUAL_SWAP = "manual_swap"


class ReviewStatus(str, Enum):
    OBSERVED = "observed"
    CONFIRMED = "confirmed"
    NEEDS_REVIEW = "needs_review"
    UNREADABLE = "unreadable"
    MISSING = "missing"
    CONFIRMED_BLANK = "confirmed_blank"
    INFERRED_NEEDS_CONFIRMATION = "inferred_needs_confirmation"
    UNRESOLVED = "unresolved"

    @property
    def is_confirmed(self) -> bool:
        return self in (ReviewStatus.CONFIRMED, ReviewStatus.OBSERVED)

    @property
    def is_unresolved(self) -> bool:
        return self in (ReviewStatus.UNRESOLVED, ReviewStatus.UNREADABLE, ReviewStatus.MISSING)


@dataclass
class ScheduleEntry:
    """An entry in the active or approved monthly schedule."""
    date: str  # ISO-8601: "YYYY-MM-DD"
    display_label: str
    id: str = field(default_factory=lambda: str(uuid.uuid4()))
    floor: Optional[str] = None
    department: Optional[str] = None
    shift_code: Optional[str] = None
    source_type: EntrySourceType = EntrySourceType.ROSTER
    review_status: ReviewStatus = ReviewStatus.CONFIRMED
    notes: Optional[str] = None
    updated_at: str = field(default_factory=lambda: datetime.utcnow().isoformat())

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "date": self.date,
            "floor": self.floor,
            "department": self.department,
            "shift_code": self.shift_code,
            "display_label": self.display_label,
            "source_type": self.source_type.value,
            "review_status": self.review_status.value,
            "notes": self.notes,
            "updated_at": self.updated_at,
        }


@dataclass
class ManualDuty:
    """Payload for a manual emergency duty added by the user."""
    date: str  # YYYY-MM-DD
    department: str
    shift: str
    floor: str
    notes: Optional[str] = None

    def to_schedule_entry(self, entry_id: Optional[str] = None) -> ScheduleEntry:
        return ScheduleEntry(
            id=entry_id or str(uuid.uuid4()),
            date=self.date,
            floor=self.floor,
            department=self.department,
            shift_code=self.shift,
            display_label=self.shift,
            source_type=EntrySourceType.MANUAL_EMERGENCY,
            review_status=ReviewStatus.CONFIRMED,
            notes=self.notes,
        )

    def to_dict(self) -> Dict[str, Any]:
        return {
            "date": self.date,
            "department": self.department,
            "shift": self.shift,
            "floor": self.floor,
            "notes": self.notes,
        }


@dataclass
class ExtractedCell:
    """Cell extracted from an OCR table row."""
    day_number: int
    date: str
    weekday: str
    raw_value: Optional[str] = None
    normalized_shift: Optional[str] = None
    review_status: ReviewStatus = ReviewStatus.MISSING
    review_reason: Optional[str] = None
    confidence: float = 0.0
    source_type: EntrySourceType = EntrySourceType.OCR_OBSERVED
    supporting_dates: List[int] = field(default_factory=list)
    supporting_raw_tokens: List[str] = field(default_factory=list)
    inference_rule: Optional[str] = None
    section_start_day: Optional[int] = None
    section_end_day: Optional[int] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "day_number": self.day_number,
            "date": self.date,
            "weekday": self.weekday,
            "raw_value": self.raw_value,
            "normalized_shift": self.normalized_shift,
            "review_status": self.review_status.value if isinstance(self.review_status, ReviewStatus) else self.review_status,
            "review_reason": self.review_reason,
            "confidence": self.confidence,
            "source_type": self.source_type.value if isinstance(self.source_type, EntrySourceType) else self.source_type,
            "supporting_dates": self.supporting_dates,
            "supporting_raw_tokens": self.supporting_raw_tokens,
            "inference_rule": self.inference_rule,
            "section_start_day": self.section_start_day,
            "section_end_day": self.section_end_day,
        }


@dataclass
class ExtractionDiagnostics:
    input_type: str = "image"
    page_count: int = 1
    detected_orientation_degrees: int = 0
    observation_count: int = 0
    date_header_count: int = 0
    id_match_count: int = 0
    unresolved_cell_count: int = 0
    warnings: List[str] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "input_type": self.input_type,
            "page_count": self.page_count,
            "detected_orientation_degrees": self.detected_orientation_degrees,
            "observation_count": self.observation_count,
            "date_header_count": self.date_header_count,
            "id_match_count": self.id_match_count,
            "unresolved_cell_count": self.unresolved_cell_count,
            "warnings": self.warnings,
        }


@dataclass
class DraftSchedule:
    """
    A draft schedule resulting from a roster extraction run.
    Invariant: A DraftSchedule NEVER becomes active without explicit human approval.
    """
    staff_id: str
    month: RosterMonth
    entries: List[ExtractedCell]
    unresolved_cells: List[ExtractedCell] = field(default_factory=list)
    diagnostics: ExtractionDiagnostics = field(default_factory=ExtractionDiagnostics)
    is_approved: bool = False
    approved_at: Optional[str] = None
    schedule_id: str = field(default_factory=lambda: str(uuid.uuid4()))

    def approve(self) -> None:
        """Explicit human approval transition."""
        self.is_approved = True
        self.approved_at = datetime.utcnow().isoformat()

    def to_dict(self) -> Dict[str, Any]:
        return {
            "schedule_id": self.schedule_id,
            "staff_id": self.staff_id,
            "month": self.month.string_value,
            "month_display": self.month.display_name,
            "is_approved": self.is_approved,
            "approved_at": self.approved_at,
            "entries": [e.to_dict() for e in self.entries],
            "unresolved_cells": [u.to_dict() for u in self.unresolved_cells],
            "diagnostics": self.diagnostics.to_dict(),
        }

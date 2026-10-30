"""
MedicalStaffScheduleTracker - Revision Reconciliation and Diff Engine
Ported from Sources/MedicalStaffScheduleTracker/Models/RevisionDiff.swift
"""

from dataclasses import dataclass
from enum import Enum
from typing import Optional, List, Dict, Any

from domain_models import ScheduleEntry, ExtractedCell, EntrySourceType, ReviewStatus


class ChangeType(str, Enum):
    """Type of change observed when reconciling a revised roster against an active schedule."""
    ADDED = "added"
    CHANGED = "changed"
    UNCHANGED = "unchanged"
    REMOVED_CONFIRMED = "removed_confirmed"
    UNRESOLVED = "unresolved"
    CONFLICT = "conflict"


@dataclass
class DateDiff:
    """A per-date difference record comparing current schedule to a proposed revision."""
    date: str  # YYYY-MM-DD
    change_type: ChangeType
    explanation: str
    previous_entry: Optional[Dict[str, Any]] = None
    proposed_entry: Optional[Dict[str, Any]] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "date": self.date,
            "change_type": self.change_type.value,
            "explanation": self.explanation,
            "previous_entry": self.previous_entry,
            "proposed_entry": self.proposed_entry,
        }


def compute_schedule_revision_diff(
    current_entries: List[ScheduleEntry],
    revised_cells: List[ExtractedCell],
) -> List[DateDiff]:
    """
    Compares the current approved schedule against a revised extracted roster.

    Invariants:
    - Missing or low-confidence data in revision is NEVER assumed to be a removal -> ChangeType.UNRESOLVED.
    - Conflict between revised roster and manual emergency duty is flagged -> ChangeType.CONFLICT.
    - Explicit OFF or confirmed removal -> ChangeType.REMOVED_CONFIRMED.
    """
    curr_map: Dict[str, ScheduleEntry] = {e.date: e for e in current_entries}
    rev_map: Dict[str, ExtractedCell] = {c.date: c for c in revised_cells}

    all_dates = sorted(set(list(curr_map.keys()) + list(rev_map.keys())))
    diffs: List[DateDiff] = []

    for d in all_dates:
        curr = curr_map.get(d)
        rev = rev_map.get(d)

        curr_dict = curr.to_dict() if curr else None
        rev_dict = rev.to_dict() if rev else None

        # Case 1: No previous entry, but revised has a proposed shift
        if not curr:
            if rev and rev.normalized_shift:
                diffs.append(
                    DateDiff(
                        date=d,
                        change_type=ChangeType.ADDED,
                        explanation=f"New duty '{rev.normalized_shift}' added on previously open date.",
                        previous_entry=None,
                        proposed_entry=rev_dict,
                    )
                )
            continue

        # Case 2: Previous duty was a manual emergency duty
        if curr.source_type == EntrySourceType.MANUAL_EMERGENCY:
            if rev and rev.normalized_shift and rev.normalized_shift != curr.shift_code:
                diffs.append(
                    DateDiff(
                        date=d,
                        change_type=ChangeType.CONFLICT,
                        explanation=(
                            f"Manual emergency duty '{curr.shift_code}' conflicts with revised "
                            f"roster proposed shift '{rev.normalized_shift}'."
                        ),
                        previous_entry=curr_dict,
                        proposed_entry=rev_dict,
                    )
                )
                continue

        # Case 3: Previous duty exists, but revised cell is missing or unresolved
        if not rev or rev.review_status in (
            ReviewStatus.UNRESOLVED,
            ReviewStatus.UNREADABLE,
            ReviewStatus.MISSING,
            ReviewStatus.NEEDS_REVIEW,
        ):
            diffs.append(
                DateDiff(
                    date=d,
                    change_type=ChangeType.UNRESOLVED,
                    explanation=(
                        f"Current schedule has duty '{curr.shift_code}', but revised roster cell "
                        f"is unresolved ({rev.review_status if rev else 'missing'}). Cannot assume removal."
                    ),
                    previous_entry=curr_dict,
                    proposed_entry=rev_dict,
                )
            )
            continue

        # Case 4: Revised cell is explicitly OFF
        if rev.normalized_shift == "OFF" or rev.raw_value == "OFF":
            diffs.append(
                DateDiff(
                    date=d,
                    change_type=ChangeType.REMOVED_CONFIRMED,
                    explanation=f"Previous duty '{curr.shift_code}' confirmed removed (revised roster states OFF).",
                    previous_entry=curr_dict,
                    proposed_entry=rev_dict,
                )
            )
            continue

        # Case 5: Matching duty
        if rev.normalized_shift == curr.shift_code:
            diffs.append(
                DateDiff(
                    date=d,
                    change_type=ChangeType.UNCHANGED,
                    explanation="Previous duty matches revised duty.",
                    previous_entry=curr_dict,
                    proposed_entry=rev_dict,
                )
            )
            continue

        # Case 6: Changed duty
        diffs.append(
            DateDiff(
                date=d,
                change_type=ChangeType.CHANGED,
                explanation=f"Shift changed from '{curr.shift_code}' to '{rev.normalized_shift}'.",
                previous_entry=curr_dict,
                proposed_entry=rev_dict,
            )
        )

    return diffs

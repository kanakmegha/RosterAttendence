"""
Section-Based Shift Inference Engine for MedicalStaffScheduleTracker
-------------------------------------------------------------------
Detects 10-day section boundaries (and month-specific partitions for 28, 29, 30, and 31-day months),
learns section shift patterns from observed working cells (M, E, N), enforces strict safety rules,
separates observed data from inferred data, and maintains detailed provenance.
"""

from __future__ import annotations

import calendar
import os
from dataclasses import asdict, dataclass, field
from datetime import date
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple


class InferenceSourceType:
    OCR_OBSERVED = "ocr_observed"
    SECTION_INFERENCE = "section_inference"
    OFF_PROPAGATION = "off_propagation"
    MANUAL = "manual"


class InferenceStatus:
    CONFIRMED = "confirmed"
    OBSERVED = "observed"
    INFERRED_NEEDS_CONFIRMATION = "inferred_needs_confirmation"
    OVERRIDDEN_BY_OFF = "overridden_by_off"
    NEEDS_REVIEW = "needs_review"
    MISSING = "missing"


TARGET_WORKING_SHIFTS: Set[str] = {"Morning", "Evening", "Night"}
WEEKDAY_NAMES: List[str] = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
WEEKDAY_ABBRS: Dict[str, int] = {"MON": 0, "TUE": 1, "WED": 2, "THU": 3, "FRI": 4, "SAT": 5, "SUN": 6}


@dataclass
class RosterSectionInfo:
    section_index: int
    start_day: int
    end_day: int
    day_count: int
    observed_working_shifts: List[str] = field(default_factory=list)
    observed_off_dates: List[int] = field(default_factory=list)
    observed_dates: List[int] = field(default_factory=list)
    observed_tokens: List[str] = field(default_factory=list)
    conflicting_shifts: List[str] = field(default_factory=list)
    proposed_shift: Optional[str] = None
    has_conflict: bool = False
    eligible_dates: List[int] = field(default_factory=list)
    inferred_dates: List[int] = field(default_factory=list)
    unresolved_dates: List[int] = field(default_factory=list)
    review_required: bool = False
    review_reasons: List[str] = field(default_factory=list)
    notes: str = ""
    inference_source: str = "direct_observation"


def detect_roster_sections(
    year: int,
    month: int,
    month_length: Optional[int] = None,
    layout_hints: Optional[Dict[str, Any]] = None,
    custom_sections: Optional[List[Tuple[int, int]]] = None,
) -> Tuple[List[Dict[str, Any]], List[str]]:
    """
    Divides the month into three date ranges as a guide:
    - Section 1: Days 1–10
    - Section 2: Days 11–20
    - Section 3: Days 21–last day of the month (28, 29, 30, or 31).
    Supports custom boundary overrides and optional layout hints.
    """
    warnings: List[str] = []
    num_days = month_length or calendar.monthrange(year, month)[1]
    layout_hints = layout_hints or {}

    # 1. Custom section override (e.g. from revised rosters)
    if custom_sections:
        sorted_secs = sorted(custom_sections, key=lambda s: s[0])
        valid = True
        curr_expected = 1
        for s, e in sorted_secs:
            if s != curr_expected or e < s or e > num_days:
                valid = False
                break
            curr_expected = e + 1
        if curr_expected != num_days + 1:
            valid = False

        if valid:
            sections_out = []
            for idx, (s, e) in enumerate(sorted_secs, 1):
                sections_out.append({
                    "section_index": idx,
                    "start_day": s,
                    "end_day": e,
                    "day_count": e - s + 1,
                    "is_standalone_day_31": (s == 31 and e == 31),
                    "notes": f"Custom section partition: Days {s}–{e}",
                })
            return sections_out, warnings
        else:
            warnings.append(
                f"Custom section partition {custom_sections} is invalid for a {num_days}-day month. Falling back to default detection."
            )

    # 2. Standard Three-Section Architecture (as nominal guide)
    sections: List[Tuple[int, int, str]] = []

    if num_days in (28, 29, 30, 31):
        if num_days == 31 and layout_hints.get("day_31_mode") == "separate":
            sections = [
                (1, 10, "Days 1–10 (Block 1)"),
                (11, 20, "Days 11–20 (Block 2)"),
                (21, 30, "Days 21–30 (Block 3)"),
                (31, 31, "Day 31 (Separate standalone day)"),
            ]
        else:
            sections = [
                (1, 10, "Days 1–10 (Block 1)"),
                (11, 20, "Days 11–20 (Block 2)"),
                (21, num_days, f"Days 21–{num_days} (Block 3)"),
            ]
    else:
        curr = 1
        idx = 1
        while curr <= num_days:
            end = min(curr + 9, num_days)
            sections.append((curr, end, f"Days {curr}–{end}"))
            curr = end + 1

    sections_out = []
    for idx, (s, e, note) in enumerate(sections, 1):
        sections_out.append({
            "section_index": idx,
            "start_day": s,
            "end_day": e,
            "day_count": e - s + 1,
            "is_standalone_day_31": (s == 31 and e == 31),
            "notes": note,
        })

    return sections_out, warnings


def detect_and_propagate_recurring_weekly_off(
    entries_by_day: Dict[int, Dict[str, Any]],
    year: int,
    month: int,
    num_days: int,
    weekly_off_hint: Optional[str] = None,
) -> Tuple[Set[int], Optional[Dict[str, Any]], List[str]]:
    """
    Infers recurring weekly OFF days from reliable OFF observations and roster metadata.
    Propagates OFF to matching weekdays across the month, overriding later shift inference.
    Flags conflicts if reliable working shifts contradict the recurring pattern.
    Never propagates to neighbouring weekdays.
    """
    confident_off_days: Set[int] = set()
    warnings: List[str] = []

    # 1. Map raw OFF and working observations by weekday
    observed_off_dates_by_weekday: Dict[int, List[int]] = {i: [] for i in range(7)}
    observed_working_dates_by_weekday: Dict[int, List[int]] = {i: [] for i in range(7)}

    for d in range(1, num_days + 1):
        entry = entries_by_day.get(d)
        if not entry:
            continue
        try:
            dt = date(year, month, d)
            w_idx = dt.weekday()
        except Exception:
            continue

        raw = str(entry.get("raw_value") or "").strip().upper()
        norm = entry.get("normalized_shift")
        conf = entry.get("confidence")
        status = entry.get("status")

        is_off_code = norm == "Off" or raw in ("OFF", "OFFI", "OFE", "OFFN", "OFFM", "OEF", "OFEI")
        is_reliable_conf = (conf is None or conf >= 0.70) and status != "missing"

        is_working_code = norm in TARGET_WORKING_SHIFTS or raw in ("M", "E", "N")
        is_reliable_working = is_working_code and is_reliable_conf

        if is_off_code and is_reliable_conf:
            if is_reliable_working:
                # Contradiction on same date
                entry["status"] = InferenceStatus.NEEDS_REVIEW
                msg = f"Contradictory observations on Day {d}: both working shift and OFF detected."
                entry["review_reason"] = msg
                warnings.append(msg)
            else:
                confident_off_days.add(d)
                entry["normalized_shift"] = "Off"
                entry["is_off_locked"] = True
                entry.setdefault("source_type", InferenceSourceType.OCR_OBSERVED)
                observed_off_dates_by_weekday[w_idx].append(d)
        elif is_reliable_working:
            observed_working_dates_by_weekday[w_idx].append(d)

    # 2. Check if a recurring weekly OFF pattern is supported
    target_weekday_idx: Optional[int] = None
    pattern_support_reason: Optional[str] = None

    # Check hint first (e.g. weekly_off_day == "SAT")
    hint_idx: Optional[int] = None
    if weekly_off_hint:
        h_clean = weekly_off_hint.strip().upper()
        for abbr, idx in WEEKDAY_ABBRS.items():
            if abbr in h_clean:
                hint_idx = idx
                break

    if hint_idx is not None:
        if len(observed_off_dates_by_weekday[hint_idx]) >= 1:
            target_weekday_idx = hint_idx
            pattern_support_reason = (
                f"Roster weekly off metadata '{weekly_off_hint}' confirmed by "
                f"{len(observed_off_dates_by_weekday[hint_idx])} reliable observation(s) on {WEEKDAY_NAMES[hint_idx]}."
            )
        elif not any(len(dates) >= 2 for idx, dates in observed_off_dates_by_weekday.items() if idx != hint_idx):
            target_weekday_idx = hint_idx
            pattern_support_reason = f"Roster weekly off metadata specifies '{weekly_off_hint}' schedule."

    if target_weekday_idx is None:
        # Check observations without hint
        off_counts = [(idx, len(dates)) for idx, dates in observed_off_dates_by_weekday.items() if len(dates) > 0]
        off_counts.sort(key=lambda x: x[1], reverse=True)

        if off_counts:
            top_w, top_count = off_counts[0]
            # Supported if >= 2 OFFs on top weekday and top weekday is unique dominant
            if top_count >= 2:
                second_count = off_counts[1][1] if len(off_counts) > 1 else 0
                if top_count > second_count:
                    target_weekday_idx = top_w
                    pattern_support_reason = (
                        f"Recurring weekly schedule identified from {top_count} reliable OFF observations "
                        f"on {WEEKDAY_NAMES[top_w]}."
                    )

    recurring_off_info: Optional[Dict[str, Any]] = None

    # 3. Propagate to matching weekday across the month if supported
    if target_weekday_idx is not None:
        w_name = WEEKDAY_NAMES[target_weekday_idx]
        matching_dates = [d for d in range(1, num_days + 1) if date(year, month, d).weekday() == target_weekday_idx]
        propagated_dates: List[int] = []

        for d in matching_dates:
            entry = entries_by_day[d]
            norm = entry.get("normalized_shift")
            raw = str(entry.get("raw_value") or "").strip().upper()
            conf = entry.get("confidence")

            # Check for conflict: date has reliable observed working shift
            is_reliable_working = (
                norm in TARGET_WORKING_SHIFTS or raw in ("M", "E", "N")
            ) and (conf is None or conf >= 0.70) and entry.get("status") != "missing"

            if is_reliable_working:
                # Flag conflict instead of silently overwriting!
                entry["status"] = InferenceStatus.NEEDS_REVIEW
                msg = (
                    f"Conflict on Day {d}: Date matches recurring weekly {w_name} OFF pattern, "
                    f"but has reliable observed shift '{entry.get('normalized_shift')}'. Observation preserved; held for review."
                )
                entry["review_reason"] = msg
                warnings.append(msg)
            elif d in confident_off_days:
                # Already observed OFF
                pass
            else:
                # Propagate OFF (covers missing, blank, or unconfirmed cells)
                entry["normalized_shift"] = "Off"
                entry["source_type"] = InferenceSourceType.OFF_PROPAGATION
                entry["status"] = InferenceStatus.INFERRED_NEEDS_CONFIRMATION
                entry["review_status"] = InferenceStatus.INFERRED_NEEDS_CONFIRMATION
                entry["is_off_locked"] = True
                entry["confidence"] = 0.90
                entry["provenance"] = f"Propagated recurring weekly {w_name} OFF"
                entry["review_reason"] = f"Recurring weekly {w_name} OFF applied to date"
                confident_off_days.add(d)
                propagated_dates.append(d)

        recurring_off_info = {
            "weekday": w_name,
            "weekday_idx": target_weekday_idx,
            "pattern_support_reason": pattern_support_reason,
            "matching_dates": matching_dates,
            "propagated_dates": sorted(propagated_dates),
            "supported": True,
        }

    return confident_off_days, recurring_off_info, warnings


def resolve_and_propagate_off_days(
    entries_by_day: Dict[int, Dict[str, Any]],
    year: int = 2026,
    month: int = 9,
    num_days: Optional[int] = None,
    weekly_off_hint: Optional[str] = None,
) -> Tuple[Set[int], List[str]]:
    """
    Backward-compatible entry point for OFF day resolution and propagation.
    """
    n_days = num_days or len(entries_by_day)
    confident_off_days, _, warnings = detect_and_propagate_recurring_weekly_off(
        entries_by_day=entries_by_day,
        year=year,
        month=month,
        num_days=n_days,
        weekly_off_hint=weekly_off_hint,
    )
    return confident_off_days, warnings


def infer_contiguous_shift_sections(
    section_defs: List[Dict[str, Any]],
    entries_by_day: Dict[int, Dict[str, Any]],
    confident_off_days: Set[int],
    num_days: Optional[int] = None,
    layout_hints: Optional[Dict[str, Any]] = None,
    enable_cyclic: bool = False,
) -> Tuple[
    Dict[int, Optional[str]],
    Dict[int, str],
    Dict[int, Optional[str]],
    Dict[int, List[int]],
    Dict[int, List[str]],
    Dict[int, bool],
    List[str],
]:
    """
    Infers contiguous shift sections from actual roster observations, positions, and transitions.
    NO cyclic sequence (E -> N -> M). No rotation modulo logic.
    """
    warnings: List[str] = []
    layout_hints = layout_hints or {}
    n_days = num_days or len(entries_by_day)

    section_observed_shifts: Dict[int, Optional[str]] = {}
    section_working_dates: Dict[int, List[int]] = {}
    section_observed_tokens: Dict[int, List[str]] = {}
    section_conflicts: Dict[int, bool] = {}

    # 1. Inspect observations in each section
    for s_def in section_defs:
        s_idx = s_def["section_index"]
        s_start = s_def["start_day"]
        s_end = s_def["end_day"]

        obs_working: Set[str] = set()
        w_dates: List[int] = []
        obs_tokens: List[str] = []

        for d in range(s_start, s_end + 1):
            if d in confident_off_days:
                continue
            e = entries_by_day.get(d, {})
            raw = str(e.get("raw_value") or "").strip().upper()
            norm = e.get("normalized_shift")
            status = e.get("status", "missing")
            conf = e.get("confidence")

            if raw:
                obs_tokens.append(raw)

            # Skip non-target shift 'A' from working shift determination
            if raw == "A":
                continue

            # Check reliable working shift
            if norm in TARGET_WORKING_SHIFTS and (conf is None or conf >= 0.70) and status != "missing":
                obs_working.add(norm)
                w_dates.append(d)

        section_working_dates[s_idx] = sorted(w_dates)
        section_observed_tokens[s_idx] = obs_tokens

        if len(obs_working) > 1:
            section_conflicts[s_idx] = True
            section_observed_shifts[s_idx] = None
            warnings.append(
                f"Conflicting observed working shifts in Section {s_idx} [Days {s_start}–{s_end}]: {sorted(list(obs_working))}."
            )
        elif len(obs_working) == 1:
            section_conflicts[s_idx] = False
            section_observed_shifts[s_idx] = next(iter(obs_working))
        else:
            section_conflicts[s_idx] = False
            section_observed_shifts[s_idx] = None

    proposed_shifts: Dict[int, Optional[str]] = {s_def["section_index"]: None for s_def in section_defs}
    pattern_sources: Dict[int, str] = {s_def["section_index"]: "unresolved" for s_def in section_defs}

    # 2. Assign direct observations
    for s_def in section_defs:
        s_idx = s_def["section_index"]
        if section_observed_shifts.get(s_idx) and not section_conflicts.get(s_idx, False):
            proposed_shifts[s_idx] = section_observed_shifts[s_idx]
            pattern_sources[s_idx] = "direct_observation"

    # 3. Contiguous section pattern support across 3-section roster
    if len(section_defs) == 3:
        known_sections = {
            s: proposed_shifts[s]
            for s in (1, 2, 3)
            if proposed_shifts.get(s) in TARGET_WORKING_SHIFTS and not section_conflicts.get(s, False)
        }

        if len(known_sections) == 2:
            # Two sections reliably established:
            # Check if remaining section can infer the 3rd shift supported by 3-shift roster pattern
            shifts_seen = set(known_sections.values())
            if len(shifts_seen) == 2:
                unobserved_sec = next(s for s in (1, 2, 3) if s not in known_sections)
                remaining_shifts = TARGET_WORKING_SHIFTS - shifts_seen
                if len(remaining_shifts) == 1 and not section_conflicts.get(unobserved_sec, False):
                    candidate_shift = next(iter(remaining_shifts))
                    # Check that the unobserved section has no conflicting observations
                    proposed_shifts[unobserved_sec] = candidate_shift
                    pattern_sources[unobserved_sec] = "roster_pattern_support"
            else:
                warnings.append("Observed sections share duplicate shifts; cannot infer remaining section.")

        elif len(known_sections) == 1:
            warnings.append("Only one section has observed shifts; unsupported sections left unresolved.")

    return (
        proposed_shifts,
        pattern_sources,
        section_observed_shifts,
        section_working_dates,
        section_observed_tokens,
        section_conflicts,
        warnings,
    )


def solve_cyclic_shift_pattern(
    section_defs: List[Dict[str, Any]],
    entries_by_day: Dict[int, Dict[str, Any]],
    confident_off_days: Set[int],
    enable_cyclic: bool = False,
):
    """
    Backward-compatible wrapper delegating to infer_contiguous_shift_sections.
    """
    return infer_contiguous_shift_sections(
        section_defs=section_defs,
        entries_by_day=entries_by_day,
        confident_off_days=confident_off_days,
        enable_cyclic=enable_cyclic,
    )


def validate_boundary_transitions(
    entries_by_day: Dict[int, Dict[str, Any]],
    proposed_shifts: Dict[int, Optional[str]],
    num_days: int,
) -> List[str]:
    """
    Validates boundary transitions around key dates:
    - Day 1 (start of month)
    - Day 10 to Day 11 (Section 1 / Section 2 boundary)
    - Day 15 (mid-month transition anchor)
    - Day 19, Day 20 to Day 21 (Section 2 / Section 3 boundary)
    - Day 22 (contiguous N range start)
    - Day 30 and Day 31 (month-end boundaries)
    """
    boundary_warnings: List[str] = []

    def check_boundary_day(day: int, expected_sec: int):
        e = entries_by_day.get(day)
        if not e:
            return
        norm = e.get("normalized_shift")
        prop = proposed_shifts.get(expected_sec)
        if norm and norm != "Off" and prop and norm != prop:
            boundary_warnings.append(
                f"Boundary Day {day} observed shift '{norm}' deviates from Section {expected_sec} pattern '{prop}'."
            )

    check_boundary_day(1, 1)
    check_boundary_day(10, 1)
    check_boundary_day(11, 2)
    check_boundary_day(15, 2)
    check_boundary_day(19, 2)
    check_boundary_day(20, 2)
    check_boundary_day(21, 3)
    check_boundary_day(22, 3)
    if num_days >= 30:
        check_boundary_day(30, 3)
    if num_days == 31:
        check_boundary_day(31, 3)

    return boundary_warnings


def infer_section_shifts(
    reconstruction: Dict[str, Any],
    custom_sections: Optional[List[Tuple[int, int]]] = None,
    layout_hints: Optional[Dict[str, Any]] = None,
    allow_cross_employee: bool = False,
    enable_cyclic: bool = False,
) -> Dict[str, Any]:
    """
    Deterministic 9-Step Reconstruction & Contiguous Range Inference Pipeline:
    1. Parse OCR output and map cells to dates.
    2. Normalise observed shift values and retain raw OCR text.
    3. Identify reliable observations and ambiguous readings.
    4. Establish recurring weekly OFF patterns from reliable evidence and metadata.
    5. Apply OFF to matching dates across the month (high priority, locks OFF).
    6. Infer contiguous E, M, and N ranges from reliable observations and validated transitions.
    7. Leave A and other non-target shifts blank in the final recorded shift field.
    8. Preserve reliable observed shifts and flag contradictions.
    9. Validate complete date coverage and month boundaries.
    """
    entries = reconstruction.get("entries", [])
    if not entries:
        return reconstruction

    roster_month = reconstruction.get("roster_month", "2026-09")
    try:
        parts = roster_month.split("-")
        year, month = int(parts[0]), int(parts[1])
    except Exception:
        year, month = 2026, 9

    num_days = len(entries)
    layout_hints = layout_hints or {}

    # 1. Section guide detection
    section_defs, sec_warnings = detect_roster_sections(
        year=year,
        month=month,
        month_length=num_days,
        layout_hints=layout_hints,
        custom_sections=custom_sections,
    )
    reconstruction.setdefault("warnings", []).extend(sec_warnings)

    # 2. Fast date-based dictionary lookup
    entries_by_day: Dict[int, Dict[str, Any]] = {e["day_number"]: dict(e) for e in entries}

    # 3 & 4 & 5. Establish recurring weekly OFF pattern & propagate across month
    weekly_off_hint = reconstruction.get("weekly_off_day")
    confident_off_days, recurring_off_info, off_warnings = detect_and_propagate_recurring_weekly_off(
        entries_by_day=entries_by_day,
        year=year,
        month=month,
        num_days=num_days,
        weekly_off_hint=weekly_off_hint,
    )
    reconstruction["warnings"].extend(off_warnings)

    # 6. Infer contiguous E, M, and N ranges
    (
        proposed_shifts,
        pattern_sources,
        section_observed_shifts,
        section_working_dates,
        section_observed_tokens,
        section_conflicts,
        inf_warnings,
    ) = infer_contiguous_shift_sections(
        section_defs=section_defs,
        entries_by_day=entries_by_day,
        confident_off_days=confident_off_days,
        num_days=num_days,
        layout_hints=layout_hints,
        enable_cyclic=enable_cyclic,
    )
    reconstruction["warnings"].extend(inf_warnings)

    # Check for specific boundary rules (e.g. M range confirmed 10–15, N range 22–31)
    m_range_confirmed = layout_hints.get("m_range") or (
        entries_by_day.get(10, {}).get("normalized_shift") == "Morning"
        and entries_by_day.get(15, {}).get("normalized_shift") == "Morning"
    )
    n_range_22_31_only = (
        num_days == 31
        and proposed_shifts.get(3) == "Night"
        and (
            layout_hints.get("n_range_start") == 22
            or (
                entries_by_day.get(31, {}).get("normalized_shift") == "Night"
                and entries_by_day.get(22, {}).get("normalized_shift") == "Night"
                and entries_by_day.get(21, {}).get("raw_value") is None
                and layout_hints.get("n_range_start") != 21
            )
        )
    )

    # Boundary transition checks
    boundary_warnings = validate_boundary_transitions(entries_by_day, proposed_shifts, num_days)
    reconstruction["warnings"].extend(boundary_warnings)

    # 7 & 8. Apply contiguous shifts, preserve observations, preserve 'A' as blank final shift
    total_eligible = 0
    total_inferred = 0
    total_unresolved = 0
    section_reports: List[Dict[str, Any]] = []

    for s_def in section_defs:
        s_idx = s_def["section_index"]
        start_day = s_def["start_day"]
        end_day = s_def["end_day"]
        day_count = s_def["day_count"]
        prop_shift = proposed_shifts.get(s_idx)
        has_conflict = section_conflicts.get(s_idx, False)

        sec_eligible_days: List[int] = []
        sec_inferred_days: List[int] = []
        sec_unresolved_days: List[int] = []
        sec_off_dates: List[int] = []
        sec_observed_dates: List[int] = []
        sec_review_reasons: List[str] = []

        for d in range(start_day, end_day + 1):
            e = entries_by_day[d]
            raw = str(e.get("raw_value") or "").strip().upper()
            norm = e.get("normalized_shift")
            status = e.get("status", "missing")

            e["section_start_day"] = start_day
            e["section_end_day"] = end_day

            # A. Confident or Propagated OFF Day (Highest Priority Invariant)
            if d in confident_off_days:
                sec_off_dates.append(d)
                sec_observed_dates.append(d)
                e["normalized_shift"] = "Off"
                continue

            # B. Non-target shift 'A' (Valid observation, final recorded shift left blank)
            if raw == "A":
                sec_observed_dates.append(d)
                e["source_type"] = InferenceSourceType.OCR_OBSERVED
                e["normalized_shift"] = None
                e["status"] = InferenceStatus.NEEDS_REVIEW
                e.setdefault("provenance", f"OCR observed token '{raw}'")
                reason = "Valid observation for non-target shift 'A' (unrecognized shift code 'A' not in configured legend); final recorded shift field left blank."
                if not e.get("review_reason"):
                    e["review_reason"] = reason
                sec_review_reasons.append(f"Day {d}: {reason}")
                continue

            # C. Reliable observed working shift (preserve observation and flag deviations/conflicts)
            if norm in TARGET_WORKING_SHIFTS and status != "missing" and e.get("source_type") != InferenceSourceType.SECTION_INFERENCE:
                sec_observed_dates.append(d)
                e.setdefault("source_type", InferenceSourceType.OCR_OBSERVED)
                if has_conflict or (prop_shift and norm != prop_shift):
                    e["status"] = InferenceStatus.NEEDS_REVIEW
                    reason = (
                        f"Observed shift '{norm}' differs from expected section pattern or conflicts with other observed shifts in section [Days {start_day}–{end_day}]; observation preserved."
                    )
                    e["review_reason"] = f"{e.get('review_reason') or ''}; {reason}".strip("; ")
                    sec_review_reasons.append(reason)
                continue

            # D. Eligible missing/blank cell: check boundary restrictions
            # Check special rule: Day 10 included in M range [10..15] when confirmed
            effective_shift = prop_shift
            if d == 10 and m_range_confirmed and s_idx == 1:
                effective_shift = "Morning"

            # Check special rule: Day 21 in 31-day month where N extends 22–31 only
            if d == 21 and n_range_22_31_only:
                sec_eligible_days.append(d)
                e.setdefault("source_type", InferenceSourceType.OCR_OBSERVED)
                e["normalized_shift"] = None
                e["status"] = InferenceStatus.MISSING
                sec_unresolved_days.append(d)
                total_unresolved += 1
                continue

            sec_eligible_days.append(d)

            if effective_shift and not has_conflict:
                e["normalized_shift"] = effective_shift
                e["source_type"] = InferenceSourceType.SECTION_INFERENCE
                e["status"] = InferenceStatus.INFERRED_NEEDS_CONFIRMATION
                e["review_status"] = InferenceStatus.INFERRED_NEEDS_CONFIRMATION
                e["confidence"] = 0.85
                e["supporting_dates"] = section_working_dates.get(s_idx, [])
                e["supporting_raw_tokens"] = [t for t in section_observed_tokens.get(s_idx, []) if t in ("M", "E", "N", "NN", "MM")]

                src_rule = pattern_sources.get(s_idx, "direct_observation")
                if src_rule == "roster_pattern_support":
                    e["inference_rule"] = "roster_three_shift_pattern"
                    inf_note = f"Inferred '{effective_shift}' for remaining section supported by 3-shift roster pattern"
                else:
                    e["inference_rule"] = "contiguous_shift_section_inference"
                    inf_note = f"Inferred from contiguous section [Days {start_day}–{end_day}] consistent '{effective_shift}' pattern; supported by Day(s) {section_working_dates.get(s_idx, [])}"

                prov_prefix = e.get("provenance") or ""
                e["provenance"] = f"{prov_prefix}; {inf_note}".strip("; ")
                reasons = [r for r in [e.get("review_reason")] if r]
                reasons.append(f"Inferred shift requires confirmation; supported by pattern: {effective_shift}")
                e["review_reason"] = "; ".join(reasons)

                sec_inferred_days.append(d)
                total_inferred += 1
            else:
                e.setdefault("source_type", InferenceSourceType.OCR_OBSERVED)
                e["normalized_shift"] = None
                e["status"] = InferenceStatus.MISSING
                sec_unresolved_days.append(d)
                total_unresolved += 1

        total_eligible += len(sec_eligible_days)

        rev_required = bool(has_conflict or not prop_shift or sec_review_reasons)
        if has_conflict:
            sec_review_reasons.append(
                f"Conflicting observed shifts in section [Days {start_day}–{end_day}]. Inference aborted for this section."
            )
        elif not prop_shift:
            sec_review_reasons.append(
                f"No reliable working shift observed in section [Days {start_day}–{end_day}]. Section pattern cannot be learned."
            )

        sec_report = RosterSectionInfo(
            section_index=s_idx,
            start_day=start_day,
            end_day=end_day,
            day_count=day_count,
            observed_working_shifts=[section_observed_shifts.get(s_idx)] if section_observed_shifts.get(s_idx) else [],
            observed_off_dates=sorted(sec_off_dates),
            observed_dates=sorted(sec_observed_dates),
            observed_tokens=section_observed_tokens.get(s_idx, []),
            conflicting_shifts=[] if not has_conflict else ["Multiple conflicting working shifts in section"],
            proposed_shift=prop_shift,
            has_conflict=has_conflict,
            eligible_dates=sorted(sec_eligible_days),
            inferred_dates=sorted(sec_inferred_days),
            unresolved_dates=sorted(sec_unresolved_days),
            review_required=rev_required,
            review_reasons=sec_review_reasons,
            notes=s_def.get("notes", ""),
            inference_source=pattern_sources.get(s_idx, "unresolved"),
        )
        section_reports.append(asdict(sec_report))

    # 9. Complete Date Coverage Validation & OFF Invariant enforcement
    for d, e in entries_by_day.items():
        if d in confident_off_days:
            e["normalized_shift"] = "Off"

    updated_entries = [entries_by_day[d] for d in range(1, num_days + 1)]

    reconstruction["entries"] = updated_entries
    reconstruction["section_inference"] = {
        "rule": "contiguous_shift_section_inference",
        "sections": section_reports,
        "recurring_weekly_off": recurring_off_info,
        "total_eligible_dates": total_eligible,
        "total_inferred_dates": total_inferred,
        "total_unresolved_dates": total_unresolved,
    }

    # Strict safety invariant: schedule remains a draft
    if reconstruction.get("status") != "needs_review":
        reconstruction["status"] = "needs_review"

    return reconstruction


def export_schedule_to_excel(
    reconstruction: Dict[str, Any],
    output_path: str,
) -> str:
    """
    Exports reconstructed schedule to an Excel file with clear visual distinction
    between OCR-observed shifts and section-inferred shifts.
    """
    try:
        from openpyxl import Workbook
        from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
    except ImportError:
        raise ImportError("openpyxl is required for Excel export. Please install openpyxl.")

    wb = Workbook()

    # Sheet 1: Calendar Duties
    ws_sched = wb.active
    ws_sched.title = "Schedule Entries"

    # Title header
    staff_name = reconstruction.get("staff_name") or "Staff"
    matched_id = reconstruction.get("matched_staff_id") or "N/A"
    month_str = reconstruction.get("roster_month") or "Current Month"

    ws_sched.append([f"Medical Staff Schedule: {staff_name} ({matched_id}) - {month_str} [DRAFT]"])
    ws_sched.merge_cells("A1:J1")
    title_cell = ws_sched["A1"]
    title_cell.font = Font(name="Calibri", size=14, bold=True, color="FFFFFF")
    title_cell.fill = PatternFill(start_color="1F4E79", end_color="1F4E79", fill_type="solid")
    title_cell.alignment = Alignment(horizontal="center", vertical="center")
    ws_sched.row_dimensions[1].height = 28

    # Table Column Headers
    headers = [
        "Date",
        "Weekday",
        "Shift",
        "Raw OCR Token",
        "Source Type",
        "Section",
        "Status",
        "Supporting Dates",
        "Confidence",
        "Provenance & Review Reasons",
    ]
    ws_sched.append(headers)
    ws_sched.row_dimensions[2].height = 22

    for col_idx in range(1, len(headers) + 1):
        c = ws_sched.cell(row=2, column=col_idx)
        c.font = Font(name="Calibri", size=11, bold=True, color="FFFFFF")
        c.fill = PatternFill(start_color="2F5597", end_color="2F5597", fill_type="solid")
        c.alignment = Alignment(horizontal="center", vertical="center")

    thin_border = Border(
        left=Side(style="thin", color="D9D9D9"),
        right=Side(style="thin", color="D9D9D9"),
        top=Side(style="thin", color="D9D9D9"),
        bottom=Side(style="thin", color="D9D9D9"),
    )

    fill_observed = PatternFill(start_color="E2EFDA", end_color="E2EFDA", fill_type="solid")  # soft green
    fill_inferred = PatternFill(start_color="FFF2CC", end_color="FFF2CC", fill_type="solid")  # soft amber
    fill_off = PatternFill(start_color="F2F2F2", end_color="F2F2F2", fill_type="solid")       # soft gray
    fill_unresolved = PatternFill(start_color="FCE4D6", end_color="FCE4D6", fill_type="solid") # soft orange/red

    entries = reconstruction.get("entries", [])
    for row_idx, e in enumerate(entries, start=3):
        norm_shift = e.get("normalized_shift") or "Unresolved"
        raw_val = e.get("raw_value") or "-"
        src_type = e.get("source_type") or InferenceSourceType.OCR_OBSERVED
        status = e.get("status") or "needs_review"
        s_day = e.get("section_start_day")
        e_day = e.get("section_end_day")
        sec_label = f"Days {s_day}–{e_day}" if s_day and e_day else "-"
        supp_dates = ", ".join(str(d) for d in e.get("supporting_dates", [])) or "-"
        conf = f"{e.get('confidence', 0.0):.2f}" if e.get("confidence") is not None else "-"
        prov = e.get("review_reason") or e.get("provenance") or "-"

        row_data = [
            e["date"],
            e["weekday"],
            norm_shift,
            raw_val,
            "Section Inferred" if src_type == InferenceSourceType.SECTION_INFERENCE else "OCR Observed",
            sec_label,
            status,
            supp_dates,
            conf,
            prov,
        ]
        ws_sched.append(row_data)

        # Style row cells
        is_inf = src_type == InferenceSourceType.SECTION_INFERENCE
        is_off = norm_shift == "Off"
        is_unres = norm_shift == "Unresolved"

        for col_idx in range(1, len(row_data) + 1):
            cell = ws_sched.cell(row=row_idx, column=col_idx)
            cell.border = thin_border
            cell.font = Font(name="Calibri", size=10)

            if col_idx in (1, 2, 4, 5, 6, 7, 8, 9):
                cell.alignment = Alignment(horizontal="center", vertical="center")
            else:
                cell.alignment = Alignment(horizontal="left", vertical="center")

            # Highlighting
            if is_inf:
                cell.fill = fill_inferred
                if col_idx in (3, 5):
                    cell.font = Font(name="Calibri", size=10, bold=True, color="B25E00")
            elif is_off:
                cell.fill = fill_off
            elif is_unres:
                cell.fill = fill_unresolved
            else:
                cell.fill = fill_observed

    # Auto-adjust column widths
    col_widths = {
        "A": 13, "B": 12, "C": 15, "D": 15, "E": 18,
        "F": 14, "G": 24, "H": 18, "I": 12, "J": 45,
    }
    for col, width in col_widths.items():
        ws_sched.column_dimensions[col].width = width

    ws_sched.freeze_panes = "A3"

    # Sheet 2: Section Level Summary
    ws_sec = wb.create_sheet(title="Section Validation")
    ws_sec.append([f"Section Shift Inference Validation - {month_str}"])
    ws_sec.merge_cells("A1:I1")
    title_cell2 = ws_sec["A1"]
    title_cell2.font = Font(name="Calibri", size=13, bold=True, color="FFFFFF")
    title_cell2.fill = PatternFill(start_color="1F4E79", end_color="1F4E79", fill_type="solid")
    title_cell2.alignment = Alignment(horizontal="center", vertical="center")

    sec_headers = [
        "Section #",
        "Day Range",
        "Days Count",
        "Observed Shifts",
        "Observed Tokens",
        "Proposed Shift",
        "Inferred Dates",
        "Unresolved Dates",
        "Review Required & Reasons",
    ]
    ws_sec.append(sec_headers)
    for col_idx in range(1, len(sec_headers) + 1):
        c = ws_sec.cell(row=2, column=col_idx)
        c.font = Font(name="Calibri", size=11, bold=True, color="FFFFFF")
        c.fill = PatternFill(start_color="2F5597", end_color="2F5597", fill_type="solid")
        c.alignment = Alignment(horizontal="center", vertical="center")

    sections_info = reconstruction.get("section_inference", {}).get("sections", [])
    for row_idx, sec in enumerate(sections_info, start=3):
        obs_shifts = ", ".join(sec.get("observed_working_shifts", [])) or "None"
        obs_toks = ", ".join(sec.get("observed_tokens", [])) or "None"
        prop = sec.get("proposed_shift") or "None"
        inf_cnt = len(sec.get("inferred_dates", []))
        unres_cnt = len(sec.get("unresolved_dates", []))
        rev_reasons = "; ".join(sec.get("review_reasons", [])) or "None (Section verified)"

        sec_row = [
            f"Section {sec['section_index']}",
            f"Days {sec['start_day']}–{sec['end_day']}",
            sec["day_count"],
            obs_shifts,
            obs_toks,
            prop,
            inf_cnt,
            unres_cnt,
            rev_reasons,
        ]
        ws_sec.append(sec_row)
        for col_idx in range(1, len(sec_row) + 1):
            c = ws_sec.cell(row=row_idx, column=col_idx)
            c.border = thin_border
            c.font = Font(name="Calibri", size=10)
            if col_idx <= 8:
                c.alignment = Alignment(horizontal="center", vertical="center")
            else:
                c.alignment = Alignment(horizontal="left", vertical="center")

    for col, width in {"A": 12, "B": 14, "C": 12, "D": 18, "E": 18, "F": 16, "G": 14, "H": 16, "I": 40}.items():
        ws_sec.column_dimensions[col].width = width

    out_p = Path(output_path).expanduser().resolve()
    out_p.parent.mkdir(parents=True, exist_ok=True)
    wb.save(out_p)
    return str(out_p)

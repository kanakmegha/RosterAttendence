# Architecture — Medical Staff Schedule Tracker

## 1. Architectural approach
Use a modular, pipeline-based design. Parsing must be deterministic and explainable wherever possible. OCR produces observations and coordinates; a separate layout engine maps observations to table cells; a roster interpreter maps cell text to shift meanings. Do not combine all logic into one giant OCR script.

**Core invariant:** no extracted schedule becomes active until the user reviews and approves it.

## 2. High-level components

```text
Client UI
  ├── Profile / Staff ID
  ├── Upload + processing status
  ├── Extraction review
  ├── Monthly calendar + duty list
  ├── Emergency duty form
  ├── Roster revision diff
  └── Export + retention confirmation
          |
          v
Application/API layer
  ├── AuthN/AuthZ (every request)
  ├── Upload orchestration
  ├── Extraction job status
  ├── Review/approval
  ├── Schedule CRUD
  ├── Revision/diff service
  ├── Export service
  └── Retention service
          |
          v
Domain services
  ├── Roster Parser Pipeline
  │    ├── File validation
  │    ├── PDF text/geometry adapter
  │    ├── Image orientation candidates
  │    ├── Image preprocessing candidates
  │    ├── OCR adapter (Apple Vision on macOS)
  │    ├── Layout/table detector
  │    ├── Exact staff-ID matcher
  │    ├── Date-column detector
  │    ├── Floor/department association
  │    ├── Cell-to-date mapping
  │    ├── Shift-code interpreter
  │    └── Confidence + diagnostics
  ├── Revision reconciliation
  ├── Excel export
  └── Retention policy
          |
          v
Persistence
  ├── Users/profiles
  ├── Upload metadata
  ├── Draft extraction/cells
  ├── Approved schedules
  ├── Revisions/change history
  ├── Reminders/retention records
  └── Export job metadata
```

## 3. Parser pipeline (important)
### Stage A — Input validation
- Verify extension, MIME, decodability, page count, file size.
- Create a short-lived processing workspace.
- Never log raw content.

### Stage B — Document normalization
- PDF: extract selectable text with coordinates first. Render pages only when necessary.
- Images: read orientation metadata, then evaluate plausible rotations (0°, 90°, 180°, 270°) as candidates.
- Apply a small set of preprocessing variants when initial OCR/layout quality is poor: scaling, grayscale, contrast, deskew, adaptive thresholding.
- Choose candidates based on combined evidence: staff-ID confidence, header alignment, table structure, date coverage, and geometric consistency. Do not choose solely by total OCR text count.
- Avoid repeatedly enlarging without limit; set pixel and memory caps.

### Stage C — OCR / text extraction
Normalized adapter output:
`TextObservation { text, confidence?, boundingBox, pageIndex, lineId?, source }`
- Bounding boxes use a documented coordinate convention, normalized to top-left origin for all downstream modules.
- PDF text extraction and Vision OCR both emit the same observation schema.
- Apple Vision implementation is local on macOS; hide it behind `OCRProvider` so another provider can be added later if the target platform changes.

### Stage D — Layout and table detection
- Detect likely header bands, date labels, row/column boundaries, employee identity columns, and floor/department fields.
- Date headers may be individual observations or grouped text. Support tokenizing grouped observations while retaining their sub-position uncertainty.
- Use column centers and inferred cell boundaries; associate a shift with the date column whose cell contains it.
- Support roster sections and nonuniform staff groups; don't assume one simplistic row per employee without confirming template.
- Floor/department values may be repeated, merged, or located in a separate column. Association rules must be template-aware and confidence-scored.
- If layout cannot be established, return `NEEDS_REVIEW` with diagnostic evidence. Do not guess.

### Stage E — Exact staff-ID match
- Normalize only harmless differences (case, surrounding whitespace, OCR-confusable characters only when explicitly surfaced for user confirmation).
- Search the expected ID region/column where possible.
- Exact match required. Fuzzy matches are suggestions only, never automatic selection.
- If zero or multiple matches: stop and request correction/review.

### Stage F — Date and shift interpretation
- Determine roster month/year from user input or clear source heading and show it for confirmation.
- Build date column map from detected header numbers plus month/year; validate day range and duplicates.
- Recognize `M`, `E`, `N`, and configured explicit off codes.
- Preserve raw text. Unknown/blank/ambiguous cells stay unknown or review-needed.
- `OFF` must not be inferred from weekend or weekday.
- Each result includes provenance: page, bounding box, original text, parser version, confidence.

### Stage G — Reviewable draft
Emit:
`DraftSchedule { staffId, month, orientation, entries[], unresolvedCells[], diagnostics }`
Each entry contains date, floor, department, rawCode, interpretedShift, confidence, reviewStatus, sourceLocation.
No persistence as an approved schedule at this stage.

## 4. Data flow and state machine

```text
UPLOADED
  -> VALIDATING
  -> EXTRACTING_TEXT
  -> DETECTING_LAYOUT
  -> MATCHING_STAFF_ID
  -> BUILDING_DRAFT
  -> NEEDS_USER_REVIEW
  -> APPROVED
  -> ACTIVE
```

Failure transitions:
- Any invalid/ambiguous stage -> `FAILED` or `NEEDS_REVIEW`.
- A failed/partial run cannot transition directly to `ACTIVE`.
- Revisions go through `REVISION_DRAFT -> DIFF_REVIEW -> APPROVED -> APPLIED`.

## 5. Revision reconciliation rules
- Compare approved schedule to the new draft by date and relevant fields.
- Change types: `ADDED`, `CHANGED`, `REMOVED_CONFIRMED`, `UNCHANGED`, `UNRESOLVED`, `CONFLICT`.
- A value is `REMOVED_CONFIRMED` only when the relevant date/cell is clearly observed and intentionally empty according to validated roster semantics; OCR failure or missing data is `UNRESOLVED`.
- Keep manual emergency duties as separate source records. Do not overwrite them with roster data.
- Require explicit approval for every applied revision.
- Preserve reminders/metadata for unchanged entries.

## 6. Security and privacy
- Every database query is scoped to the authenticated user, enforced server-side.
- Never trust a client-supplied user ID as authorization.
- Use short-lived upload access and private object storage if remote storage is required.
- Default to local parsing where feasible. No external OCR/LLM calls with real roster content by default.
- Redact IDs and source text from logs. Keep sanitized stage metrics.
- Temporary files must be removed on completion/failure and by a cleanup job.
- Define and test deletion scope; do not delete profile/account data as part of schedule cleanup.

## 7. Suggested API contracts
- `POST /api/rosters` — upload/create processing job.
- `GET /api/rosters/{id}/status` — processing status and sanitized diagnostics.
- `GET /api/rosters/{id}/draft` — extracted draft for review.
- `POST /api/rosters/{id}/approve` — approve draft and create active schedule.
- `GET /api/schedule?month=YYYY-MM` — current user's approved schedule.
- `POST /api/emergency-duties` — create manual duty.
- `PATCH /api/emergency-duties/{id}` — edit manual duty.
- `POST /api/roster-revisions` — submit revised roster.
- `GET /api/roster-revisions/{id}/diff` — retrieve before/after diff.
- `POST /api/roster-revisions/{id}/apply` — apply after explicit approval.
- `GET /api/schedule/export?month=YYYY-MM` — create/download current-month Excel.
- `POST /api/retention/{month}/confirm-export` — record explicit user confirmation.
- All endpoints require authentication except intentionally public auth endpoints. Verify ownership server-side.

## 8. Testing strategy
- Unit tests for normalization, exact ID matching, date validation, code legend, and cell classification.
- Golden fixture tests for known synthetic or explicitly approved redacted roster images/PDFs.
- Rotation tests for 0/90/180/270 degrees.
- OCR robustness tests for low contrast, small text, skew, and grouped date labels.
- Geometry tests for mapping each date header to a cell center.
- Negative tests: similar ID, missing ID, duplicate ID, unreadable date, blank cell, unknown shift code, no header, wrong month, multi-section roster.
- Revision tests: unchanged, changed, added, confirmed removal, unresolved OCR, manual-duty conflict.
- Export tests: workbook opens, headers/rows match expected values.
- Authorization tests: user A cannot read or mutate user B's data.
- Retention tests: no deletion without explicit confirmation; grace period and final warning require approved settings.
- Never use real, unredacted staff rosters in public repositories or external test services.

## 9. Module boundaries
1. `document_input`
2. `ocr_adapter`
3. `image_normalization`
4. `layout_detection`
5. `staff_id_matching`
6. `schedule_extraction`
7. `review_approval`
8. `schedule_calendar`
9. `emergency_duties`
10. `revision_diff`
11. `excel_export`
12. `retention_cleanup`
13. `auth_and_authorization`

Each module exposes a small interface, has unit tests, and is merged only after its acceptance gate passes.

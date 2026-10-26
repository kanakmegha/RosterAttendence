# Antigravity Build Prompt — Medical Staff Schedule Tracker

Act as a senior software architect and test-driven engineer. Build the Medical Staff Schedule Tracker **one module at a time**, with explicit checkpoints. Do not generate the entire application in one pass. After each module, stop, run tests, show the results, and wait for my approval before proceeding.

## Product goal
A privacy-first app where a medical staff member enters their staff ID and uploads a roster PDF or photo. The app automatically handles orientation, image enhancement, OCR/PDF text extraction, date-header detection, table/cell mapping, exact staff-ID matching, and extraction of date, floor/department, and shift/holiday. Users must not be asked to rotate or crop the image manually. The extracted schedule remains a draft until the user reviews and approves it.

## Non-negotiable constraints
- Use the PRD and architecture documents in this repository as source of truth.
- Inspect the existing repository before changing anything. Summarize current structure and propose a plan first.
- Preserve existing working code unless a change is justified and explained.
- Never invent the roster layout or assume a blank cell means OFF.
- Known codes: `M` = Morning, `E` = Evening, `N` = Night. Off/holiday codes must come from a configurable, explicitly approved legend. Off may occur on any weekday.
- Match the staff ID exactly. Near matches are suggestions for review only.
- Dates run left to right in the known roster layout, but implement layout detection that can handle grouped OCR observations and variations; do not depend only on date numbers appearing as separate OCR tokens.
- Floor/department must be associated with the correct staff/date cell. If ambiguous, mark `REVIEW`.
- Never activate a schedule without explicit user approval.
- On revised roster upload, show a date-by-date before/after diff. Unreadable/missing cells are `UNRESOLVED`, not removals. Do not silently delete manual emergency duties.
- The main calendar shows the active roster month only.
- Emergency duty fields: date, department, shift, floor. Do not invent shift times.
- Provide current-month Excel export.
- Retention: remind before month-end; delete only after the user explicitly confirms successful export/save. If unconfirmed, retain temporarily and warn again. Do not implement automatic expiry deletion until the product owner configures and approves a maximum grace period and final warning.
- Enforce per-user authorization server-side on every API call.
- Process roster content locally when practical. Do not send real roster images/PDFs to external OCR/LLM services by default.
- Do not log raw OCR dumps, other employees' IDs, or roster images.
- No silent failures. A failed or empty extraction must display an explicit status and diagnostic.

## Build workflow: one module per iteration
For each module:
1. State the purpose and interfaces.
2. List files to create/change.
3. Implement only that module and the minimal integration required.
4. Add unit tests and negative tests before moving on.
5. Run the relevant tests and lint/type checks.
6. Report commands run and actual pass/fail output.
7. Explain limitations and remaining risks.
8. Stop and wait for my approval. Do not continue to the next module until I say so.

If tests cannot be run in the current environment, say so explicitly; do not claim they passed. Do not replace actual test results with expected results.

## Proposed module sequence and acceptance gates

### Module 0 — Repository audit and plan
Inspect the repo, runtime versions, existing code, build/test commands, and architecture. Do not modify code yet. Return a module plan, risks, and decisions requiring my input.

### Module 1 — Domain models and code legend
Define typed models for roster month, OCR observation, date header, extracted cell, draft schedule, confidence/review status, schedule entry, manual duty, and revision diff. Add configurable shift/off legend. Add tests for date validation, month length, and shift interpretation. Blank/unknown must remain unknown.

### Module 2 — Document input and normalization
Validate supported PDF/image formats and limits. Detect image orientation metadata and create 0/90/180/270 candidates automatically. Add bounded preprocessing variants (scale, grayscale, contrast, deskew/threshold where available). Do not alter the user's original file. Test all orientations and malformed files.

### Module 3 — OCR/PDF extraction adapter
Define one common text-observation interface with text, confidence, normalized top-left bounding box, page, and provenance. For PDFs, extract local text with geometry first; OCR scanned pages as needed. For macOS image OCR, use Apple Vision behind an adapter. No third-party network calls. Add fixture tests and clear errors.

### Module 4 — Layout/date-column detection
Detect header bands and date columns using coordinates and geometry. Handle dates grouped into a single OCR observation when possible; do not require each date to arrive as a standalone OCR item. Validate date range, duplicates, ordering, and month. Score candidate orientations/layouts using ID confidence, date coverage, table alignment, and cell consistency—not OCR count alone. Add synthetic coordinate tests and redacted fixture tests. If no reliable layout, return `NEEDS_REVIEW` with diagnostics.

### Module 5 — Exact staff-ID matching and selected schedule extraction
Find the exact ID in the expected ID column/region. Reject near matches. Map the selected person's cells to date columns. Extract floor/department using layout-aware association. Keep raw cell code, interpreted shift, bounding box, confidence, and source page. Do not parse another person's values into the user's schedule. Add tests for missing/duplicate IDs, grouped rows, unknown codes, blank cells, and multiple sections.

### Module 6 — Draft review and approval
Create a review screen that lists date, floor, department, original code, interpreted shift, confidence, and warnings. Allow corrections. Keep the draft separate from the active schedule. Only explicit approval can activate it. Test approval and rejection states.

### Module 7 — User auth/profile and authorization
Implement profile staff ID create/edit and server-side ownership enforcement. Test that user A cannot access user B's data by changing request IDs. Do not rely on UI hiding.

### Module 8 — Current-month calendar and emergency duties
Show only active roster month. Add emergency duty form for date, department, shift, floor. Label manual duties separately and preserve them. Add tests for date boundaries and manual-duty CRUD.

### Module 9 — Revised roster diff
Compare a new approved draft against the active schedule per date. Distinguish ADDED, CHANGED, UNCHANGED, REMOVED_CONFIRMED, UNRESOLVED, and CONFLICT. Missing OCR must never become a removal. Preserve unchanged reminders and manual duties. Require explicit approval to apply. Test all diff cases.

### Module 10 — Excel export
Export current month to `.xlsx` with date, floor, department, shift/holiday, source type, status, and notes. Test workbook opens and values match the approved schedule. Export must be scoped to authenticated user.

### Module 11 — Retention and reminders
Add pre-month-end export reminder and explicit export-success confirmation. Do not delete schedule without confirmation. If not confirmed, retain temporarily and warn again. Leave maximum grace period and final warning as required configuration that blocks automatic deletion until set/approved. Test deletion scope and idempotency. Remove temporary source/OCR artifacts per policy.

### Module 12 — End-to-end acceptance
Use only synthetic or explicitly redacted test fixtures. Test upload → parse → review → approve → calendar → manual duty → revision diff → export → retention. Verify privacy, authorization, and failure handling.

## Required parser diagnostics
For each run, provide sanitized diagnostics:
- input type, dimensions/page count
- candidate orientation and preprocessing variant
- OCR observation count (not raw contents in production)
- date-header candidates and confidence
- exact ID match count and confidence
- detected date-column positions
- cell-to-date mapping confidence
- floor/department association confidence
- unresolved cell count
- final status and actionable error code

Do not dump all OCR text in the normal UI or logs. For developer mode, use synthetic fixtures or user-approved redacted examples only.

## Definition of done for each module
- Implementation matches the module scope.
- Tests include positive and negative cases.
- Build/typecheck/lint passes where configured.
- No unrelated refactor.
- A short README section explains how to run the tests and module manually.
- Report known limitations honestly.
- Stop for my approval.

Start with **Module 0 only**. Inspect the repository and return the audit/plan. Do not begin Module 1 until I approve.

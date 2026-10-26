# Medical Staff Schedule Tracker — Product Brief

## 1. Product summary
A privacy-first application that lets a medical staff member view their own monthly duty roster by entering their staff ID and uploading a roster PDF or photo. The app identifies the exact staff ID in the source, extracts that staff member's date, floor/department, shift, and off/holiday entries, and presents the result as a reviewable monthly calendar.

The product should automate image orientation, image enhancement, OCR, date-header detection, and table/cell association. Users must not be asked to crop the roster, manually rotate it, or prepare OCR input.

## 2. Problem
Monthly staff rosters may be distributed as PDFs, scans, or photos. Finding one person's duties manually is tedious and error-prone. Generic OCR can recognize text while losing the spatial relationships between date headers, staff IDs, floors, and shift cells. A wrong date or shift could affect attendance and patient-care staffing, so extraction must be transparent and reviewable.

## 3. Target user and core job
**User:** a medical staff member who needs their own monthly duty schedule.

**Core job:** “When I receive a roster, let me enter my staff ID and see my duties for the correct dates, with the right floor/department and shift, without manually editing the source.”

## 4. Core experience
1. Sign in / create an account.
2. Enter and save a staff ID; allow the user to edit it later.
3. Upload a roster PDF or photo.
4. The app processes it locally where practical: orientation detection, image enhancement, OCR/PDF text extraction, table geometry analysis, and exact staff-ID matching.
5. The app presents a draft extraction: date, floor/department, original roster code, interpreted shift/holiday, confidence, and source evidence.
6. The user reviews and approves the draft before it becomes the active schedule.
7. The approved schedule appears in a current-month calendar and list.
8. The user can add an emergency duty with date, department, shift, and floor.
9. The user can export the current month's schedule to Excel.
10. At month-end, the app reminds the user to export/save. It deletes the schedule only after the user explicitly confirms a successful export; otherwise it retains the schedule temporarily and warns again according to a configured, bounded grace period.

## 5. Supported roster interpretation
Initial known shift codes:
- `M` = Morning
- `E` = Evening
- `N` = Night
- `off` / explicit recognized off code = Off / holiday, according to the roster legend

Important rules:
- Off can occur on any weekday.
- Blank, unreadable, unknown, or low-confidence cells are **not** automatically off.
- A code is interpreted only using a configured roster legend. Ambiguous codes require review.
- Exact staff-ID matching is required. Similar IDs must not be accepted as a match.
- Date headers run left to right in the user's known roster layout, but parser design should not hard-code one exact visual template.
- Floor/department must be read from the correct associated cell/row/section. Do not infer a floor from a nearby unrelated label.
- Keep the original recognized code and source coordinates for auditability.

## 6. MVP scope
- Authentication and per-user schedule isolation.
- Editable staff ID profile.
- PDF and image upload.
- Local/native PDF text extraction and macOS Vision OCR for image/scanned content in the initial desktop development utility; production architecture should keep the OCR adapter replaceable for other client/server platforms.
- Automatic orientation candidates and image preprocessing.
- Table/header detection, staff ID localization, date-to-cell association, floor/department association, shift code interpretation, confidence and review state.
- Extraction review/approval UI.
- Current-month calendar and list.
- Manual emergency-duty entries.
- Revised roster comparison with per-date before/after diff and approval before applying.
- Excel export for the current month.
- Retention reminders and explicit deletion confirmation.
- Audit-safe errors and privacy controls.

## 7. Out of scope for the first release
- Automatically changing a schedule without user approval.
- Treating missing values as off.
- Sharing raw rosters with third-party OCR or LLM services.
- Hospital HR integrations or automatic roster fetching.
- Inferring schedule facts that are not visible in the source.
- Multi-month calendar browsing in the primary view (the primary calendar shows only the active roster month).
- Clinical decision support.

## 8. Success criteria
- Users can upload a source and enter an ID without cropping or rotating the image themselves.
- Exact ID match is demonstrated; near-match IDs are rejected.
- Every extracted duty maps to the correct date column and associated floor/department in the reviewed source.
- Low-confidence, blank, or ambiguous cells are flagged, not silently filled.
- No schedule is published until the user approves the extraction.
- Roster updates produce a reviewable diff; manual emergency duties are not silently deleted.
- Export creates a valid workbook with the correct month and user-approved entries.
- A user cannot access another user's roster through API calls or guessed IDs.
- Raw files and schedules follow the documented retention policy.

## 9. Product principles
1. Correctness over apparent completeness.
2. Local processing and data minimization.
3. Explicit user approval before schedule changes.
4. Explainable extraction: show original code, date, floor, confidence, and source location.
5. Modular implementation with independently testable milestones.
6. Fail loudly and clearly; never silently produce an empty or partial output as if successful.

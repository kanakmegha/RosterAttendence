# Product Requirements Document (PRD)
## Medical Staff Schedule Tracker

**Status:** MVP requirements baseline  
**Primary risk:** incorrect duty/date/floor association  
**Privacy posture:** process roster content locally where practical; do not send real rosters to external services without explicit informed consent.

## 1. Objective
Build an application that converts a monthly roster PDF/photo into a user-specific, reviewable duty schedule using the user's exact staff ID. It must support monthly calendar viewing, emergency duty entry, safe roster updates, Excel export, and controlled month-end retention.

## 2. User stories and requirements

### 2.1 Profile and identity
- **US-01:** As a user, I can enter my staff ID during setup.
- **US-02:** As a user, I can edit my staff ID later.
- **FR-01:** Staff ID is stored in the authenticated user's profile.
- **FR-02:** All schedule records are owned by a user ID and protected by server-side authorization.
- **FR-03:** ID matching must be exact after conservative normalization. Do not accept fuzzy matches automatically. If OCR reads a near-match, ask for review or fail safely.

### 2.2 Upload and source handling
- **US-03:** I can upload a roster as PDF, JPEG, PNG, or another explicitly supported image type.
- **FR-04:** Validate file type, size, page count, and decoding before processing.
- **FR-05:** Keep processing status visible: queued, processing, needs review, ready, or failed.
- **FR-06:** No empty/failed run may appear as a successful extraction.
- **FR-07:** Retain the original source only as long as required by the configured retention policy; avoid duplicating it unnecessarily.

### 2.3 Automatic parsing
- **US-04:** I enter my staff ID and the system finds my schedule automatically without requiring me to crop or rotate the source.
- **FR-08:** For images, test relevant orientation candidates and use image metadata where available. The user must not be required to rotate the image manually.
- **FR-09:** Apply preprocessing candidates as needed (deskew, contrast, scale, grayscale/adaptive thresholding) and compare OCR/layout results. Avoid blindly selecting a rotation based only on OCR character count.
- **FR-10:** For PDFs, first attempt local text and geometry extraction; use OCR for scanned pages or insufficient text.
- **FR-11:** Detect roster structure: header rows, date columns, staff ID/name columns, shift cells, floor/department fields, and section boundaries.
- **FR-12:** Associate each date with the correct column/cell using geometry and table boundaries, not just text order.
- **FR-13:** Associate floor/department with the correct row/section. If the association is ambiguous, mark it `REVIEW`.
- **FR-14:** Store for each extracted cell: date, raw code, interpreted code, floor/department, confidence, bounding box/source page, and review state.
- **FR-15:** Recognize configured codes: `M` = Morning, `E` = Evening, `N` = Night, and only explicitly configured off/holiday codes as Off/Holiday.
- **FR-16:** Blank cells remain blank/unknown. Unknown and low-confidence values are not interpreted as off.
- **FR-17:** Off is valid on any weekday; weekday/weekend is not evidence of a holiday.
- **FR-18:** Require the roster month/year from the user or reliable source metadata/title. Do not infer a year from an ambiguous photo without review.
- **FR-19:** Exact ID must be found in the ID column/region. If absent or multiple plausible matches exist, stop and request user review rather than extracting a different person's schedule.
- **FR-20:** Provide a debug report with detected orientation, page dimensions, header candidates, ID match coordinates, detected date columns, and confidence summary. It must not expose other staff's personal details in the normal user UI.

### 2.4 Extraction review and approval
- **US-05:** I can review the draft before it appears on my calendar.
- **FR-21:** Show a date-by-date table: date, floor/department, original roster code, interpreted shift/holiday, confidence, and review warning.
- **FR-22:** Show unresolved/ambiguous cells separately.
- **FR-23:** User must explicitly approve the draft before it becomes active.
- **FR-24:** Allow corrections to extracted entries, and record that a correction was user-confirmed.
- **FR-25:** Do not present low-confidence entries as confirmed.

### 2.5 Calendar and emergency duties
- **US-06:** I can view only the current roster month in the main calendar.
- **FR-26:** Calendar events are sourced from approved roster entries and user-created manual duties.
- **FR-27:** An emergency duty can be added with date, department, shift, and floor. Shift times are configurable later; do not invent times in MVP.
- **FR-28:** Label manual emergency duties separately from roster-derived duties.
- **FR-29:** Editing/deleting a manual emergency duty requires an explicit user action.

### 2.6 Revised roster reconciliation
- **US-07:** When I upload a revised roster, I can compare it with my current schedule before applying changes.
- **FR-30:** Generate a per-date diff with old value, proposed new value, and change type (added, changed, removed, unchanged, unresolved).
- **FR-31:** A missing/unreadable cell is `unresolved`, not `removed`.
- **FR-32:** Preserve unchanged events and their reminders/metadata.
- **FR-33:** Do not silently delete or overwrite manual emergency duties. If a conflict exists, show it and ask the user.
- **FR-34:** Apply changes only after explicit user approval.
- **FR-35:** Keep a minimal change history sufficient to explain what was changed and when, without retaining source data longer than policy allows.

### 2.7 Export
- **US-08:** I can export the active roster month to Excel.
- **FR-36:** Export `.xlsx` with one row per date/event and columns for date, floor, department, shift/holiday, source (roster/manual), status, and notes as applicable.
- **FR-37:** Include only the authenticated user's approved schedule and selected month.
- **FR-38:** Provide a clear export-complete state. Do not claim the file was saved to a location unless the platform confirms it.
- **FR-39:** Export must be testable independently from UI and database.

### 2.8 Retention and deletion
- **US-09:** Before month-end, I receive a reminder to export/save my schedule.
- **FR-40:** Before month-end, remind the user to export.
- **FR-41:** Delete the schedule only after the user explicitly confirms that export/save succeeded.
- **FR-42:** If confirmation is absent, retain temporarily and warn again.
- **FR-43:** Define a configurable maximum grace period and a final warning before deletion. Do not implement automatic deletion until product owner sets and approves the exact grace-period duration and final-warning timing.
- **FR-44:** Deletion scope must be explicit: schedule entries, reminders, temporary source files, OCR artifacts, and derived data as defined by policy. Do not delete account/profile data unless separately requested.
- **FR-45:** Make deletion idempotent and auditable; avoid retaining the raw roster in logs.
- **FR-46:** User can manually delete their schedule/source earlier, with confirmation.

### 2.9 Security and privacy
- **FR-47:** Enforce per-user authorization on every API read/write. UI hiding is not security.
- **FR-48:** Use secure transport and secure storage for authentication secrets.
- **FR-49:** Do not log raw roster text, full OCR dumps, staff IDs of other people, or source images in production logs.
- **FR-50:** Process on-device/local when practical. Do not call external OCR/LLM services by default.
- **FR-51:** Restrict temporary file permissions and clean up temporary files on success/failure according to policy.
- **FR-52:** Keep data-minimized diagnostics: error code, processing stage, and sanitized metrics.

## 3. Non-functional requirements
- **NFR-01 Accuracy:** No false certainty. Uncertain extraction must be flagged.
- **NFR-02 Privacy:** Local-first OCR and deterministic parsing; external transmission off by default.
- **NFR-03 Reliability:** Each processing stage has explicit success/failure and a timeout/resource limit.
- **NFR-04 Performance:** Provide progress for large PDFs; don't freeze the UI during OCR.
- **NFR-05 Accessibility:** Review table and calendar are keyboard accessible and readable.
- **NFR-06 Portability:** Keep OCR and document parsing behind adapters so native Vision can be replaced or complemented on non-macOS platforms.
- **NFR-07 Testability:** Each module has unit tests, fixture tests, failure tests, and a documented manual acceptance test.
- **NFR-08 Observability:** Structured, privacy-safe stage logs; no silent failure.
- **NFR-09 Data integrity:** Use transactions for applying approved roster diffs and deletion operations.
- **NFR-10 Maintainability:** Separate OCR, layout detection, interpretation, persistence, UI, export, and retention.

## 4. Key data entities
- **UserProfile:** id, auth_subject, staff_id, created_at, updated_at.
- **RosterUpload:** id, user_id, original_filename, mime_type, month, status, created_at, expires_at; source storage pointer only if required.
- **ExtractionRun:** id, upload_id, parser_version, orientation, status, started_at, completed_at, sanitized_diagnostics.
- **ExtractedCell:** id, extraction_run_id, date, raw_text, interpreted_code, floor, department, confidence, bounding_box, source_page, review_status.
- **ScheduleEntry:** id, user_id, date, floor, department, shift_code, display_label, source_type (`roster`/`manual`), source_entry_id, approval_status, created_at, updated_at.
- **RosterRevision:** id, user_id, previous_schedule_version, proposed_extraction_id, status, approved_at.
- **ScheduleChange:** id, revision_id, date, change_type, before_snapshot, after_snapshot, review_status.
- **Reminder:** id, user_id, schedule_month, reminder_type, scheduled_at, sent_at, status.
- **RetentionRecord:** id, user_id, schedule_month, export_confirmed_at, grace_deadline, final_warning_at, deletion_status, deleted_at.
- **ExportJob:** id, user_id, schedule_month, status, created_at, completed_at.

## 5. Acceptance criteria
1. Exact ID match extracts only the matching user's row/column and rejects near matches.
2. The user never needs to manually crop or rotate a photo.
3. A known test roster with expected values yields matching date/floor/shift entries.
4. OCR/layout uncertainty is visible; blank cells never become off by default.
5. A failed parse produces a clear error and no active schedule.
6. The active calendar changes only after explicit approval.
7. A revision diff distinguishes unresolved from removed and preserves manual duties.
8. Excel export has expected headers and row values.
9. An authenticated user cannot read or mutate another user's schedule by changing IDs in API requests.
10. Retention does not delete without explicit export confirmation and an approved policy for grace-period expiry.

## 6. Open decisions
- Exact hospital roster code legend (all valid off/holiday codes and any other shifts).
- How floor/department is laid out for all supported roster templates.
- Maximum retention grace period and final warning timing.
- Target app platform(s) and authentication provider.
- Whether source files should be deleted immediately after user approval or retained briefly for corrections.
- Whether multiple roster templates must be supported in MVP.

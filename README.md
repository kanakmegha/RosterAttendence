# Medical Staff Schedule Tracker — Planning Kit

This kit contains the product brief, PRD, architecture, and staged Antigravity build prompt.

## Suggested use
1. Put these files at the root of your project repository.
2. Open the repository in Antigravity.
3. Paste `ANTIGRAVITY_PROMPT.md` into Antigravity.
4. Ask it to begin with Module 0 only. Review its repository audit and plan before approving implementation.
5. For every module, verify actual test output before allowing the next module.

## Privacy note
Use synthetic rosters or redacted fixtures in development and testing. Do not send real staff rosters to external OCR/LLM services by default.

## Important product rules
- Staff ID exact match.
- User never needs to manually rotate or crop a roster.
- Unknown/blank cells are not Off.
- Draft extraction requires review/approval.
- Revised roster changes require a before/after diff and approval.
- Manual emergency duties must not be silently removed.
- Server-side authorization is required.
- Deletion depends on explicit export confirmation; grace-period settings must be approved before automatic expiry deletion.

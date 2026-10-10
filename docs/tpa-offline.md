# TPA member ids and e-cards by spreadsheet ("offline mode")

## Why it exists

A TPA's server is down, or the TPA simply does not run an API and works from Excel. Either way the broker still needs the member ids
and e-cards, and the clock is running: premium is pro-rated from the day cover starts, so a few hours can be the difference between a
member who counts from inception and one who is a mid-term endorsement. Every hour a person is waiting is visible, and the sheet can be
applied the moment it arrives.

Two ways in, one code path:

| Who uploads | Where | `source` recorded |
|---|---|---|
| The TPA's own user, signed in to the portal | `/app/member-ids` (gateway `tpa`) | `TPA_PORTAL` |
| The broker, with a sheet the TPA emailed | `/app/member-ids` (gateway `broker`) | `BROKER_FOR_TPA` |

The nightly pull (`TPA_SYNC`) is unchanged. Offline and pull share the matching helpers in `Reconciler`, so the two agree on what "the same
person" means (employee code, relationship, name tie-break).

## The flow

1. **The board** (`GET .../integration/tpa-pending`): every policy with people still waiting, longest wait first. Urgency is `HIGH` from 4 hours
   and `CRITICAL` from 24 (`app.offline.urgent-hours`, `app.offline.critical-hours`).
2. **The template** (`GET .../policies/{id}/offline-template`): an .xlsx of exactly the people waiting. Grey columns are ours (employee code,
   relationship, name, cover start date, endorsement reference); the TPA fills the yellow ones (member id, member name, e-card URL). The broker can
   email it, or skip it and upload whatever the TPA already sent: headers are recognised by name (`Emp ID`, `Membership No`, `UHID`, `Card link`, ...),
   after skipping title rows. `.xlsx`, `.xls`, `.csv` (`,` `;` or tab, UTF-8 or Windows-1252).
3. **Check** (`POST .../offline-sheet`, `mode=preview`): every line gets a verdict and a line number; nothing is saved.
4. **Save** (`mode=apply`): the good lines are saved, the bad ones are listed, and one bad line never holds up the rest. Saving the same sheet twice
   changes nothing (`UNCHANGED`). The portal keeps the checked file for an hour so "Save" needs no second upload.

Roles: `BROKER_ADMIN`, `BROKER_SPECIALIZER` and `TPA_USER` may upload; `BROKER_NON_ADMIN` can read the board only. A TPA user sees only policies routed
to their TPA (and, when the login was created by a broker, only that broker's). Anything else is a 404, never a 403, so existence is not revealed.
A broker creates a TPA login with `POST /users` (`role: TPA_USER`, `tpa_id`); the broker in the request is ignored in favour of the caller's.

## What is checked (legacy parity)

| Legacy check | Reason code here |
|---|---|
| required fields | `EMPLOYEE_CODE_MISSING`, `RELATION_MISSING`, `ID_MISSING` |
| duplicate employee (SELF) or TPA id inside the sheet | `PERSON_DUPLICATE_IN_SHEET`, `ID_DUPLICATE_IN_SHEET` (both lines are refused: we cannot tell whose it is) |
| employee not found / removed from the policy | `EMPLOYEE_NOT_ON_POLICY` |
| relation not on the member | `RELATION_NOT_ON_ROSTER`, `RELATION_UNKNOWN` (map the TPA's word once under reconciliation settings) |
| cover start must equal the member's start date | `COVER_START_MISMATCH`, `COVER_START_UNREADABLE` (the upload form can switch this check off) |
| endorsement id must match | `ENDORSEMENT_REF_MISMATCH` (first 8 characters of the endorsement id, or the whole id) |
| *new* | `ID_IN_USE` (the id already belongs to someone else on this policy), `ECARD_URL_INVALID`, `NAME_AMBIGUOUS` / `NAME_NO_MATCH` (two children, told apart by name) |

A different id for someone who already has one is saved and flagged `REASSIGNED`, with the old id shown.

## Where it lives

- **endorsement engine** owns the roster. New on `EndorsementMember`: `tpa_member_name`, `ecard_url`, `tpa_id_source`, `tpa_id_assigned_at`
  (migration `jobs.0006`). Internal, system-token-only endpoints: policy-level `tpa-member-ids` (one batched `UPDATE ... FROM VALUES` per 500 people),
  `members/?pending=ids|ecards|any`, `members/pending-summary/`.
- **integration-service** owns the upload: `OfflineSheet` (parser), `OfflineMatcher` (verdicts), `OfflineMemberService`, `OfflineMemberController`
  under `/api/v1/broker/integration` and `/api/v1/tpa/integration`. Audit tables `integration.offline_uploads` and `offline_upload_rows` (V4) keep who
  uploaded what, the file's SHA-256 and every line's outcome. An applied upload publishes `TpaMemberIdsReceived` on `tpa.events`.
- **BFF** exposes the same endpoints on the `broker` and `tpa` gateways and renders the portal pages.

## Measured here (one machine, real services, scratch databases)

A 10,000-person roster on one policy: checking a 10,000-line CSV took about 1.0 s end to end through the BFF; saving it took 3.0 s (9.3 s before the
engine write was batched); re-sending the same sheet took 1.8 s and changed nothing. The board for two policies answered in about 130 ms. These
are single runs on a development machine, not a benchmark.

## Not ported, on purpose

- **Re-keying claims when a member id changes.** Legacy rewrote claim rows on a `REASSIGNED`. This platform holds no claims, so only the id changes
  (the old one is kept in the audit row). If claims move here, this needs revisiting.
- **The 48-hour emailed results.** The answer is synchronous: the broker or TPA sees every line's verdict before leaving the page.
- **Member-id and e-card email/WhatsApp to the employee.** Not sent yet. The `TpaMemberIdsReceived` event carries the policy and counts so
  notification-service can pick it up; a per-member message needs per-policy trigger settings that do not exist yet.
- **Per-TPA claim fields** in the sheet.
- **Reading a sheet the TPA password-protects.** Remove the password first; the error says so.

# How many employees? Grid or spreadsheet

The endorsement page asks one question first: **how many employees are you changing?**

| Count | Way in | What happens |
|---|---|---|
| 1 to `GRID_MAX_EMPLOYEES` (default 100) | **Live grid** | A grid session opens, sized for the count (one SELF row per employee, up to 8 rows each). The engine holds the grid; on a pause in typing **only the row that changed is sent**, and the verdict for it, for any other row it affected, and a summary come back. Nothing becomes an endorsement until Submit. |
| More than that | **Spreadsheet** | The engine builds an `.xlsx` for the policy (drop-downs and type checks covering the count's rows plus spares; **data rows left empty on purpose**: the reader skips a fully empty row but checks any row with something in it, so pre-filled placeholders would be reported as errors). The user fills it in, gives the cover start date at upload, and it goes through the normal chunked file path. |

## Services

* `POST /api/v1/{gw}/endorsements/intake/plan/` `{policy_id, op_type, employee_count, effective_date?}` returns `mode: GRID | FILE`. GRID creates the session (201); FILE creates nothing (200).
* `PUT .../gui/sessions/{id}/grid/rows/{row_key}/` `{op_type, row, position, grid_rows}`: **one row, as it changed.** The engine stores it and answers `{row, changed, summary, checks}`: that row's verdict, the verdict of any *other* row it changed (the twin of a duplicate, a family), and the summary. `DELETE` on the same URL removes a row. Emptying a row is a delete.
* `PUT .../gui/sessions/{id}/grid/` stores the whole grid and checks all of it (a paste, a reload, rows inserted mid-grid so positions are renumbered). `POST .../grid/validate/` checks a whole grid without storing it.
* `POST .../submit/` promotes the stored rows to a job. A grid with errors cannot be submitted.
* `GET .../intake/template/?policy_id&op_type&employee_count&effective_date` returns the spreadsheet.

The BFF exposes all of these under `/endorsements/...` (contract `endorsement.php`); the portal pages are `/app/endorse`, `/app/endorse/grid/{id}`, `/app/endorse/file`.

## One rule book

The grid calls `apps.validation.batch.validate_rows`, the same function the file pipeline runs per chunk, with `persist=False`. So duplicates, one PAN on two people, family rules (one RPC to policy-service), the NO_MIDTERM rule and the mid-term dependant rules behave exactly as after an upload. For a handful of rows the row rules run as plain Python (`issues_scalar`) instead of a pandas pass, because a DataFrame has a fixed cost; `tests/test_rules_parity.py` keeps the two identical. The summary (progress, mistakes by kind, completeness, ages, family sizes, sum insured) is computed with pandas in `apps/intake/grid.py` from the stored rows, without checking anything again.

### Why one row is enough

Most rules look at the row alone. The few that look across rows (the same person twice, one PAN on two people, an employee's family against the policy's rules) only look at rows that share an **employee code** or a **PAN**. So when a row changes, the engine re-checks exactly the rows reachable from it through shared codes and PANs (including the codes and PANs the row had *before* the edit, and, transitively, rows that share with those, since a PAN clash changes whether a family is asked about at all). Usually that is one family. Rows outside that set cannot change.

Tests: `tests/test_grid_intake.py` holds the one-row path to the whole-grid check, including a seeded fuzz (six grids, 270 random edits: changing codes, PANs, emptying rows) that must end with every row's verdict identical to checking the whole grid at once; and one that shows only one employee's family is sent to policy-service.

If policy-service cannot be reached for the family rules, the grid still returns every other check and says `checks.family_rules: false`; the family rules run again on submit.

## Settings

| Setting | Default | Where |
|---|---|---|
| `GRID_MAX_EMPLOYEES` | 100 | endorsement-service; the line between grid and spreadsheet |
| `GRID_ROWS_PER_EMPLOYEE` | 8 | the grid's row cap is count x this |
| `GRID_VALIDATE_RATE_PER_MINUTE` | 600 | per session |
| `BFF_GRID_MAX_EMPLOYEES` | 100 | BFF; wording on the form only. Keep equal to the engine's |

## Measured (local, one process, 300 stored rows = 100 employees x 3, fake policy-service, local Postgres)

| | whole grid each time | one row |
|---|---|---|
| request body | ~38,800 bytes | ~170 bytes |
| answer body | ~21,000 bytes | ~750 bytes |
| engine time (in process) | about 85 ms | about 40 ms |

So the large saving is on the wire and in the browser: a keystroke pause costs a few hundred bytes instead of the whole sheet in both directions, which matters most on a slow connection and as the grid grows. Server time fell less than the sizes did: what remains is mostly fixed cost (queries, the summary), not the number of rows. Rows are also checked from a per-row debounce (250 ms) so typing in two rows does not wait on each other.

Separately, the vectorised row rules alone over 100,000 rows take about 1.2 s. None of these is a comparison with the legacy system, which was not run.

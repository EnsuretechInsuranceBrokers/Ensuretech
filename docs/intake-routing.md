# How many employees? Grid or spreadsheet

The endorsement page asks one question first: **how many employees are you changing?**

| Count | Way in | What happens |
|---|---|---|
| 1 to `GRID_MAX_EMPLOYEES` (default 100) | **Live grid** | A grid session opens, sized for the count (one SELF row per employee, up to 8 rows each). On every pause in typing the whole grid is sent to the engine and checked in one pass; the verdict per row and a summary come back. Nothing is saved until Submit. |
| More than that | **Spreadsheet** | The engine builds an `.xlsx` for the policy and count; the user fills it in and uploads it through the normal chunked file path. |

## Services

* `POST /api/v1/{gw}/endorsements/intake/plan/` `{policy_id, op_type, employee_count, effective_date?}` returns `mode: GRID | FILE`. GRID creates the session (201); FILE creates nothing (200).
* `POST .../gui/sessions/{id}/grid/validate/` `{op_type, rows}` checks the whole grid and stores nothing.
* `PUT .../gui/sessions/{id}/grid/` saves the grid (errors included, so nothing typed is lost); `POST .../submit/` then promotes it to a job. A grid with errors cannot be submitted.
* `GET .../intake/template/?policy_id&op_type&employee_count&effective_date` returns the spreadsheet.

The BFF exposes all four under `/endorsements/...` (contract `endorsement.php`); the portal pages are `/app/endorse`, `/app/endorse/grid/{id}`, `/app/endorse/file`.

## One rule book

The grid check calls `apps.validation.batch.validate_rows`, the same function the file pipeline runs per chunk, with `persist=False`. So duplicates inside the grid, one PAN on two people, family rules (one RPC to policy-service for the whole grid), the NO_MIDTERM rule and the mid-term dependant rules all behave exactly as they would after an upload. `tests/test_grid_intake.py::test_grid_and_csv_agree_on_which_rows_fail` holds the two together. The summary (progress, mistakes by kind, completeness, ages, family sizes, sum insured) is computed with pandas in `apps/intake/grid.py`.

If policy-service cannot be reached for the family rules, the grid still returns every other check and says `checks.family_rules: false`; the family rules run again on submit.

## Settings

| Setting | Default | Where |
|---|---|---|
| `GRID_MAX_EMPLOYEES` | 100 | endorsement-service; the line between grid and spreadsheet |
| `GRID_ROWS_PER_EMPLOYEE` | 8 | the grid's row cap is count x this |
| `GRID_VALIDATE_RATE_PER_MINUTE` | 600 | per session |
| `BFF_GRID_MAX_EMPLOYEES` | 100 | BFF; wording on the form only. Keep equal to the engine's |

## Measured (local, one process)

* Whole-grid check, 300 rows (100 employees x 3), fake policy-service, local Postgres: about 75 ms per call.
* The vectorised row rules alone over 100,000 rows: about 1.2 s.

These are not comparisons with the legacy system, which was not run.

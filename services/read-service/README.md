# read-service

Django 6.1 + DRF, **no relational database**. MongoDB read models projected from the platform's Kafka events, plus a tenant-isolated
retrieval-augmented "ask" endpoint. PostgreSQL stays the source of truth (ADR-016); everything here can be dropped and rebuilt from the
event log. Nothing here is on a write path, and nothing here makes an authoritative decision.

## Pieces
- **Projector** (`manage.py run_projector`): consumes `endorsement.events`, `policy.events`, `cd-balance.updates`, `tpa.events` and upserts
  `jobs`, `job_history`, `policies`, `cd_accounts`, `tpa_syncs`. Handlers are idempotent; offsets are committed after the write; a transient
  MongoDB failure blocks on that message with backoff (order kept); a poison message goes to `projector_dead_letters` and is skipped.
  A stale `EndorsementStateChanged` never overwrites a newer state (timestamp-guarded upsert). `rebuild_projections --yes` drops the
  projections and replays the topics from the beginning (stop the projector first; uploaded knowledge documents are untouched).
- **Read API** (`/api/v1/{broker,employer}/read/`): `jobs/`, `jobs/{id}/` (with history), `policies/`, `policies/{id}/` (with deposit and recent
  TPA syncs), `cd/`, `dashboard/` (scoped aggregations computed at read time). Brokers see their broker; HR users only their employer.
- **RAG** (`/api/v1/{broker,employer}/rag/`): `knowledge/` (BROKER_ADMIN uploads policy documents / TPA rules / notes, with an audience of
  BROKER or EMPLOYER), `ask/` (question, optional `policy_id`). Event history (job transitions, applied endorsements, TPA sync outcomes) is
  indexed automatically so "why was job X cancelled?" is answerable. Answers carry citations and `advisory: true`.

## Tenant isolation (the part that must not break)
- Scope comes only from the verified token. All user-facing reads go through `ScopedCollection`, which merges `{broker_id[, employer_id]}`
  into every find/count/aggregate/insert/delete; aggregation stages that can reach other collections or write (`$lookup`, `$out`, `$merge`,
  `$unionWith`, `$facet`, ...) are refused. Query parameters are whitelisted and typed, never passed as operators. Cross-tenant ids are 404.
- Retrieval filters by tenant **before** scoring and before anything reaches an LLM, so another tenant's text cannot enter a prompt.
  Employers only retrieve audience `EMPLOYER` documents for their own employer plus their own event history.
- No member PII is projected or embedded (events do not carry it; roster data stays in the engine's PostgreSQL).
- Gateways fail closed: SYSTEM and TPA tokens cannot read here; broker/employer roles without their ids are 403.

## Answering
`ANSWERER=extractive` (default): returns the best passages verbatim. `ANSWERER=claude`: grounded generation over the retrieved passages only
(`CLAUDE_MODEL`, default `claude-opus-5-5`, low effort, no tools). Retrieved text is fenced as untrusted data and the closing tag neutralised.
Any failure (no key, API error, refusal, empty output) falls back to extractive, so `/ask` never 500s because of an LLM. 30 asks/min/user.

## Tests
`TEST_MONGO_URI=mongodb://host:27017 pytest` (58 tests, a throwaway database per test). The sandbox has no MongoDB, so they ran against
**FerretDB 1.24 on PostgreSQL**, which speaks the MongoDB wire protocol (reports 7.0.42) but is not MongoDB. Run them once against a real
MongoDB before relying on this. Also smoke-tested over HTTP with a real identity-service token.

## NOT done / limits (read before relying on it)
- **Retrieval is lexical.** The default embedder hashes words (no model); it finds shared vocabulary, not meaning (in the smoke test the
  "cancelled by HR" job ranked third for a question that named it). Swap `EMBEDDER` for a real embedding model and re-embed. Scoring is
  in-process numpy over the tenant's chunks (capped at `RAG_MAX_CANDIDATES`, reported as `truncated`): fine for thousands of chunks; use a
  vector index (Atlas Vector Search / pgvector) beyond that, with the same tenant pre-filter.
- **The Claude path was only tested against a stub**, never the live API (no key here). The Kafka loop was tested with a fake consumer;
  `run_projector` / `rebuild_projections` have never run against a real broker.
- **No prediction.** "Prediction" needs historical outcomes and a trained model; none exists. Not built, not faked.
- Not projected: member lookup (PII), roster, billing statements, employers (so no employer/policy/employee headline counts like the legacy dashboard). Roster-level
  reports (enrollment, e-card, member-id) belong in the endorsement engine and are not built. Employer/HR notification feeds, a change-stream push, and PDF/Excel ingestion for
  knowledge documents (text only) are not built. Throttle counters are per-process (LocMem).
- Event ordering across topics is not assumed; same-topic order relies on the broker_id partition key.

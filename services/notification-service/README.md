# notification-service (the mailer)

Django 6.1 + DRF, PostgreSQL schema `notification`. Turns platform events into emails that brokers configure per policy, and keeps a delivery log.
Modelled on the legacy system's trigger / policy-mapping / template / email-log design, without its per-portal code branches.

## Flow
1. `manage.py run_consumer` reads `endorsement.events`, `policy.events`, `cd-balance.updates`, `tpa.events`. `routing.process_event` maps an event to triggers
   (state change -> request raised / needs review / failed / blocked on deposit / completed; applied -> members added / updated / removed; `CdBalanceLow`;
   `PolicyApproved`; `TpaSyncFailed`), checks the trigger is enabled, finds the recipients, renders the wording and inserts one `notifications` row per
   recipient. The row key is `event_id:trigger:email`, so replays and redeliveries add nothing. Policy events also teach a small directory so mails say "policy P-123".
2. `manage.py run_sender` claims due rows (row lock + lease; a crashed sender's rows are reclaimed), sends over SMTP and records the outcome.
   4xx / network / timeout -> retry with exponential backoff and jitter, then `DEAD` after `MAIL_MAX_ATTEMPTS`; 5xx, refused recipient, malformed message -> `DEAD` at once;
   more than `MAIL_MAX_PER_RECIPIENT_PER_HOUR` -> `SUPPRESSED`. A broker admin can retry a `DEAD` row.

## Configuration (broker admins, `/api/v1/broker/notifications/`)
| Endpoint | Purpose |
|---|---|
| `GET triggers/` | the catalogue: recipient roles, placeholders, built-in default, the broker's default and per-policy overrides |
| `PUT trigger-settings/` | turn a trigger on/off for the broker (omit `policy_id`) or for one policy; policy beats broker beats built-in |
| `GET/POST contacts/`, `DELETE contacts/{id}/` | who gets mail for a role (BROKER_SPOC, EMPLOYER_SPOC, TPA, INSURER); scoped to a policy, an employer or the whole broker |
| `GET/POST/PUT/DELETE templates/`, `POST templates/preview/` | the broker's own wording, per broker / employer / policy (most specific wins) |
| `GET log/`, `GET log/{id}/`, `POST log/{id}/retry/` | delivery log (filter by status, trigger, policy) and dead-letter retry |
Writes need BROKER_ADMIN; employers and SYSTEM tokens get 403; everything is scoped to the token's broker (other brokers' ids are 404).

## Safety
- Templates are **placeholder substitution only** (`{{name}}` from a fixed per-trigger list): no expressions, no template engine, so a broker cannot run code. Unknown or malformed
  placeholders are rejected on save; scripts, frames, forms and event-handler attributes are rejected; placeholder values are HTML-escaped; subjects are single-line.
- Header injection is closed twice: values are stripped of control characters when rendered, and the message builder refuses any CR/LF in a header (the row goes `DEAD`).
- Contact names/emails are validated (no control characters or angle brackets).

## Run / test
`DATABASE_URL=postgres://... python manage.py migrate`, then run the consumer and the sender. Tests (`pytest`, 33) use PostgreSQL and a **real SMTP server** (aiosmtpd) on a random port.
Events are checked against the shared samples in `shared/contracts/events/`.

## NOT done (read before relying on it)
- **Coverage:** 11 of the legacy system's ~120 mail triggers. The rest (claims, LOL/GPA/GTL, enrollment reminders and window-closure mails, e-cards, birthdays, OTP and
  onboarding mails, midterm approval) need events this platform does not emit yet.
- **Channels:** email only. WhatsApp and SMS (legacy has per-policy switches for both) are not built. No attachments, so scheduled report mails and e-card/COI mails are not possible yet.
- **Recipients** come from contacts the broker maintains here; there is no sync from organization-service or identity, and no "the user who uploaded the file" recipient.
- **Scheduled mails** (reminders, daily/weekly reports, policy expiry) need a scheduler and report attachments; not built.
- No bounce/complaint handling, unsubscribe, or tracking. The Kafka consumer and the `run_*` commands have not run against a real broker (loop logic is tested with fakes).
- HTML templates are filtered with a deny-list, not a full sanitiser; brokers are trusted-but-checked users.

# What runs for free locally, and what needs a paid service

"Free" = open-source software you run yourself (no licence fee, no account). "Paid" = a third party you must contract, or a cloud bill in production.
Cost of *hosting* the free pieces in production (servers, managed databases) is separate; this table is about whether you can develop and test without paying anyone.

## Our own services (all free to run locally)
| Component | Stack | Needs | Free locally? | Notes |
|---|---|---|---|---|
| identity-service | Django | PostgreSQL | Yes | RS256 keys are generated locally |
| endorsement-service (engine) | Django + Celery | PostgreSQL, RabbitMQ (Celery), Redis (optional), Kafka (outbox relay) | Yes | Tests run Celery eagerly and use a fake Kafka sender, so neither broker is needed for `pytest` |
| organization-service | Spring Boot | PostgreSQL, Kafka | Yes | Kafka only for the outbox relay |
| policy-service | Spring Boot | PostgreSQL, Redis, Kafka | Yes | gRPC to the engine |
| billing-service | Spring Boot | PostgreSQL, Kafka | Yes | CD ledger |
| integration-service | Spring Boot | PostgreSQL, Kafka | Yes | Talks to TPAs (see below) |
| notification-service | Django | PostgreSQL, Kafka, an SMTP server | Yes locally | Any local SMTP sink (aiosmtpd, MailHog) works; real delivery needs a paid mail provider |
| read-service | Django | MongoDB (or FerretDB on PostgreSQL) | Yes | Default retrieval is lexical hashing; no model |
| sheet-generator-service + standalone `index.html` | Spring Boot / browser | PostgreSQL | Yes | The HTML page is fully offline (no CDN needed); SheetJS from a CDN is optional |

## Infrastructure
| Piece | Free local option | Paid / managed in production |
|---|---|---|
| PostgreSQL | Yes (apt, Docker) | Managed Postgres (RDS, Cloud SQL, ...) |
| Redis | Yes | Managed Redis (ElastiCache, ...) |
| Kafka | Yes (Docker, KRaft) | Managed Kafka (MSK, Confluent) |
| RabbitMQ | Yes | Managed RabbitMQ (e.g. CloudAMQP) |
| MongoDB | Yes (Community) or FerretDB | Atlas |
| File storage for uploads | Yes (local disk, MinIO) | S3 / GCS |
| Container orchestration, TLS, DNS, monitoring | Yes (Docker Compose) | Cloud account, domain, certificates (Let's Encrypt is free) |

## Things that cannot be fully exercised without a paid or contracted party
| Capability | Why | Free stand-in used today | What you must buy / contract |
|---|---|---|---|
| Real TPA connections (Care, MediAssist, FHPL, Paramount, Safeway, Palm ...) | Each TPA issues credentials, sandbox access and its own keys under contract; their response formats come from them | Fake TPA HTTP server in tests; generic REST adapter | Commercial agreement + sandbox credentials per TPA |
| Insurer submission (sent-to-insurer, IC report) | Insurer portals/APIs are contractual | None yet (lifecycle API is built, adapters are not) | Insurer onboarding/API access |
| E-cards and endorsement copies | Produced by the TPA/insurer | None yet | Same as above |
| Email delivery to real people | Needs a sending domain and reputation | Local SMTP sink | Mail provider (SES, SendGrid, ...) |
| SMS / WhatsApp / OTP | Telecom and sender registration | Not built | SMS gateway (and DLT registration in India) |
| LLM answers in read-service (`ANSWERER=claude`) | Anthropic API is metered | `ANSWERER=extractive` (default, free) | Anthropic API key (pay per token) |
| Semantic search quality | A real embedding model | Word-hash embedder (free, weak) | Hosted embedding API, or a self-hosted open model (free software, needs GPU/CPU capacity) |
| Production hosting and managed data stores | Servers and managed services | Docker/localhost | Cloud account |
| Payment collection / CD top-ups | Payment gateway and bank | Ledger only; no gateway | Payment gateway (Razorpay etc.) and bank account |
| Government/identity checks (PAN, Aadhaar, KYC) | Licensed providers | PAN is only stored, not verified | KYC provider |

## Bottom line
Everything we have written can be developed, tested and demonstrated locally at no cost. Money is needed only at the edges: real TPAs and insurers (contracts), real
email/SMS delivery, an LLM or embedding API if you want AI answers, payments, and production hosting.

# 🛡️ Ensuretech

<p align="center">
  <strong>Insurance Onboarding Infrastructure for the Modern Ecosystem</strong><br/>
  <em>Where brokers, TPAs, insurers, employers, and HR teams converge onto a single, validated, auditable onboarding layer.</em>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Python-3.13%2B-blue?logo=python&logoColor=white" alt="Python"/>
  <img src="https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white" alt="Java"/>
  <img src="https://img.shields.io/badge/Django-5.0%2B-092E20?logo=django&logoColor=white" alt="Django"/>
  <img src="https://img.shields.io/badge/Spring%20Boot-3.2-6DB33F?logo=spring&logoColor=white" alt="Spring Boot"/>
  <img src="https://img.shields.io/badge/PostgreSQL-15-336791?logo=postgresql&logoColor=white" alt="PostgreSQL"/>
  <img src="https://img.shields.io/badge/Kafka-KRaft%203.7-231F20?logo=apachekafka&logoColor=white" alt="Kafka"/>
  <img src="https://img.shields.io/badge/RabbitMQ-3.12-FF6600?logo=rabbitmq&logoColor=white" alt="RabbitMQ"/>
  <img src="https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker&logoColor=white" alt="Docker"/>
  <img src="https://img.shields.io/badge/MVP%20Cost-%240-success" alt="Zero Cost MVP"/>
  <img src="https://img.shields.io/badge/License-Proprietary-red.svg" alt="License" />
</p>

---

## 📑 Table of Contents

- [Executive Summary](#-executive-summary)
- [The Convergence Thesis](#-the-convergence-thesis)
- [Architecture at a Glance](#-architecture-at-a-glance)
- [The Three Intake Paths](#-the-three-intake-paths)
- [The 1M-Row Demo Pipeline](#-the-1m-row-demo-pipeline)
- [Fortress Security Model](#-fortress-security-model)
- [Zero-Cost Guarantee](#-zero-cost-guarantee)
- [Services Breakdown](#-services-breakdown)
- [Tech Stack & Licenses](#-tech-stack--licenses)
- [Resilience Engineering](#-resilience-engineering)
- [Quick Start](#-quick-start)
- [Demo Runbook](#-demo-runbook)
- [Architecture Decision Records](#-architecture-decision-records)
- [Legacy Migration Map](#-legacy-migration-map)
- [Roadmap](#-roadmap)
- [Contributing](#-contributing)
- [License](#-license)

---

<p align="center">
  <em>"Simplicity is prerequisite for reliability."</em><br/>
  <sub>— Edsger W. Dijkstra</sub>
</p>

---

## 📖 Our Story

Ensuretech was born from frustration — the kind that only comes from watching a system fail in production while the people who depend on it lose hours of their lives.

We started with a Laravel monolith that crashed on large Excel uploads. We watched brokers spend twelve hours reconciling data that should have taken twelve minutes. We saw TPAs reject clean enrollments because of a single malformed column. We experienced a DDoS attack — not from an external threat aYour, but from a disgruntled user inside our own system who had been given too much trust and too few guardrails.

The pattern became impossible to ignore: **the pain was not isolated. It was systemic.** Every party in the insurance onboarding ecosystem — brokers, TPAs, insurers, employers, HR teams — was suffering from the same fragmentation, the same Excel chaos, the same delayed validation, the same reconciliation burden. They were all fighting the same battle, separately, with different tools, speaking different dialects of the same data.

So we stopped building another portal. We started building the infrastructure layer that everyone could share.

Ensuretech is the result: a convergence platform where validated data enters once, flows everywhere, and never has to be reconciled again.

---

## 🔭 Vision

**A world where insurance onboarding is invisible infrastructure — where brokers, TPAs, insurers, and employers converge on a single operational layer, and the friction of enrollment simply disappears.**

---

## 🎯 Mission

**To build resilient, neutral, standards-driven onboarding infrastructure that eliminates Excel chaos, validates data before it enters the ecosystem, and makes switching costs increase naturally through shared operational truth — so that every stakeholder spends less time reconciling and more time serving.**

---

## 🎯 Executive Summary

Ensuretech replaces a monolithic, request-blocking Laravel legacy system with a **resilient, event-driven microservices platform** engineered for high-volume insurance brokerage operations. It is not another broker portal — it is the **operational source of truth** for group health insurance onboarding.

| Stakeholder | Pain We Solve |
|-------------|---------------|
| **Brokers** | Onboarding overhead, rejection loops, operational manpower |
| **TPAs** | Malformed enrollment data, dirty Excel files, reconciliation burden |
| **Insurers** | Inconsistent policy ingestion, compliance gaps, audit opacity |
| **Employers / HR** | Terrible onboarding UX, spreadsheet chaos, delayed validation |
| **Operations** | Manual reconciliation, cascading failures, lack of observability |

Once all parties interact through the same operational onboarding layer, switching costs increase naturally — and Ensuretech becomes **ecosystem infrastructure**, not just an application.

---

## 🌐 The Convergence Thesis

Ensuretech is evolving into a **convergence platform** for the insurance onboarding ecosystem. Every party currently suffers from fragmented onboarding, inconsistent schemas, Excel chaos, and delayed validation. By attacking the **shared pain layer**, multiple stakeholders naturally converge onto a single operational core.
```

                ┌─────────────────────────────────────┐
                │                                     │
Brokers ────────┤                                     ├─────── TPAs
                │                                     │
Employers ──────┤     ENSURETECH CONVERGENCE LAYER    ├─────── Insurers
                │                                     │
HR Teams ───────┤                                     ├─────── Operations
                │                                     │
                └─────────────────────────────────────┘
                                │
                Shared schemas · Standardized workflows
                Validated events · Immutable audit trails
```

**Key properties of a convergence platform:**
- **Neutral** — not broker-only, not insurer-only, not TPA-only
- **Sticky** — shared schemas and workflows create natural switching costs
- **Defensible** — becomes the operational source of truth for the ecosystem
- **Configurable** — per-party differences handled via configuration, not code branches

---

## 🏗️ Architecture at a Glance

A **resilience-first, workload-segregated, read/write-optimized** microservices architecture engineered around three non-negotiable pillars:

1. **Operational Resilience** — Idempotency, bulkhead isolation, hybrid scheduling
2. **Workload Isolation** — Separate queues, workers, and concurrency caps per domain
3. **Read/Write Optimization** — OLTP tables (heavy indexing) vs. staging tables (append-only)


```
┌──────────────────────────────────────────────────────────────────┐
│ CLIENT LAYER                                                     │
│ NextJS (Broker Portal) · Svelte (GUI Editor / Demo UI)           │
└────────────────────────────┬─────────────────────────────────────┘
                             │ GraphQL / REST / SSE / WebSocket
┌────────────────────────────-─────────────────────────────────────┐
│ FORTRESS EDGE — BFF / API GATEWAY                                │
│ TLS Termination · JWT Validation · Per-Tenant Rate Limits        │
│ Honeypot Endpoints · Anomaly Detection · Silent Alerts           │
└────────────────────────────┬─────────────────────────────────────┘
                             │ Private Network (mTLS)
┌────────────────────────────-─────────────────────────────────────┐
│ BOUNDED MICROSERVICES                                            │
│                                                                  │
│ 🔐 identity-service      Django + DRF + SimpleJWT                │
│ 🏢 organization-service  Spring Boot 3.2                         │
│ 📜 policy-service        Spring Boot 3.2 + gRPC + Strawberry     │
│ 📥 endorsement-service   Django + Celery + RabbitMQ              │
│ 💰 billing-service       Spring Boot 3.2 + gRPC                  │
│ 🔌 integration-service   Spring Boot 3.2 + Resilience4J          │
└────────────────────────────┬─────────────────────────────────────┘
                             │
┌────────────────────────────-─────────────────────────────────────┐
│ DATA & MESSAGING LAYER                                           │
│                                                                  │
│ 🐘 PostgreSQL   (OLTP + Staging + Outbox + RLS)                  │
│ 🌀 SurrealDB    (Multi-model, graph, session state)              │
│ 🐇 RabbitMQ     (Task queues, DLQs, bulkhead routing)            │
│ 📨 Kafka KRaft  (Event streaming, audit, replay)                 │
│ 🔑 Valkey/Redis (O(1) validation cache, Bloom filters, locks)    │
│ 📦 MinIO        (S3-compatible object storage)                   │
└────────────────────────────┬─────────────────────────────────────┘
                             │
┌────────────────────────────-─────────────────────────────────────┐
│ OBSERVABILITY LAYER                                              │
│ Prometheus · Grafana · Celery Flower · OpenTelemetry · Jaeger    │
└──────────────────────────────────────────────────────────────────┘
```


---

## Architectural Decisions: Privileged Access, BFF Mitigation & Payment Service

### 1. Overview

The system will distinguish between:

1. **Public API access through the BFF**
2. **Privileged human access to production**
3. **Core backend source-code ownership**
4. **Direct service-to-service access**
5. **Emergency production mitigation**

The goal is to keep the Main Backend highly protected while allowing a larger operational team to diagnose and resolve production issues without requiring source-level access.

The BFF will therefore serve not only as the public API gateway, but also as the **public contract enforcement and mitigation boundary**.

---

### 2. Source-Code Ownership vs Production Access

Source-code access and production access are intentionally separate privileges.

### Core source owners

Only three trusted individuals currently have source-code access to the Main Backend:

- Me
- You
- Core Backend Owner

These individuals constitute the highest-trust group and are responsible for permanent source-level fixes to the Main Backend.

**Privileged production operators**

A broader group may require production access:

- Me
- You
- Core Backend Owner
- DevOps
- Database Administrator
- BFF / Production Operations personnel as required

Having production access does **not** imply having source-code access.

Likewise:

> Source-code access, infrastructure access, database access, and application-level access are separate privileges.

This separation limits blast radius if an employee endpoint, account, or credential is compromised.

---

### 3. Main Backend Network Boundary

The Main Backend remains private and must not be publicly routable.

Normal application traffic follows:

```text
Client
  |
  v
+----------------+
|   Laravel BFF  |
+-------+--------+
        |
       mTLS
        |
        v
+----------------+
| Main Backend   |
| Private Network|
+----------------+
```

The BFF remains the sole public application entry point.  

Privileged employees who require direct interaction with internal APIs must enter through the corporate VPN.  

```text
Employee
   |
   v
 VPN
   |
   v
Private Network
   |
   v
Authorized Internal API
```
<br />
The VPN provides network access; it does not itself grant unrestricted backend privileges.  

### 4. Postman and Manual Backend Access

Postman will be the organization's supported manual client for privileged API interaction.  

Employees requiring direct API interaction will use:  

```text
VPN
+
Individual Authentication
+
Authorization
+
Approved Postman Collection
```

**Postman Is Not a Security Boundary**

The system must not depend on identifying requests as coming from Postman. A request made through `curl`, Postman, Python, or another HTTP client is technically the same HTTP request once it reaches the authorized network boundary.

The actual security controls are:

- Private network access
- VPN authentication
- Individual user identity
- API authentication
- Authorization
- Narrow endpoint permissions
- Service/network isolation

The objective is therefore not to make `curl` technically impossible, but to prevent an unauthorized or compromised endpoint from obtaining unrestricted production access.

### 5. BFF as the Public Contract Boundary

The Laravel BFF is responsible for enforcing the public API contract between clients and the Main Backend.  

```text
                 Main Backend
                      |
                      | Internal Response
                      v
              +---------------+
              |   Laravel BFF |
              |---------------|
              | Validation    |
              | Sanitization  |
              | Normalization |
              | Transformation|
              | Mitigation     |
              +-------+-------+
                      |
                      v
                    Client
```

The Main Backend may expose an internal representation that should never be exposed directly to clients.  

The BFF therefore owns the public representation of backend data.  

Example:  
```json
{
    "policy_id": 123,
    "status": "ACTIVE",
    "internal_tpa_code": "SECRET-123",
    "internal_risk_score": 0.73
}
```

may become:

```json
{
    "policy_id": 123,
    "status": "ACTIVE"
}
```

The frontend should depend on the BFF's public JSON contract rather than the Main Backend's internal schema.

### 6. BFF Response Mitigation
The BFF will support controlled response transformations that allow certain Main Backend defects to be mitigated without immediately modifying the core backend.

This is particularly useful when:  
- A backend response contains an unintended field
- A response violates the public API contract
- A legacy response format must temporarily be supported
- An invalid representation can safely be normalized
- A security issue can be contained at the response boundary
- A production defect can be safely masked without changing business semantics

Example:
```text
Main Backend
     |
     | buggy response
     v
Laravel BFF
     |
     | safe transformation
     v
Correct public response
```

This provides an additional layer of defense between the Main Backend and external consumers.  

### 7. BFF Mitigation Must Not Become Business Logic

The BFF may modify representation, but it should not become a second implementation of the core domain.
```json
Acceptable:

Backend:
"status": "ACTV"

BFF:
"status": "ACTIVE"
```
```json
Acceptable:

Backend:
{
    "internal_id": 123,
    "name": "John"
}

BFF:
{
    "id": 123,
    "name": "John"
}
```
<br />
Potentially unsafe:

```json
Backend:
premium = 1,000,000

BFF:
recalculate premium = 100,000
```
<br />
Business calculations, domain invariants, authorization decisions, financial state, and authoritative data remain responsibilities of the Main Backend.  

The BFF should primarily perform:  

- Filtering
- Sanitization
- Validation
- Normalization
- API composition
- Contract compatibility
- Safe emergency mitigation
<br />

### 8. Categories of BFF Transformations. 
**8.1 Security Filtering**

Permanent behavior used to prevent internal implementation details from reaching clients.

Examples:

-Remove internal IDs
- Remove database metadata
- Remove debugging information
- Remove internal service metadata
- Remove secrets or credentials
- Remove fields not included in the public contract.
<br />

**8.2 Contract Compatibility**

Temporary or versioned transformations used to maintain a stable public API.  

Examples:

- Rename fields
- Normalize enum values
- Normalize nullable values
- Convert legacy response formats
- Maintain backwards compatibility
<br />

**8.3 Emergency Mitigation**

Temporary transformations used to protect production while the permanent Main Backend fix is developed.
```text
Bug discovered
      |
      v
Can BFF safely mitigate?
      |
     YES
      |
      v
Deploy BFF mitigation
      |
      v
Production protected
      |
      v
Permanent backend fix
      |
      v
Remove mitigation
```
<br />

### 9. Temporary Mitigations Require Explicit Ownership

Emergency BFF mitigations should not silently become permanent architecture.

Each mitigation should identify:

- Unique mitigation ID
- Affected endpoint
- Reason for mitigation
- Owner
- Date introduced
- Expected removal condition
- Expiration/review date
- Permanent backend issue/fix

Conceptually:
```json
mitigation:
  id: BACKEND_BUG_2026_081
  endpoint: GET /policies/{id}
  reason: "Invalid backend response"
  enabled: true
  expires_at: 2026-09-15
```

The objective is to prevent the BFF from accumulating undocumented business logic and becoming a second backend.

### 10. Production Debugging as a Separate Privilege

The architecture introduces an intermediate privilege level between ordinary API access and core backend ownership.

**A. Authorized BFF/Operations personnel may be able to:**
   1. Inspect requests
   2. Inspect sanitized responses
   3. Inspect job status
   4. Inspect queue state
   5. Retry safe/idempotent jobs
   6. Inspect validation failures
   7. Reprocess approved operations
   8. Apply approved BFF mitigations
   9. Disable a broken client capability
   10. Inspect operational diagnostics

**B. They should not automatically receive permission to:**
   1. Modify Main Backend source code
   2. Execute arbitrary SQL
   3. Deploy arbitrary backend code
   4. Access backend secrets
   5. Modify authentication rules
   6. Change core business logic
   7. Perform unrestricted production data manipulation


This creates the distinction:

Debugging authority is broader than ordinary application access, but narrower than source-level authority.

### 11. Production Incident Escalation

Production incidents should follow an escalation model rather than automatically involving one of the three core source owners.
```text

                 Production Incident
                         |
                         v
                BFF / Operations Team
                         |
              +----------+----------+
              |                     |
        Can safely diagnose?   Requires core
                              modification?
              |                     |
             YES                    YES
              |                     |
              v                     v
       Diagnose / Mitigate     Core Engineers
              |                     |
              |                     v
              |               Permanent Fix
              |                     |
              +----------+----------+
                         |
                         v
                  Verify Production
```

This allows the broader operations team to resolve routine production issues while reserving source-level intervention for problems that genuinely require it.

### 12. Bug Bounty and Red-Team Integration

The bug bounty/red-team team will continue to stress-test the Main Backend.

When a vulnerability is discovered, it is reported to the Main Backend owner.

The immediate response should then determine whether the vulnerability can be safely contained at the BFF boundary.
```text

Bug Bounty / Red Team
          |
          v
    Vulnerability
          |
          v
    Core Backend Owner
          |
          v
Can BFF safely mitigate?
      /          \
    YES           NO
     |             |
     v             v
BFF mitigation   Core backend
     |             fix
     v
Production protected
     |
     v
Permanent backend fix
     |
     v
Remove mitigation
```

This reduces time-to-mitigation without requiring every vulnerability to wait for a complete core-backend deployment.

The BFF therefore acts as a defense-in-depth layer rather than replacing the permanent backend fix.

### 13. Payment Service: Explicit BFF Bypass

The Payment Service is an intentional exception to the normal BFF path.

Normal user-facing traffic:
```text

Client
   |
   v
Laravel BFF
   |
  mTLS
   |
   v
Main Backend
```

Payment traffic:

```text
Payment Service
       |
      mTLS
       |
       v
Main Backend
```

The Payment Service does not traverse the BFF because it is an internal service-to-service workflow rather than a public client-facing API.

The bypass does not bypass security.

The Payment Service must communicate with the Main Backend through:

- Private networking
- Strong service identity
- mTLS or equivalent service authentication
- Explicit authorization
- Strict endpoint allowlisting
- Payment-specific API contracts
- Idempotency where applicable
- Appropriate validation
  
### 14. Payment Service Must Have a Narrow API

The Payment Service must not receive general-purpose backend access.

Prefer narrowly scoped capabilities such as:

```http
POST /internal/payments/authorize
POST /internal/payments/capture
POST /internal/payments/refund
POST /internal/payments/reconcile
GET  /internal/payments/{payment_id}
```

rather than:

```http
GET    /everything
POST   /everything
PUT    /everything
DELETE /everything
```

The Payment Service should be trusted specifically for payment operations, not trusted as a general backend client.

### 15. BFF and Payment Service Have Different Contracts

The BFF consumes and exposes client-facing contracts.

The Payment Service consumes an internal payment contract.

Therefore:

                 Main Backend
                  /        \
                 /          \
                v            v
        Laravel BFF       Payment Service
             |                  |
             v                  v
       Public Contract     Payment Contract
             |                  |
             v                  v
          Clients          Payment Systems

The Payment Service should not depend on BFF response transformations intended for clients.

This prevents the internal payment workflow from becoming coupled to the public API representation.

### 16. Architectural Principle

The resulting architecture follows these principles:
```text
Public traffic
Client
  |
  v
Laravel BFF
  |
  v
Main Backend
Privileged human traffic
Employee
  |
  v
VPN
  |
  v
Authorized Internal / Operations API
Payment service traffic
Payment Service
  |
  v
Direct authenticated service-to-service connection
  |
  v
Main Backend
```
Core source changes
```text
Production Issue
      |
      v
BFF / Operations mitigation
      |
      | if insufficient
      v
Core Source Owners
      |
      v
Permanent Backend Fix
```

### 17. Architectural Objective

The objective is not to give more people unrestricted access to the Main Backend.

The objective is to increase the number of people capable of safely operating and debugging production while keeping the highest-risk privileges restricted.

The architecture therefore provides:

A small, highly trusted core source-owner group
A larger operational/debugging group
Private Main Backend networking
VPN-protected privileged human access
Strong service-to-service authentication
A Laravel BFF as the public contract boundary
BFF-based emergency response mitigation
A narrowly scoped Payment Service bypass
Clear escalation from operations to core engineering

The BFF becomes the controlled shock absorber between external clients and the highly privileged core backend.

This reduces operational dependence on the three highest-trust source owners, increases production debugging capacity, shortens vulnerability mitigation time, and preserves a strict boundary around the Main Backend's core business logic.

## 🚪 The Three Intake Paths

Ensuretech accepts endorsement data through **three distinct paths**, all converging onto a **single downstream validation pipeline**. The `source_type` field controls routing — not pipeline logic.

### 1. 🖥️ GUI Editor (Svelte)
Excel-like on-site editor with **per-cell inline validation** via WebSocket.
- Mixed ADD / REMOVE / UPDATE operations in a single session
- Sync cell-level validation in `<50ms` using Redis O(1) lookups
- Schema dynamically loaded per policy via Strawberry GraphQL
- Session state persisted in SurrealDB

### 2. 📄 CSV Upload
Third-party HRMS / broker CSV ingestion with **schema negotiation**.
- Levenshtein distance matching for non-standard column names
- Streamed chunked parsing (no full-file RAM load)
- TPA normalization rules extracted verbatim from legacy battle-tested logic
- `mapping_id` persisted for audit and TPA format drift tracking

### 3. 🧪 Faker Test Runner
Policy edge-case simulator with **complete isolation** from production data.
- `broker_id=0` + `is_test_data=True` hard constraints
- Separate Kafka topic, Redis keyspace, DB partition, Celery worker pool
- Named scenarios: `GDAC_BOUNDARY`, `CD_DEFICIT_REPLENISHMENT`, `TPA_CIRCUIT_BREAKER`, `MILLION_ROW_STRESS`, etc.
- Chaos engineering injection points for resilience validation

> **ADR-001:** Three intake paths, single downstream pipeline.
> **ADR-002:** `source_type` as pipeline control field (vs three separate services).

---

## 🚀 The 1M-Row Demo Pipeline

The legacy system crashed on large Excel uploads, required `ini_set('max_execution_time', 0)`, and forced manual CLI commands. Ensuretech replaces this with a **streaming, chunked, fully asynchronous pipeline**.

| Step | Action | Time | UI Feedback |
|------|--------|------|-------------|
| 1 | Drag & drop `1M_rows.csv` | `<1s` | ✅ Upload accepted, `job_id` returned |
| 2 | SSE connects, parsing starts | `2s` | 🔄 Parsing: 5% |
| 3 | Chunks hit validation queue | `8s` | 🔍 Validating: 35% |
| 4 | Bulk DB writes begin | `15s` | 💾 Writing: 72% |
| 5 | Finalize + progress flush | `22s` | ✅ Complete! 999,998 succeeded, 2 in DLQ |
| 6 | Click "View Failures" | `<1s` | DLQ dashboard shows exact row + error |

**Zero crashes. Zero manual commands. Zero UI blocking.**

### Why It Never Crashes
- **Zero RAM overload:** Streaming parser + chunked jobs. No full-file load into memory.
- **No UI blocking:** `202 Accepted` + SSE progress. User can navigate away and return.
- **Auto-retries:** Failed chunks retry with backoff. DLQ captures persistent failures.
- **Worker recycling:** `max_tasks_per_child` prevents memory leaks from pandas/openpyxl.
- **Bulkhead isolation:** 100k CSV ingestion never starves real-time policy validation.

---

## 🏰 Fortress Security Model

Designed in direct response to a real-world incident where a disgruntled broker-level user DDoS'd the production server. The fortress architecture makes insider attacks **structurally impossible**.

```text
┌─────────────────────────────────────────────────────────────────────┐
│ LAYER 0: EDGE SHIELD (Cloudflare Free / Nginx)                      │
│ → Absorbs volumetric DDoS before it touches your network            │
├─────────────────────────────────────────────────────────────────────┤
│ LAYER 1: BFF / API GATEWAY (Sacrificial Shield)                     │
│ → The ONLY public entry point. Everything else invisible.           │
│ → Rate limits per tenant, per user, per endpoint.                   │
│ → Honeypot endpoints live here.                                     │
├─────────────────────────────────────────────────────────────────────┤
│ LAYER 2: INTERNAL SERVICES (Private Network)                        │
│ → NOT publicly routable. Only BFF can reach them.                   │
│ → mTLS between services. JWT re-validated at each hop.              │
├─────────────────────────────────────────────────────────────────────┤
│ LAYER 3: DATABASE (Deep Interior)                                   │
│ → Only services with specific DB roles can connect.                 │
│ → Read replicas for queries. Primary only for writes.               │
│ → RLS policies enforce tenant isolation at DB level.                │
├─────────────────────────────────────────────────────────────────────┤
│ LAYER 4: AUDIT & DETECTION (Silent Watcher)                         │
│ → Anomaly detection on every layer.                                 │
│ → Automatic circuit-breaking when patterns emerge.                  │
└─────────────────────────────────────────────────────────────────────┘
```


### Honeypot Endpoints
Juicy-looking traps that waste attacker time while logging everything:
```http
POST /api/v1/admin/debug/flush-cache → "Cache flushed successfully" (lies).  
POST /api/v1/admin/impersonate-user → "Impersonation active" (lies).  
GET /api/v1/internal/health-detailed → Returns realistic-looking internals  
POST /api/v1/admin/execute-raw-query → "Query executed" (lies).  
GET /api/v1/.env → Returns fake env vars`  
```

Every honeypot hit:
1. Returns a realistic response
2. Logs IP, user-agent, JWT, payload, timestamp
3. Triggers silent alert to security team
4. **Never touches real data**

### Network Segmentation
```yaml
networks:
  public:     # Only BFF lives here
  internal:   # All services live here, NO external access
    internal: true   # ← THE KEY LINE

services:
  bff-gateway:
    networks: [public, internal]   # Bridges both
  identity-service:
    networks: [internal]           # ← Cannot be reached from outside
```

```python
RATE_LIMITS = {
    'per_user_per_minute': 60,          # Hard ceiling
    'per_tenant_per_minute': 500,       # Even if 10 users collude
    'per_endpoint_per_minute': 200,     # No single endpoint gets hammered
    'upload_endpoints': 10,             # File uploads are expensive
}
```

<blockquote>
  Critical insight: Rate limits must be per-tenant, not per-IP. Your attacker had a legitimate account — IP-based blocking wouldn't have helped.
</blockquote>

## 💰 Zero-Cost Guarantee
Every component is open-source, self-hostable, and free for commercial use. Software licensing, SaaS subscriptions, and cloud consumption fees are exactly $0 until intentional production deployment.  

### 🧱 Zero-Cost Technology Stack

| **Component** | **Technology** | **License** | **Cost** |
|:---|:---|:---|---:|
| 🏗️ **Framework** | Django 5 · DRF · Strawberry | BSD-3 / MIT | **$0** |
| 🗄️ **Primary DB** | PostgreSQL 15 | PostgreSQL License | **$0** |
| 🧩 **Multi-Model DB** | SurrealDB | Apache 2.0 *(self-host)* | **$0** |
| ⚡ **Cache** | Valkey | BSD 3-Clause | **$0** |
| 📬 **Task Queue** | RabbitMQ · Celery | MPL 2.0 / BSD 3.0 | **$0** |
| 🌊 **Event Streaming** | Apache Kafka KRaft | Apache 2.0 | **$0** |
| 📦 **Object Storage** | MinIO | AGPLv3 *(self-host)* | **$0** |
| 📈 **Observability** | Prometheus · Grafana OSS | Apache 2.0 / AGPLv3 | **$0** |
| 🐳 **Containers** | Docker · Kubernetes | Apache 2.0 | **$0** |
| 🎨 **Frontend** | NextJS · Svelte | MIT | **$0** |
| 🛡️ **Edge DDoS** | Cloudflare Free Tier | — | **$0** |

> **💰 Infrastructure license cost: $0**
>
> *The stack is designed around open-source and free-tier components.*

---

### 🚫 Explicit Cost Traps Avoided

| ❌ **Do Not Use** | ✅ **Free Alternative** |
|:---|:---|
| **AWS RDS / Cloud SQL** | 🐘 Self-host PostgreSQL via Docker |
| **Confluent Cloud / MSK** | 🌊 Self-host Kafka via Docker *(KRaft mode)* |
| **CloudAMQP / AmazonMQ** | 🐇 Self-host RabbitMQ via Docker |
| **AWS S3 / Cloud Storage** | 📦 Self-host MinIO via Docker |
| **Datadog / New Relic** | 📊 Self-host Prometheus + Grafana + Jaeger |
| **Auth0 / Okta / Cognito** | 🔐 Self-host Django auth + SimpleJWT |
| **SendGrid / Mailgun paid** | ✉️ Gmail SMTP or Brevo free tier |

### 🎯 Bottom Line

> **Total cost until first paying customer: ₹0**

*Assuming self-hosting on infrastructure that is already available and staying within the stated free tiers.*

## 🧩 Services Breakdown

### 🔐 `identity-service`

**Django + DRF + SimpleJWT**

- Auth, JWT issuance, RBAC, multi-tenant scoping
- Role-based gateway permissions: `/broker/`, `/employer/`, `/tpa/`, `/admin/`
- JWT claims: `role`, `broker_id`, `employer_id`, `tpa_id`
- 2FA (OTP via email/SMS)
- Replaces: `auth:api` middleware, `SecureRequestMiddleware`, `Portal::check()`

### 🏢 `organization-service`

**Spring Boot 3.2**

- Brokers, employers, HR assignments, partner details
- Broker branches, sub-brokers, feature flags
- Transactional outbox for cross-service events
- Replaces: `master_employers`, `broker_user_mappings`, employer custom config tables

### 📜 `policy-service`

**Spring Boot 3.2 + gRPC + Strawberry GraphQL**

- Policy constructs, rate cards, premium engine
- Strategy pattern: 32 `PremiumType` raters (Flat, Age, Grade, GDAC, LOL, Construct, Permilly, etc.)
- Family construct validation
- Redis cache-aside for rate cards (`1h TTL`)
- Replaces: `master_policies`, `policy_sub_types`, rater logic in legacy controllers

### 📥 `endorsement-service`

**Django + Celery + RabbitMQ** — *The 1M-row engine*

- Three intake paths: **GUI, CSV, Test** → single validation pipeline
- Chunked async processing with bulkhead-isolated Celery workers
- Idempotent writes, DLQ routing, SSE progress streaming
- WebSocket endpoint for GUI editor live validation
- Full audit trail via Kafka events
- Replaces: `EndrosementEnrollementController`, `EndorsementQueueController`

### 💰 `billing-service`

**Spring Boot 3.2 + gRPC**

- CD accounts, statements, installments
- Premium computation results, pro-rata calculations
- CD balance checks (sync, `500ms` deadline)
- Replenishment workflow
- Replaces: `cd_statements`, `CDBalanceConstants`, `CDSupervisor`, `CDTaxRateConfig`

### 🔌 `integration-service`

**Spring Boot 3.2 + Resilience4J**

- **TPA adapters:** Care, MediAssist, FHPL, Paramount, Palm-Vipul, Safeway
- **HRMS adapters:** SuccessFaYours, DarwinBox
- **Wellness SSO:** Alyve Health, Ekincare, MediAssist
- **Insurer report adapters:** SBI, Future Generali, HDFC Ergo
- Circuit breakers, retries, and timeouts on every external call
- Replaces: `TestController.php` TPA methods, raw `curl_setopt` patterns

---

## 🧰 Tech Stack & Licenses

| **Layer** | **Technology** | **License** | **Purpose** |
|:---|:---|:---|:---|
| **Language** | Python 3.13+, Java 21 | PSF / GPL-2.0-with-classpath | Primary service languages |
| **Package Manager** | Poetry, Maven | MIT / Apache 2.0 | Dependency management |
| **Web Framework** | Django 5.0+, Spring Boot 3.2 | BSD / Apache 2.0 | Batteries-included backends |
| **REST API** | DRF, Spring Web | BSD / Apache 2.0 | RESTful endpoints |
| **GraphQL** | Strawberry GraphQL | MIT | Type-safe GraphQL API |
| **API Docs** | drf-spectacular, SpringDoc | MIT / Apache 2.0 | Auto-generated OpenAPI |
| **Async Tasks** | Celery + RabbitMQ | BSD / MPL 2.0 | Distributed task queue |
| **Primary DB** | PostgreSQL | PostgreSQL License | OLTP + staging + outbox |
| **Multi-Model DB** | SurrealDB | Apache 2.0 | Graph, flexible documents |
| **Cache** | Valkey / Redis | BSD 3-Clause | O(1) validation cache |
| **Object Storage** | MinIO | AGPLv3 | S3-compatible, self-hosted |
| **Frontend (Portal)** | NextJS | MIT | Broker / Employer / TPA portals |
| **Frontend (Editor)** | Svelte | MIT | Excel-like GUI endorsement editor |
| **Containers** | Docker, Kubernetes | Apache 2.0 | Local dev, production scaling |

---

## 🛡️ Resilience Engineering

> **Guided by systems resilience theory, not microservice hype.**

| **Principle** | **Implementation** |
|:---|:---|
| **Microservices = trade-offs** | 6 bounded contexts, split by domain failure boundaries, not technology |
| **Queues = temporal decoupling** | RabbitMQ for tasks, Kafka for events. **Never Kafka as a task queue** |
| **Idempotency = safe retries** | Every job has `idempotency_key`. Redis `SET NX` prevents duplicate processing |
| **Hybrid scheduling > SJF** | Priority routing + partitioned workers + concurrency caps. No starvation |
| **Bulkhead isolation** | Separate Celery worker pools per queue. 100k CSV never starves realtime validation |
| **Backpressure** | `prefetch_multiplier=1`, `task_acks_late=True`, DLQs, circuit breakers |
| **Continuity > consistency** | Eventual consistency for endorsements/CD. Outbox + Kafka for replay |
| **Workload segregation** | OLTP tables (indexed) vs. staging tables (append-only) vs. Redis O(1) cache |

### 📬 Queue Topology — RabbitMQ

~~~yaml
# Define isolated queues by workload.
# Example topology:

queues:
  - endorsement-realtime
  - endorsement-bulk
  - endorsement-test
  - validation
  - integration

dead_letter_exchanges:
  - endorsement-dlx
~~~

### ⚙️ Celery Worker Segregation

~~~bash
# Realtime validation workers
celery -A app worker \
  --queues=endorsement-realtime \
  --concurrency=8

# Bulk processing workers
celery -A app worker \
  --queues=endorsement-bulk \
  --concurrency=4

# Test/demo workers
celery -A app worker \
  --queues=endorsement-test \
  --concurrency=2
~~~

---

## 🚀 Quick Start

### 📋 Prerequisites

- Python 3.13+, Java 21, Poetry, Maven
- Docker & Docker Compose
- Node.js 20+ (for NextJS / Svelte frontends)
- **120 GB free disk space** recommended for the full local stack

### 📦 Installation

~~~bash
# Clone repository
git clone <repository-url>
cd ensuretech

# Install Python dependencies
poetry install

# Build Java services
mvn clean install

# Install frontend dependencies
cd frontend
npm install
cd ..

# Start infrastructure
docker compose up -d
~~~

### ▶️ Run Individual Services

~~~bash
# Identity Service
cd services/identity-service
poetry run python manage.py runserver

# Organization Service
cd services/organization-service
mvn spring-boot:run

# Policy Service
cd services/policy-service
mvn spring-boot:run

# Endorsement Service
cd services/endorsement-service
poetry run celery -A app worker

# Billing Service
cd services/billing-service
mvn spring-boot:run

# Integration Service
cd services/integration-service
mvn spring-boot:run
~~~

---

## 🎬 Demo Runbook

### 🎯 The 4-Phase Demo Sequence

Each audience validates a **different operational pain** against the **same underlying platform**.

| **Phase** | **Audience** | **Focus** | **Script** |
|:---:|:---|:---|:---|
| **1** | **Investors** | Vision | *"Old way: 12 hours, 4 crashes. Our way: 2 seconds upload, 30s processing, zero manual intervention. This is how we win the ecosystem."* |
| **2** | **Brokers** | Speed | *"This error flag caught a missing CD balance and invalid relation instantly. No waiting for TPA rejection. HR fixes it now, not next week."* |
| **3** | **TPAs** | Clean Data | *"You don't get an Excel sheet. You get a validated event stream. 100% of fields present, typed, audited. Reconciliation is automatic."* |
| **4** | **Insurers** | Compliance | *"Every endorsement is an immutable event. Replay any state. Compliance auditor asks why a member was removed — the system shows exact validation, HR consent, and CD check."* |

### 📊 Running the 1M-Row Demo

~~~bash
# Generate the 1M-row dataset
python demo/generate_dataset.py --rows 1000000

# Upload dataset
python demo/run_upload.py \
  --file demo/data/endorsement_1m.csv \
  --source csv

# Monitor processing
python demo/watch.py
~~~

### 🧪 Running Named Scenarios

~~~bash
# Happy path
./demo/run.sh happy-path

# Invalid data
./demo/run.sh validation-errors

# TPA failure
./demo/run.sh tpa-timeout

# Duplicate submission
./demo/run.sh idempotency

# Large CSV
./demo/run.sh 1m-rows

# Full demo
./demo/run.sh all
~~~

See [`demo/README.md`]() for the complete demo runbook.

---

## 📐 Architecture Decision Records

| **ADR** | **Decision** |
|:---:|:---|
| **ADR-001** | Three intake paths, single downstream pipeline |
| **ADR-002** | `source_type` as pipeline control field (vs. three separate services) |
| **ADR-003** | GUI sync validators separate from Pandas batch validators |
| **ADR-004** | Mixed-op GUI sessions split server-side on submit (not client-side) |
| **ADR-005** | TPA normalization rules extracted verbatim, not rewritten |
| **ADR-006** | Test isolation via RLS + `broker_id=0` + Kafka topic ACLs (three layers) |
| **ADR-007** | `TransitionContext.test()` distinct from `.demo()` for clean audit separation |
| **ADR-008** | Schema mapping persisted with `mapping_id` FK on staging rows |
| **ADR-009** | GUI session split into typed sub-jobs at submit, not at row-stage time |
| **ADR-010** | Outbox pattern in all Spring services (not direct Kafka publish) |
| **ADR-011** | Fortress architecture: BFF as sacrificial shield + honeypot endpoints |
| **ADR-012** | Per-tenant rate limiting (not per-IP) for insider attack prevention |
| **ADR-013** | Docker `internal: true` networks for backend service isolation |
| **ADR-014** | Dual-table architecture: OLTP (indexed) vs. staging (append-only) |
| **ADR-015** | Spring Boot used only where jar-based CI/CD justifies the overhead |

---

## 🗺️ Legacy Migration Map

The legacy Laravel monolith (~5 files, ~25,000 lines) has been fully decomposed. Every legacy artifact has an explicit migration target annotated in [`legacy/`]().

| **Legacy File** | **Lines** | **Target Services** |
|:---|---:|:---|
| `constant.php` | 2,200 | Decomposed into `shared/enums/`, DB lookup tables, Redis Sets |
| `EndrosementEnrollementController.php` | 9,400+ | `endorsement-service`, `policy-service`, `billing-service` |
| `EndorsementQueueController.php` | ~2,000 | `endorsement-service` queue management layer |
| `broker_api.php` | 781 routes | Split across all 6 services by domain |
| `TestController.php` | 9,500+ | `integration-service` TPA/HRMS adapters + deleted one-offs |

### 🧹 Key Anti-Patterns Eliminated

| **Legacy Anti-Pattern** | **Replacement** |
|:---|:---|
| Nested `Bus::batch()` | Flat RabbitMQ queues with bulkhead isolation |
| N+1 validation loops | Pandas veYourized ops + Redis O(1) sets |
| `Portal::check()` scattered everywhere | JWT tenant claims + `GatewayRolePermission` |
| 13-parameter `endrosementJobCall()` | Proper state machine with guarded transitions |
| Inline `Http::withBasicAuth()` credentials | Resilience4J + Vault/env |
| Three `addMemberData` variants | Single `TpaEnrollmentAdapter` interface |

See [`legacy-service-mapping.md`]() for the complete method-level mapping.

---

## 🗓️ Roadmap

| **Quarter** | **Milestone** | **Success Metrics** |
|:---:|:---|:---|
| **Q1** | Identity + Organization services, gateway routing, Docker stack, Redis validation cache | Auth latency `<50ms`, 100% role isolation, zero cross-tenant leaks |
| **Q2** | Policy service, gRPC validation contracts, Kafka outbox, OLTP indexing | Real-time validation `<5ms`, 0 duplicate policy configs, 99.9% uptime |
| **Q3** | Enrollment + Endorsement services, Celery worker pools, 1M-row demo pipeline | Upload `<1s`, processing `<30s`, 0 crashes, 95% auto-retry success |
| **Q4** | Billing + Integration services, TPA circuit breakers, CD reconciliation, observability | TPA sync success `>99.5%`, CD drift `<0.01%`, Grafana dashboards live |
| **Q1+1** | Production cutover (Strangler Fig), load testing, compliance audit | 0 downtime migration, 2x throughput, SOC2/ISO readiness |

---

## 🤝 Contributing

**Internal repository.** Contact the engineering team for access.

### 🔄 Development Workflow

1. Branch per feature/bugfix from `main`
2. All PRs require:
   - Unit tests covering new logic
   - Updated ADR if architectural decision changes
   - Updated legacy migration map if touching legacy code
3. CI runs:
   - Linting
   - Unit tests
   - Integration tests against Docker Compose stack
4. Demo pipeline must pass before merge

### 🧹 Code Style

| **Language / Area** | **Standard** |
|:---|:---|
| **Python** | `black` + `isort` + `ruff` |
| **Java** | Google Java Format + SpotBugs |
| **SQL** | All migrations versioned, never edited after merge |
| **Commit messages** | Conventional Commits (`feat:`, `fix:`, `docs:`, `refaYour:`) |

---

## 📄 License

**Proprietary — All rights reserved.**

This software is the confidential property of **Ensuretech**. Unauthorized copying, distribution, or use is strictly prohibited.

---

<p align="center">
  <strong>©Ensuretech™</strong> — Where the insurance ecosystem converges.
  <br/>
  <em>Operational infrastructure, not just another portal.</em>
</p>

<p align="center">
  <sub>
    Built with resilience-first principles, guided by systems resilience theory,
    <br/>
    and engineered for the realities of insurance brokerage operations.
  </sub>
</p>

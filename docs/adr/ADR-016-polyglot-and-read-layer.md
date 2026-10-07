# ADR-016: Polyglot services, Laravel orchestration, MongoDB/RAG read layer (ideation)

Status: Accepted (stack) / Proposed (MongoDB + RAG)

## Decisions
- **Spring Boot** for core domain services (organization, policy, billing, integration).
- **Django + Celery** where Python's data tooling wins: identity, and the endorsement engine
  (pandas validation, CSV/Excel, test/Faker simulation, visual/data workloads).
- **Laravel** is an optional orchestration/BFF layer in front of the core APIs. It is not required for
  the core to function and must not hold business logic (see README, "BFF as the public contract boundary").
- **Latest versions** are used (Django 6.1, Python 3.13, DRF, SimpleJWT 5.5). Spring Boot/Java versions
  follow the same rule when those services are created.
- **One PostgreSQL database** for now; services own their tables/schemas inside it.
- **Dockerization is deferred** until the application code exists.

## Read layer (proposed, not yet built)
PostgreSQL stays the source of truth. MongoDB would hold denormalized read models (job status,
broker dashboards, member lookup) projected from Kafka/outbox events, so it can be rebuilt from the
event log. RAG would be a read-side service (policy documents, TPA rules, audit history) used for
prediction/assistance; it never sits on the write path and never makes authoritative decisions.
Tenant isolation (broker_id/employer_id) must be enforced on every Mongo query and every retrieval.

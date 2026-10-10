# AWS cost estimate

Rough monthly cost of running this platform on AWS. These are planning figures, not a quote: they were not checked against the AWS pricing calculator, and they assume on-demand pricing in the Mumbai or Virginia regions. Re-price before committing.

**Assumed size:** 6 Spring services, 4 Django services, the Laravel BFF, 3 Celery worker groups plus beat, Postgres, Redis, Kafka, RabbitMQ, MongoDB for the read layer, and light use of the RAG assistant.

| Item | Lean pilot (single AZ) | Production (multi-AZ) | Growth |
|---|---|---|---|
| Compute, about 15 containers on ECS Fargate (0.25-1 vCPU each) | $200 | $300 | $800+ |
| Postgres on RDS (one instance, one database per service) | $50 (`db.t4g.medium`) | $120 (Multi-AZ) | $400+ |
| Redis on ElastiCache (Celery broker and cache) | $15 | $50 | $120 |
| Kafka on MSK (smallest brokers) | $0 (run without it for now) | $110 | $300+ |
| RabbitMQ on Amazon MQ | $0 (Redis as the broker) | $50 | $120 |
| MongoDB (Atlas M10 or DocumentDB) | $60 | $90 | $250 |
| Load balancer, NAT gateway, WAF | $60 | $90 | $200 |
| Logs, metrics, S3, Secrets Manager, backups | $40 | $70 | $200 |
| Claude API for the assistant | $5-20 | $20-100 | $100+ |
| **Total per month** | **about $450** | **about $700-1,000** | **about $2,000-3,500** |

## What drives the cost
- **Compute** is the biggest line. The Spring JVMs are memory-hungry (about 1 GB each), so the container count matters more than traffic.
- **Kafka on MSK** is the most avoidable cost. MSK Serverless is considerably more expensive at this scale; avoid it.
- **The NAT gateway** adds a fixed charge plus data transfer. VPC endpoints for S3 and ECR reduce it.
- **Observability** (Prometheus, Grafana, Flower, Spring Boot Admin) costs little to run, but keep Flower and the admin console internal-only.

## Ways to cut it
- Put several small Spring services on one Fargate task or EC2 host.
- Use Savings Plans or reserved instances once load is steady (about 30-40% saving).
- Keep a single Postgres instance until a service needs its own.
- Skip Kafka and RabbitMQ until needed; Redis already covers Celery.

## To narrow it to one number
Decide the region, the expected number of brokers and employers, and the uptime requirement, then price it in the AWS calculator.

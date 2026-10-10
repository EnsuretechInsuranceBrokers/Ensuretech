package com.broker.integration.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "sync_jobs")
public class SyncJob {
    public enum Status { PENDING, IN_PROGRESS, SUCCEEDED, DEAD, SKIPPED, CANCELLED }

    @Id private UUID id = UUID.randomUUID();
    @Column(name = "event_id", nullable = false, updatable = false) private UUID eventId;
    @Column(name = "endorsement_job_id", nullable = false, updatable = false) private UUID endorsementJobId;
    @Column(name = "broker_id", nullable = false, updatable = false) private Long brokerId;
    @Column(name = "employer_id", nullable = false, updatable = false) private Long employerId;
    @Column(name = "policy_id", nullable = false, updatable = false) private Long policyId;
    @Column(name = "tpa_id") private Short tpaId;
    @Column(name = "op_type", nullable = false, updatable = false) private String opType;
    @Column(name = "members_expected", nullable = false, updatable = false) private int membersExpected;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM) @Column(nullable = false) private Status status = Status.PENDING;
    @Column(nullable = false) private int attempts;
    @Column(name = "next_attempt_at", nullable = false) private Instant nextAttemptAt = Instant.now();
    @Column(name = "lease_until") private Instant leaseUntil;
    @Column(name = "cursor_id", nullable = false) private long cursorId;
    @Column(name = "synced_count", nullable = false) private int syncedCount;
    @Column(name = "rejected_count", nullable = false) private int rejectedCount;
    @Column(name = "last_error") private String lastError;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @Column(name = "completed_at") private Instant completedAt;

    protected SyncJob() {}

    public SyncJob(UUID eventId, UUID endorsementJobId, Long brokerId, Long employerId, Long policyId, Short tpaId, String opType, int membersExpected) {
        this.eventId = eventId; this.endorsementJobId = endorsementJobId; this.brokerId = brokerId; this.employerId = employerId;
        this.policyId = policyId; this.tpaId = tpaId; this.opType = opType; this.membersExpected = membersExpected;
    }

    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public UUID getEndorsementJobId() { return endorsementJobId; }
    public Long getBrokerId() { return brokerId; }
    public Long getEmployerId() { return employerId; }
    public Long getPolicyId() { return policyId; }
    public Short getTpaId() { return tpaId; }
    public String getOpType() { return opType; }
    public int getMembersExpected() { return membersExpected; }
    public Status getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public long getCursorId() { return cursorId; }
    public int getSyncedCount() { return syncedCount; }
    public int getRejectedCount() { return rejectedCount; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }

    public void setTpaId(Short tpaId) { this.tpaId = tpaId; touch(); }
    public void setStatus(Status s) { this.status = s; touch(); }
    public void setAttempts(int a) { this.attempts = a; touch(); }
    public void setNextAttemptAt(Instant t) { this.nextAttemptAt = t; touch(); }
    public void setLeaseUntil(Instant t) { this.leaseUntil = t; touch(); }
    public void setLastError(String e) { this.lastError = e == null ? null : e.substring(0, Math.min(e.length(), 2000)); touch(); }
    public void setCompletedAt(Instant t) { this.completedAt = t; touch(); }
    public void progress(long cursor, int synced, int rejected) { this.cursorId = cursor; this.syncedCount += synced; this.rejectedCount += rejected; touch(); }
    private void touch() { this.updatedAt = Instant.now(); }
}

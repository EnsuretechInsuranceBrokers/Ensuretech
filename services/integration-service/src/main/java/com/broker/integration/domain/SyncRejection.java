package com.broker.integration.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sync_rejections")
public class SyncRejection {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "sync_job_id", nullable = false, updatable = false) private UUID syncJobId;
    @Column(name = "member_id", nullable = false, updatable = false) private Long memberId;
    @Column(nullable = false, updatable = false) private String reason;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();

    protected SyncRejection() {}

    public SyncRejection(UUID syncJobId, Long memberId, String reason) {
        this.syncJobId = syncJobId; this.memberId = memberId; this.reason = reason.substring(0, Math.min(reason.length(), 500));
    }

    public Long getMemberId() { return memberId; }
    public String getReason() { return reason; }
}

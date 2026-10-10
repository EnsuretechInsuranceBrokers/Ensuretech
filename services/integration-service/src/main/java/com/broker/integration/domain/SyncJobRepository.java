package com.broker.integration.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SyncJobRepository extends JpaRepository<SyncJob, UUID> {
    Optional<SyncJob> findByEventId(UUID eventId);
    Optional<SyncJob> findByIdAndBrokerId(UUID id, Long brokerId);
    Page<SyncJob> findByBrokerId(Long brokerId, Pageable p);
    Page<SyncJob> findByBrokerIdAndStatus(Long brokerId, SyncJob.Status status, Pageable p);
    Page<SyncJob> findByBrokerIdAndPolicyId(Long brokerId, Long policyId, Pageable p);

    /** The next due job, claimed row-locked and skipping rows other workers hold. Also reclaims jobs whose worker died (lease expired). */
    @Query(value = """
            SELECT * FROM integration.sync_jobs
            WHERE (status = 'PENDING' AND next_attempt_at <= :now)
               OR (status = 'IN_PROGRESS' AND lease_until < :now)
            ORDER BY next_attempt_at LIMIT 1 FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<SyncJob> claimDue(Instant now);
}

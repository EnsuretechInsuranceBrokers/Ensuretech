package com.broker.integration.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncRejectionRepository extends JpaRepository<SyncRejection, Long> {
    List<SyncRejection> findBySyncJobIdOrderByMemberId(UUID syncJobId);
    boolean existsBySyncJobIdAndMemberId(UUID syncJobId, Long memberId);
}

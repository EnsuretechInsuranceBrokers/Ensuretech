package com.broker.billing.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReplenishmentRequestRepository extends JpaRepository<ReplenishmentRequest, Long> {
    List<ReplenishmentRequest> findByAccountIdOrderByIdDesc(Long accountId);
    Optional<ReplenishmentRequest> findByIdAndAccountId(Long id, Long accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReplenishmentRequest r where r.id = :id and r.accountId = :accountId")
    Optional<ReplenishmentRequest> lockByIdAndAccountId(@Param("id") Long id, @Param("accountId") Long accountId);
}

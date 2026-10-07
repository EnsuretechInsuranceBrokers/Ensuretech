package com.broker.billing.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CdAccountRepository extends JpaRepository<CdAccount, Long> {
    Optional<CdAccount> findByPolicyIdAndBrokerId(Long policyId, Long brokerId);
    List<CdAccount> findByBrokerIdOrderByPolicyId(Long brokerId);
    List<CdAccount> findByBrokerIdAndEmployerIdOrderByPolicyId(Long brokerId, Long employerId);

    /** Row lock: every money-moving operation on an account serialises here. Tenant check included. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from CdAccount a where a.policyId = :policyId and a.brokerId = :brokerId")
    Optional<CdAccount> lockByPolicyAndBroker(@Param("policyId") Long policyId, @Param("brokerId") Long brokerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from CdAccount a where a.id = :id")
    Optional<CdAccount> lockById(@Param("id") Long id);
}

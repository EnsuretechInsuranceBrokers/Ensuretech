package com.broker.billing.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CdAccountRepository extends JpaRepository<CdAccount, Long> {
    /** The account that funds the policy (its own, or the pool it belongs to), tenant-checked. */
    @Query(value = "SELECT a.* FROM billing.cd_accounts a JOIN billing.cd_account_policies l ON l.account_id = a.id WHERE l.policy_id = :policyId AND a.broker_id = :brokerId",
            nativeQuery = true)
    Optional<CdAccount> findByPolicyIdAndBrokerId(@Param("policyId") Long policyId, @Param("brokerId") Long brokerId);
    List<CdAccount> findByBrokerIdOrderById(Long brokerId);
    List<CdAccount> findByBrokerIdAndEmployerIdOrderById(Long brokerId, Long employerId);
    Optional<CdAccount> findByIdAndBrokerId(Long id, Long brokerId);

    /** Row lock: every money-moving operation on an account serialises here. Tenant check included. */
    @Query(value = "SELECT a.* FROM billing.cd_accounts a JOIN billing.cd_account_policies l ON l.account_id = a.id WHERE l.policy_id = :policyId AND a.broker_id = :brokerId FOR UPDATE OF a",
            nativeQuery = true)
    Optional<CdAccount> lockByPolicyAndBroker(@Param("policyId") Long policyId, @Param("brokerId") Long brokerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from CdAccount a where a.id = :id")
    Optional<CdAccount> lockById(@Param("id") Long id);
}

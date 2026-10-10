package com.broker.policy.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RateCardRepository extends JpaRepository<RateCard, Long> {
    Optional<RateCard> findByPolicyIdAndBrokerIdAndActiveTrue(Long policyId, Long brokerId);
    /** Cheap indexed pointer lookup; the (immutable) card body is cached by id. Tenant check lives here. */
    @Query("select r.id from RateCard r where r.policyId = :policyId and r.brokerId = :brokerId and r.active = true")
    Optional<Long> findActiveCardId(@Param("policyId") Long policyId, @Param("brokerId") Long brokerId);

    List<RateCard> findByPolicyIdAndBrokerIdOrderByIdDesc(Long policyId, Long brokerId);
}

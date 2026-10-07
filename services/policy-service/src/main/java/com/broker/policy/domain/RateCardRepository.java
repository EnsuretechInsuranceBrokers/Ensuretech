package com.broker.policy.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateCardRepository extends JpaRepository<RateCard, Long> {
    Optional<RateCard> findByPolicyIdAndBrokerIdAndActiveTrue(Long policyId, Long brokerId);
    List<RateCard> findByPolicyIdAndBrokerIdOrderByIdDesc(Long policyId, Long brokerId);
}

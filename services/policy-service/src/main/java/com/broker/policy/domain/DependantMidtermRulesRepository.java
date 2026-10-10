package com.broker.policy.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DependantMidtermRulesRepository extends JpaRepository<DependantMidtermRules, Long> {
    Optional<DependantMidtermRules> findByPolicyIdAndBrokerId(Long policyId, Long brokerId);
}

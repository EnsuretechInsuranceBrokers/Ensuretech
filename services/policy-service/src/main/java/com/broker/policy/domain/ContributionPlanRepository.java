package com.broker.policy.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContributionPlanRepository extends JpaRepository<ContributionPlan, Long> {
    Optional<ContributionPlan> findByPolicyIdAndBrokerId(Long policyId, Long brokerId);
}

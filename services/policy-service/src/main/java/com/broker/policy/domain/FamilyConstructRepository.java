package com.broker.policy.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FamilyConstructRepository extends JpaRepository<FamilyConstruct, Long> {
    Optional<FamilyConstruct> findByPolicyIdAndBrokerId(Long policyId, Long brokerId);
}

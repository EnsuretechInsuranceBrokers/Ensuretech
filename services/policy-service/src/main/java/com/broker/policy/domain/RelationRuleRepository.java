package com.broker.policy.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RelationRuleRepository extends JpaRepository<RelationRule, Long> {
    List<RelationRule> findByPolicyIdOrderByRelationCode(Long policyId);
    void deleteByPolicyId(Long policyId);
}

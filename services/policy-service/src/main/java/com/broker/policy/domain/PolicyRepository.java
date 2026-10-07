package com.broker.policy.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant data is only reachable with a broker_id; there is no bare findById use for policies. */
public interface PolicyRepository extends JpaRepository<Policy, Long> {
    Optional<Policy> findByIdAndBrokerId(Long id, Long brokerId);
    Optional<Policy> findByIdAndBrokerIdAndEmployerId(Long id, Long brokerId, Long employerId);
    Page<Policy> findByBrokerIdAndTestDataFalse(Long brokerId, Pageable p);
    Page<Policy> findByBrokerIdAndEmployerIdAndTestDataFalse(Long brokerId, Long employerId, Pageable p);
    Page<Policy> findByBrokerIdAndEmployerIdAndStatusAndTestDataFalse(Long brokerId, Long employerId, PolicyStatus s, Pageable p);
    boolean existsByBrokerIdAndPolicyNumber(Long brokerId, String policyNumber);
    List<Policy> findByStatusAndEndDateBefore(PolicyStatus status, LocalDate date);
}

package com.broker.policy.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployerRefRepository extends JpaRepository<EmployerRef, Long> {
    Optional<EmployerRef> findByEmployerIdAndBrokerId(Long employerId, Long brokerId);
}

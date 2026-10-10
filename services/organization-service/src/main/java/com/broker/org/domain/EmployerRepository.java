package com.broker.org.domain;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Every tenant-owned lookup takes broker_id; there is deliberately no bare findById for tenant data. */
public interface EmployerRepository extends JpaRepository<Employer, Long> {
    Optional<Employer> findByIdAndBrokerId(Long id, Long brokerId);
    Page<Employer> findByBrokerIdAndTestDataFalse(Long brokerId, Pageable p);
    Page<Employer> findByBrokerIdAndHiddenFalseAndTestDataFalse(Long brokerId, Pageable p);
    java.util.List<Employer> findByBrokerIdAndParentEmployerIdOrderById(Long brokerId, Long parentEmployerId);
    boolean existsByBrokerIdAndParentEmployerId(Long brokerId, Long parentEmployerId);
    boolean existsByBrokerIdAndEmployerCode(Long brokerId, String employerCode);
}

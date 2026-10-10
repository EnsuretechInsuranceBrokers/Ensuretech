package com.broker.org.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployerConfigRepository extends JpaRepository<EmployerConfig, Long> {
    List<EmployerConfig> findByBrokerIdAndEmployerIdOrderByConfigKey(Long brokerId, Long employerId);
    Optional<EmployerConfig> findByBrokerIdAndEmployerIdAndConfigKey(Long brokerId, Long employerId, String key);
}

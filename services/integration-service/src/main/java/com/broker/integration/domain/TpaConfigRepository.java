package com.broker.integration.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TpaConfigRepository extends JpaRepository<TpaConfig, Long> {
    Optional<TpaConfig> findByBrokerIdAndTpaId(Long brokerId, Short tpaId);
    Optional<TpaConfig> findByIdAndBrokerId(Long id, Long brokerId);
    List<TpaConfig> findByBrokerIdOrderByTpaId(Long brokerId);
}

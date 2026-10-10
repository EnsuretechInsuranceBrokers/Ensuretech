package com.broker.integration.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyRouteRepository extends JpaRepository<PolicyRoute, Long> {
    List<PolicyRoute> findByTpaId(Short tpaId);

    List<PolicyRoute> findByBrokerId(Long brokerId);
}

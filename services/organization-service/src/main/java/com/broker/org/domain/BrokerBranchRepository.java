package com.broker.org.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrokerBranchRepository extends JpaRepository<BrokerBranch, Long> {
    List<BrokerBranch> findByBrokerIdAndTestDataFalseOrderByBranchCode(Long brokerId);
}

package com.broker.policy.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RelationTypeRepository extends JpaRepository<RelationType, Short> {
    Optional<RelationType> findByCode(String code);
}

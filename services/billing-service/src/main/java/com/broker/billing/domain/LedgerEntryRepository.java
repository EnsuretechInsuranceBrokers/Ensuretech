package com.broker.billing.domain;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
    Page<LedgerEntry> findByAccountIdOrderByIdDesc(Long accountId, Pageable p);
    Optional<LedgerEntry> findByAccountIdAndReferenceTypeAndReferenceId(Long accountId, LedgerEntry.ReferenceType t, String refId);
}

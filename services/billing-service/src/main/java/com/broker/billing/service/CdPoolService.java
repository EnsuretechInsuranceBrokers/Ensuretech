package com.broker.billing.service;

import com.broker.billing.api.ConflictException;
import com.broker.billing.api.NotFoundException;
import com.broker.billing.domain.CdAccount;
import com.broker.billing.domain.CdAccountRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shared deposit accounts (legacy CDAccountType). A policy is funded by exactly one account. Joining a pool is only allowed while the
 * policy's own account is untouched (no ledger, no holds): money is never merged or split silently.
 */
@Service
public class CdPoolService {
    private final CdAccountRepository accounts;
    private final JdbcClient jdbc;

    CdPoolService(CdAccountRepository accounts, JdbcClient jdbc) { this.accounts = accounts; this.jdbc = jdbc; }

    @Transactional
    public CdAccount create(long brokerId, String type, String name, Long employerId, List<Long> policyIds) {
        if (!List.of("GROUP", "COMPANY", "MULTIPLE").contains(type)) throw new IllegalArgumentException("unknown pool type");
        if (type.equals("MULTIPLE") ? employerId != null : employerId == null) {
            throw new IllegalArgumentException(type.equals("MULTIPLE") ? "a MULTIPLE pool spans employers: employerId must be omitted" : type + " pools need an employerId");
        }
        if (type.equals("COMPANY") && jdbc.sql("SELECT count(*) FROM billing.cd_accounts WHERE broker_id=:b AND employer_id=:e AND account_type='COMPANY'")
                .param("b", brokerId).param("e", employerId).query(Long.class).single() > 0) {
            throw new ConflictException("Employer " + employerId + " already has a company pool");
        }
        Long id = jdbc.sql("INSERT INTO billing.cd_accounts(broker_id, employer_id, account_type, name) VALUES (:b, :e, :t, :n) RETURNING id")
                .param("b", brokerId).param("e", employerId).param("t", type).param("n", name).query(Long.class).single();
        CdAccount pool = accounts.lockById(id).orElseThrow();
        List<Long> members = new ArrayList<>(new LinkedHashSet<>(policyIds == null ? List.of() : policyIds));
        for (Long p : members) attach(pool, p);
        return pool;
    }

    @Transactional
    public CdAccount addPolicies(long brokerId, long poolId, List<Long> policyIds) {
        CdAccount pool = pool(brokerId, poolId);
        for (Long p : new LinkedHashSet<>(policyIds)) attach(pool, p);
        return pool;
    }

    @Transactional
    public CdAccount removePolicy(long brokerId, long poolId, long policyId) {
        CdAccount pool = pool(brokerId, poolId);
        if (pool.getAccountType().equals("COMPANY")) throw new ConflictException("A company pool funds every policy of the employer; members cannot be removed");
        if (pool.getReserved().signum() != 0) throw new ConflictException("The pool has held money; wait for those endorsements to finish before removing a policy");
        if (jdbc.sql("DELETE FROM billing.cd_account_policies WHERE account_id=:a AND policy_id=:p").param("a", poolId).param("p", policyId).update() == 0) {
            throw new NotFoundException("Policy " + policyId + " is not in this pool");
        }
        return pool;
    }

    @Transactional(readOnly = true)
    public List<Long> members(long accountId) {
        return jdbc.sql("SELECT policy_id FROM billing.cd_account_policies WHERE account_id=:a ORDER BY policy_id").param("a", accountId).query(Long.class).list();
    }

    private CdAccount pool(long brokerId, long poolId) {
        CdAccount a = accounts.lockById(poolId).filter(x -> x.getBrokerId() == brokerId && x.isPool()).orElseThrow(() -> new NotFoundException("No such pool"));
        return a;
    }

    private void attach(CdAccount pool, long policyId) {
        var current = accounts.findByPolicyIdAndBrokerId(policyId, pool.getBrokerId()).orElse(null);
        if (current == null) {
            if (jdbc.sql("SELECT count(*) FROM billing.cd_account_policies WHERE policy_id=:p").param("p", policyId).query(Long.class).single() > 0) {
                throw new ConflictException("Policy " + policyId + " belongs to another broker's account");
            }
        } else if (current.getId().equals(pool.getId())) {
            return;
        } else {
            if (current.isPool()) throw new ConflictException("Policy " + policyId + " is already funded by pool " + current.getId());
            if (pool.getEmployerId() != null && !pool.getEmployerId().equals(current.getEmployerId())) {
                throw new ConflictException("Policy " + policyId + " belongs to a different employer than the pool");
            }
            boolean untouched = current.getBalance().signum() == 0 && current.getReserved().signum() == 0
                    && jdbc.sql("SELECT count(*) FROM billing.cd_ledger WHERE account_id=:a").param("a", current.getId()).query(Long.class).single() == 0
                    && jdbc.sql("SELECT count(*) FROM billing.cd_reservations WHERE account_id=:a").param("a", current.getId()).query(Long.class).single() == 0
                    && jdbc.sql("SELECT count(*) FROM billing.cd_replenishment_requests WHERE account_id=:a").param("a", current.getId()).query(Long.class).single() == 0;
            if (!untouched) throw new ConflictException("Policy " + policyId + " already has a funded deposit account; it cannot be merged into a pool");
            jdbc.sql("DELETE FROM billing.cd_accounts WHERE id=:a").param("a", current.getId()).update();     // cascades its link
        }
        jdbc.sql("INSERT INTO billing.cd_account_policies(policy_id, account_id, broker_id) VALUES (:p, :a, :b)")
                .param("p", policyId).param("a", pool.getId()).param("b", pool.getBrokerId()).update();
    }
}

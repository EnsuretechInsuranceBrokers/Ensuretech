package com.broker.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.broker.billing.api.ConflictException;
import com.broker.billing.domain.LedgerEntry.ReferenceType;
import com.broker.billing.service.CdLedgerService.CheckResult;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class LedgerTest extends IntegrationTestBase {

    private CheckResult check(long policy, UUID job, String required) { return ledger.check(BROKER_A, policy, job, d(required)); }

    @Test
    void sufficientDepositIsReservedAndShrinksWhatOthersCanUse() {
        funded(BROKER_A, EMP_A, 1, "1000.00");
        UUID j1 = UUID.randomUUID(), j2 = UUID.randomUUID(), j3 = UUID.randomUUID();
        CheckResult a = check(1, j1, "600.00");
        assertThat(a.status()).isEqualTo("SUFFICIENT");
        assertThat(a.reserved()).isEqualByComparingTo("600.00");
        CheckResult b = check(1, j2, "600.00");                         // only 400 left for a second job
        assertThat(b.status()).isEqualTo("PARTIAL_SUFFICIENT");
        assertThat(b.available()).isEqualByComparingTo("400.00");
        assertThat(b.deficit()).isEqualByComparingTo("200.00");
        assertThat(b.reserved()).isEqualByComparingTo("0.00");
        assertThat(check(1, j3, "400.00").status()).isEqualTo("SUFFICIENT");   // exactly the remainder
        assertThat(check(1, UUID.randomUUID(), "0.01").status()).isEqualTo("INSUFFICIENT");   // nothing left at all
        assertInvariants(1);
    }

    @Test
    void checkingTheSameJobAgainReEvaluatesItsSingleReservation() {
        funded(BROKER_A, EMP_A, 1, "1000.00");
        UUID j = UUID.randomUUID();
        check(1, j, "300.00");
        assertThat(check(1, j, "300.00").reserved()).isEqualByComparingTo("300.00");             // idempotent: still one hold
        assertThat(one("SELECT reserved FROM billing.cd_accounts WHERE policy_id = 1")).isEqualByComparingTo("300.00");
        assertThat(check(1, j, "900.00").status()).isEqualTo("SUFFICIENT");                       // job grew: hold resized, not doubled
        assertThat(one("SELECT reserved FROM billing.cd_accounts WHERE policy_id = 1")).isEqualByComparingTo("900.00");
        assertThat(check(1, j, "1500.00").status()).isEqualTo("PARTIAL_SUFFICIENT");              // can no longer be covered
        assertThat(one("SELECT reserved FROM billing.cd_accounts WHERE policy_id = 1")).isEqualByComparingTo("0.00");   // so it holds nothing
        assertThat(check(1, j, "200.00").status()).isEqualTo("SUFFICIENT");                       // and can reserve again later
        assertThat(jdbc.sql("SELECT count(*) FROM billing.cd_reservations").query(Integer.class).single()).isEqualTo(1);
        assertInvariants(1);
    }

    @Test
    void aRefundNeedsNoDepositAndReleasesAnEarlierHold() {
        funded(BROKER_A, EMP_A, 1, "0");
        assertThat(check(1, UUID.randomUUID(), "-500.00").status()).isEqualTo("SUFFICIENT");
        assertThat(check(1, UUID.randomUUID(), "0.00").status()).isEqualTo("SUFFICIENT");
        funded(BROKER_A, EMP_A, 2, "1000.00");
        UUID j = UUID.randomUUID();
        check(2, j, "400.00");
        assertThat(check(2, j, "-50.00").reserved()).isEqualByComparingTo("0.00");               // recomputed as a refund: hold freed
        assertThat(one("SELECT reserved FROM billing.cd_accounts WHERE policy_id = 2")).isEqualByComparingTo("0.00");
        assertInvariants(2);
    }

    @Test
    void anUnknownPolicyOrAnotherBrokersPolicyHasNoDeposit() {
        funded(BROKER_A, EMP_A, 1, "1000.00");
        assertThat(ledger.check(BROKER_B, 1, UUID.randomUUID(), d("1.00")).status()).isEqualTo("INSUFFICIENT");   // cannot spend A's money
        assertThat(ledger.check(BROKER_A, 99, UUID.randomUUID(), d("1.00")).status()).isEqualTo("INSUFFICIENT");
        assertThat(one("SELECT reserved FROM billing.cd_accounts WHERE policy_id = 1")).isEqualByComparingTo("0.00");
    }

    @Test
    void committingTurnsTheReservationIntoARealDebitForTheActualTotal() {
        funded(BROKER_A, EMP_A, 1, "1000.00");
        UUID job = UUID.randomUUID();
        check(1, job, "500.00");
        ledger.commitEndorsement(UUID.randomUUID(), BROKER_A, EMP_A, 1, job, d("480.00"));       // actual total was a bit less
        assertThat(one("SELECT balance FROM billing.cd_accounts WHERE policy_id = 1")).isEqualByComparingTo("520.00");
        assertThat(one("SELECT reserved FROM billing.cd_accounts WHERE policy_id = 1")).isEqualByComparingTo("0.00");
        assertThat(jdbc.sql("SELECT status::text FROM billing.cd_reservations").query(String.class).single()).isEqualTo("COMMITTED");
        assertInvariants(1);
    }

    @Test
    void duplicateAndReplayedEventsNeverDebitTwice() {
        funded(BROKER_A, EMP_A, 1, "1000.00");
        UUID job = UUID.randomUUID(), event = UUID.randomUUID();
        check(1, job, "300.00");
        ledger.commitEndorsement(event, BROKER_A, EMP_A, 1, job, d("300.00"));
        ledger.commitEndorsement(event, BROKER_A, EMP_A, 1, job, d("300.00"));                   // same event redelivered
        ledger.commitEndorsement(UUID.randomUUID(), BROKER_A, EMP_A, 1, job, d("300.00"));       // new event id, same job
        assertThat(one("SELECT balance FROM billing.cd_accounts WHERE policy_id = 1")).isEqualByComparingTo("700.00");
        assertThat(jdbc.sql("SELECT count(*) FROM billing.cd_ledger WHERE reference_type='ENDORSEMENT'").query(Integer.class).single()).isEqualTo(1);
        assertInvariants(1);
    }

    @Test
    void aRefundEndorsementCreditsTheDeposit() {
        funded(BROKER_A, EMP_A, 1, "100.00");
        ledger.commitEndorsement(UUID.randomUUID(), BROKER_A, EMP_A, 1, UUID.randomUUID(), d("-1180.00"));
        assertThat(one("SELECT balance FROM billing.cd_accounts WHERE policy_id = 1")).isEqualByComparingTo("1280.00");
        assertThat(jdbc.sql("SELECT entry_type::text FROM billing.cd_ledger ORDER BY id DESC LIMIT 1").query(String.class).single()).isEqualTo("CREDIT");
        assertInvariants(1);
    }

    @Test
    void anEndorsementAppliedAfterItsHoldExpiredStillDebitsAndLeavesADeficit() {
        funded(BROKER_A, EMP_A, 1, "100.00");
        UUID job = UUID.randomUUID();
        check(1, job, "80.00");
        jdbc.sql("UPDATE billing.cd_reservations SET expires_at = now() - interval '1 hour'").update();
        assertThat(ledger.releaseExpired(Instant.now())).isEqualTo(1);
        assertThat(ledger.releaseExpired(Instant.now())).isZero();                                // idempotent
        funded(BROKER_A, EMP_A, 2, "0");
        check(1, UUID.randomUUID(), "90.00");                                                      // another job takes the freed money
        ledger.commitEndorsement(UUID.randomUUID(), BROKER_A, EMP_A, 1, job, d("80.00"));         // the endorsement is already live
        assertThat(one("SELECT balance FROM billing.cd_accounts WHERE policy_id = 1")).isEqualByComparingTo("20.00");
        assertInvariants(1);
        ledger.commitEndorsement(UUID.randomUUID(), BROKER_A, EMP_A, 1, UUID.randomUUID(), d("500.00"));   // no hold at all
        assertThat(one("SELECT balance FROM billing.cd_accounts WHERE policy_id = 1")).isEqualByComparingTo("-480.00");   // deficit, not lost
        assertThat(check(1, UUID.randomUUID(), "1.00").status()).isEqualTo("INSUFFICIENT");       // and it blocks further endorsements
        assertInvariants(1);
    }

    @Test
    void commitCreatesTheAccountIfNeededButNeverUnderTheWrongBroker() {
        ledger.commitEndorsement(UUID.randomUUID(), BROKER_A, EMP_A, 7, UUID.randomUUID(), d("10.00"));
        assertThat(one("SELECT balance FROM billing.cd_accounts WHERE policy_id = 7")).isEqualByComparingTo("-10.00");
        assertThatThrownBy(() -> ledger.commitEndorsement(UUID.randomUUID(), BROKER_B, EMP_B, 7, UUID.randomUUID(), d("10.00")))
                .isInstanceOf(ConflictException.class);
        assertThat(one("SELECT balance FROM billing.cd_accounts WHERE policy_id = 7")).isEqualByComparingTo("-10.00");
    }

    @Test
    void releaseForJobFreesTheHoldOnceAndIgnoresUnknownJobs() {
        funded(BROKER_A, EMP_A, 1, "1000.00");
        UUID job = UUID.randomUUID();
        check(1, job, "700.00");
        assertThat(ledger.releaseForJob(job)).isTrue();
        assertThat(ledger.releaseForJob(job)).isFalse();
        assertThat(ledger.releaseForJob(UUID.randomUUID())).isFalse();
        assertThat(check(1, UUID.randomUUID(), "1000.00").status()).isEqualTo("SUFFICIENT");     // the money is available again
        assertInvariants(1);
    }

    @Test
    void creditsAreIdempotentByReferenceAndRejectMismatchedReplays() {
        funded(BROKER_A, EMP_A, 1, "0");
        var first = ledger.credit(BROKER_A, 1, d("250.00"), ReferenceType.DEPOSIT, "UTR-1", "bank", "u1");
        var again = ledger.credit(BROKER_A, 1, d("250.00"), ReferenceType.DEPOSIT, "UTR-1", "bank", "u1");
        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(one("SELECT balance FROM billing.cd_accounts WHERE policy_id = 1")).isEqualByComparingTo("250.00");
        assertThatThrownBy(() -> ledger.credit(BROKER_A, 1, d("999.00"), ReferenceType.DEPOSIT, "UTR-1", "x", "u1")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> ledger.credit(BROKER_A, 1, d("0"), ReferenceType.DEPOSIT, "UTR-2", "x", "u1")).isInstanceOf(IllegalArgumentException.class);
        assertThat(ledger.credit(BROKER_A, 1, d("250.00"), ReferenceType.ADJUSTMENT, "UTR-1", "other kind", "u1").getId()).isNotEqualTo(first.getId());
        assertInvariants(1);
    }

    @Test
    void amountsAreStoredToTheCent() {
        funded(BROKER_A, EMP_A, 1, "0");
        ledger.credit(BROKER_A, 1, d("10.005"), ReferenceType.DEPOSIT, "r", "x", "u");
        assertThat(one("SELECT balance FROM billing.cd_accounts WHERE policy_id = 1")).isEqualByComparingTo("10.01");   // half-up
    }

    @Test
    void theLedgerCannotBeEditedEvenWithDirectSql() {
        funded(BROKER_A, EMP_A, 1, "100.00");
        for (String sql : new String[] {"UPDATE billing.cd_ledger SET amount = 1", "DELETE FROM billing.cd_ledger"}) {
            assertThatThrownBy(() -> jdbc.sql(sql).update()).hasMessageContaining("append-only");
        }
        assertInvariants(1);
    }

    @Test
    void lowBalanceRaisesAnEventOnlyWhenADebitCrossesTheThreshold() {
        funded(BROKER_A, EMP_A, 1, "1000.00");
        ledger.setThreshold(BROKER_A, 1, d("500.00"));
        ledger.commitEndorsement(UUID.randomUUID(), BROKER_A, EMP_A, 1, UUID.randomUUID(), d("100.00"));   // 900 left: fine
        assertThat(count("CdBalanceLow")).isZero();
        ledger.commitEndorsement(UUID.randomUUID(), BROKER_A, EMP_A, 1, UUID.randomUUID(), d("450.00"));   // 450 left: below 500
        assertThat(count("CdBalanceLow")).isEqualTo(1);
        assertThat(count("CdBalanceUpdated")).isEqualTo(3);                                               // seed credit + 2 debits
    }

    private int count(String type) {
        return jdbc.sql("SELECT count(*) FROM outbox.outbox_events WHERE event_type = :t AND topic = 'cd-balance.updates'").param("t", type).query(Integer.class).single();
    }

    // ── concurrency: the reason reservations exist ───────────────────────────────────────────────────────────
    @Test
    void concurrentJobsCanNeverReserveMoreThanTheDepositHolds() throws Exception {
        funded(BROKER_A, EMP_A, 1, "1000.00");
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<CheckResult>> results = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            UUID job = UUID.randomUUID();
            results.add(pool.submit(() -> { go.await(); return ledger.check(BROKER_A, 1, job, d("100.00")); }));
        }
        go.countDown();
        int ok = 0;
        for (Future<CheckResult> f : results) if (f.get(30, TimeUnit.SECONDS).status().equals("SUFFICIENT")) ok++;
        pool.shutdown();
        assertThat(ok).as("exactly 10 x 100 fit into 1000").isEqualTo(10);
        assertThat(one("SELECT reserved FROM billing.cd_accounts WHERE policy_id = 1")).isEqualByComparingTo("1000.00");
        assertInvariants(1);
    }

    @Test
    void concurrentDeliveriesOfTheSameEventDebitExactlyOnce() throws Exception {
        funded(BROKER_A, EMP_A, 1, "1000.00");
        UUID job = UUID.randomUUID(), event = UUID.randomUUID();
        check(1, job, "100.00");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 8; i++) fs.add(pool.submit(() -> { go.await(); ledger.commitEndorsement(event, BROKER_A, EMP_A, 1, job, d("100.00")); return null; }));
        go.countDown();
        for (Future<?> f : fs) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(one("SELECT balance FROM billing.cd_accounts WHERE policy_id = 1")).isEqualByComparingTo("900.00");
        assertInvariants(1);
    }

    @Test
    void mixedConcurrentActivityKeepsTheInvariants() throws Exception {
        funded(BROKER_A, EMP_A, 1, "5000.00");
        ExecutorService pool = Executors.newFixedThreadPool(12);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            final int n = i;
            fs.add(pool.submit(() -> {
                UUID job = UUID.randomUUID();
                if (ledger.check(BROKER_A, 1, job, d("50.00")).status().equals("SUFFICIENT")) {
                    switch (n % 3) {
                        case 0 -> ledger.commitEndorsement(UUID.randomUUID(), BROKER_A, EMP_A, 1, job, d("50.00"));
                        case 1 -> ledger.releaseForJob(job);
                        default -> ledger.credit(BROKER_A, 1, d("5.00"), ReferenceType.DEPOSIT, "pay-" + n, "x", "u");
                    }
                }
                return null;
            }));
        }
        for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertInvariants(1);
    }
}

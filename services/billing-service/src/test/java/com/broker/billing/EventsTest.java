package com.broker.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.broker.billing.events.BillingEventHandler;
import com.broker.billing.outbox.OutboxRelayJob;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Feeds the SHARED sample payloads (what the producers really emit) to the real handler. */
class EventsTest extends IntegrationTestBase {
    @Autowired BillingEventHandler handler;
    @Autowired OutboxRelayJob relay;

    static final Path SAMPLES = Path.of("../../shared/contracts/events");

    private static String sample(String name) throws Exception { return Files.readString(SAMPLES.resolve(name + ".sample.json")); }

    @Test
    void policyApprovedOpensAnAccountOnceAndNeverUnderAnotherBroker() throws Exception {
        handler.handle(sample("policy-approved"));
        handler.handle(sample("policy-approved"));
        assertThat(jdbc.sql("SELECT count(*) FROM billing.cd_accounts WHERE policy_id=1 AND broker_id=1 AND employer_id=10").query(Integer.class).single()).isEqualTo(1);
        handler.handle(sample("policy-approved").replace("\"broker_id\": 1", "\"broker_id\": 2"));            // conflicting claim is skipped, not applied
        assertThat(jdbc.sql("SELECT broker_id FROM billing.cd_accounts WHERE policy_id=1").query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void endorsementAppliedCommitsTheReservationFromTheRealPayload() throws Exception {
        funded(BROKER_A, EMP_A, 1, "5000.00");
        UUID job = UUID.fromString("9a1f4a52-5d0e-4c67-a2f1-6f1d3d1b7e01");
        ledger.check(BROKER_A, 1, job, d("1765.15"));
        handler.handle(sample("endorsement-applied"));
        handler.handle(sample("endorsement-applied"));                                                      // redelivered
        assertThat(one("SELECT balance FROM billing.cd_accounts WHERE policy_id=1")).isEqualByComparingTo("3234.85");
        assertThat(one("SELECT reserved FROM billing.cd_accounts WHERE policy_id=1")).isEqualByComparingTo("0.00");
        assertThat(jdbc.sql("SELECT status::text FROM billing.cd_reservations").query(String.class).single()).isEqualTo("COMMITTED");
        assertInvariants(1);
    }

    @Test
    void cancellationFreesTheReservation() throws Exception {
        funded(BROKER_A, EMP_A, 1, "5000.00");
        UUID job = UUID.fromString("9a1f4a52-5d0e-4c67-a2f1-6f1d3d1b7e01");
        ledger.check(BROKER_A, 1, job, d("1765.15"));
        assertThat(one("SELECT reserved FROM billing.cd_accounts WHERE policy_id=1")).isEqualByComparingTo("1765.15");
        handler.handle(sample("endorsement-state-changed"));
        assertThat(one("SELECT reserved FROM billing.cd_accounts WHERE policy_id=1")).isEqualByComparingTo("0.00");
        handler.handle(sample("endorsement-state-changed"));
        assertInvariants(1);
        // a state change that is not terminal-without-money leaves the hold alone
        ledger.check(BROKER_A, 1, job, d("100.00"));
        handler.handle(sample("endorsement-state-changed").replace("CANCELLED_BY_HR", "ENDORSEMENT_QUEUED"));
        assertThat(one("SELECT reserved FROM billing.cd_accounts WHERE policy_id=1")).isEqualByComparingTo("100.00");
    }

    @Test
    void malformedAndUnknownEventsAreSkippedWithoutBlockingTheStream() throws Exception {
        funded(BROKER_A, EMP_A, 1, "100.00");
        for (String bad : new String[] {"not json", "{}", "{\"event_type\":\"EndorsementApplied\"}",
                sample("endorsement-applied").replace("1765.15", "NaN-money"), sample("endorsement-applied").replace("9a1f4a52-5d0e-4c67-a2f1-6f1d3d1b7e01", "nope"),
                "{\"event_type\":\"SomethingNew\",\"x\":1}"}) {
            handler.handle(bad);                                                                         // must not throw
        }
        assertThat(one("SELECT balance FROM billing.cd_accounts WHERE policy_id=1")).isEqualByComparingTo("100.00");
        assertInvariants(1);
    }

    @Test
    void balanceEventsReachTheRelayWithTheBrokerAsKey() throws Exception {
        funded(BROKER_A, EMP_A, 1, "100.00");
        handler.handle(sample("endorsement-applied"));
        assertThat(relay.relayBatch()).isGreaterThanOrEqualTo(2);
        assertThat(sender.sent).allSatisfy(s -> {
            assertThat(s.topic()).isEqualTo("cd-balance.updates");
            assertThat(s.key()).isEqualTo("1");
        });
    }

    @Test
    void cdBalanceUpdatedHasExactlyTheSharedSampleFields() throws Exception {
        handler.handle(sample("policy-approved"));
        ledger.credit(1L, 1L, new java.math.BigDecimal("100.00"), com.broker.billing.domain.LedgerEntry.ReferenceType.DEPOSIT, "contract-1", "seed", "test");
        String real = jdbc.sql("SELECT payload::text FROM outbox.outbox_events WHERE event_type='CdBalanceUpdated'").query(String.class).single();
        var mapper = new tools.jackson.databind.ObjectMapper();
        java.util.TreeSet<String> realKeys = new java.util.TreeSet<>(), sampleKeys = new java.util.TreeSet<>();
        mapper.readTree(real).propertyNames().forEach(realKeys::add);
        mapper.readTree(sample("cd-balance-updated")).propertyNames().forEach(sampleKeys::add);
        assertThat(realKeys).as("CdBalanceUpdated fields drifted from shared/contracts/events").isEqualTo(sampleKeys);
    }
}

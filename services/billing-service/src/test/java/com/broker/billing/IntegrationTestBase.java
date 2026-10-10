package com.broker.billing;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.broker.billing.domain.CdAccount;
import com.broker.billing.service.CdLedgerService;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {"app.outbox.relay.enabled=false", "app.kafka.listener.enabled=false",
        "app.cd.expiry-sweep-enabled=false", "app.grpc.port=0"})
@AutoConfigureMockMvc
@Import(TestSenderConfig.class)
abstract class IntegrationTestBase {
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.jwt.public-key-location", () -> "file:" + TestTokens.PUBLIC_PEM);
        r.add("app.jwt.issuer", () -> TestTokens.ISSUER);
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected CdLedgerService ledger;
    @Autowired protected RecordingSender sender;

    protected static final long BROKER_A = 1, BROKER_B = 2, EMP_A = 10, EMP_B = 20;

    @BeforeEach
    void reset() {
        // the ledger is append-only by trigger, so tests clear it with TRUNCATE (not row-level DELETE)
        jdbc.sql("TRUNCATE billing.cd_replenishment_requests, billing.cd_reservations, billing.cd_ledger, "
                + "billing.cd_accounts, billing.processed_events RESTART IDENTITY CASCADE").update();
        jdbc.sql("DELETE FROM outbox.outbox_events").update();
        sender.sent.clear();
        sender.failing = false;
    }

    protected static String bearer(String role, Long broker, Long employer) { return "Bearer " + TestTokens.token(role, broker, employer); }

    protected static String admin(long broker) { return bearer("BROKER_ADMIN", broker, null); }

    protected static String system() { return bearer("SYSTEM", null, null); }

    protected MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String auth, String body) {
        return b.header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    protected static BigDecimal d(String s) { return new BigDecimal(s); }

    /** Opens an account for the policy and funds it. */
    protected CdAccount funded(long broker, long employer, long policy, String balance) {
        CdAccount a = ledger.openAccount(broker, employer, policy);
        if (d(balance).signum() > 0) {
            ledger.credit(broker, policy, d(balance), com.broker.billing.domain.LedgerEntry.ReferenceType.DEPOSIT, "seed-" + policy, "seed", "test");
        }
        return a;
    }

    protected BigDecimal one(String sql, Object... params) {
        var s = jdbc.sql(sql);
        for (int i = 0; i < params.length; i++) s = s.param(params[i]);
        return s.query(BigDecimal.class).single();
    }

    /** The two properties that make the ledger trustworthy. */
    protected void assertInvariants(long policyId) {
        BigDecimal balance = one("SELECT balance FROM billing.cd_accounts WHERE policy_id = ?", policyId);
        BigDecimal ledgerSum = one("SELECT COALESCE(SUM(CASE l.entry_type WHEN 'CREDIT' THEN l.amount ELSE -l.amount END), 0) FROM billing.cd_ledger l "
                + "JOIN billing.cd_accounts a ON a.id = l.account_id WHERE a.policy_id = ?", policyId);
        BigDecimal reserved = one("SELECT reserved FROM billing.cd_accounts WHERE policy_id = ?", policyId);
        BigDecimal held = one("SELECT COALESCE(SUM(r.amount), 0) FROM billing.cd_reservations r JOIN billing.cd_accounts a ON a.id = r.account_id "
                + "WHERE a.policy_id = ? AND r.status = 'HELD'", policyId);
        org.assertj.core.api.Assertions.assertThat(balance).as("balance == signed ledger sum").isEqualByComparingTo(ledgerSum);
        org.assertj.core.api.Assertions.assertThat(reserved).as("reserved == sum of HELD reservations").isEqualByComparingTo(held);
        org.assertj.core.api.Assertions.assertThat(reserved.signum()).isGreaterThanOrEqualTo(0);
    }
}

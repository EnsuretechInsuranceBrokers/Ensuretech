package com.broker.integration.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationFilterTest {
    private final CorrelationFilter filter = new CorrelationFilter();

    /** Runs a request through the filter and reports the id the code behind it saw in its logging context. */
    private String[] run(String header) throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/v1/anything");
        if (header != null) req.addHeader("X-Correlation-Id", header);
        var res = new MockHttpServletResponse();
        String[] seen = new String[1];
        filter.doFilter(req, res, (rq, rs) -> seen[0] = MDC.get("correlationId"));
        return new String[] {seen[0], res.getHeader("X-Correlation-Id")};
    }

    @Test
    void aPlainIdIsKeptSeenByTheCodeBehindItAndReturned() throws Exception {
        String[] r = run("qa-trace-12345");
        assertThat(r[0]).isEqualTo("qa-trace-12345");
        assertThat(r[1]).isEqualTo("qa-trace-12345");
        assertThat(MDC.get("correlationId")).as("never left behind on the thread").isNull();
    }

    @Test
    void noIdGetsOneAndEachRequestGetsItsOwn() throws Exception {
        String a = run(null)[1], b = run(null)[1];
        assertThat(a).matches("[0-9a-f-]{36}").isNotEqualTo(b);
    }

    @Test
    void anythingThatIsNotPlainlyAnIdIsReplacedNeverLogged() throws Exception {
        for (String evil : new String[] {"short", "x".repeat(65), "has space in it", "line1\nFORGED LOG LINE", "semi;colon;1234", "<script>alert(1)</script>", "ünïcode-ident"}) {
            String[] r = run(evil);
            assertThat(r[0]).as(evil).isEqualTo(r[1]).matches("[0-9a-f-]{36}");
        }
    }

    @Test
    void anEventsIdIsReadFromItsPayloadAndRestoredAfterwards() {
        assertThat(CorrelationFilter.fromEvent("{\"event_id\":\"1\",\"correlation_id\": \"c1d2e3f4-0000-4000-8000-000000000001\"}")).isEqualTo("c1d2e3f4-0000-4000-8000-000000000001");
        assertThat(CorrelationFilter.fromEvent("{\"correlation_id\":\"bad id\"}")).isNull();
        assertThat(CorrelationFilter.fromEvent("{}")).isNull();
        MDC.put("correlationId", "outer-request-id");
        String[] inside = new String[1];
        CorrelationFilter.runWith("event-correlation-1", () -> inside[0] = CorrelationFilter.current());
        assertThat(inside[0]).isEqualTo("event-correlation-1");
        assertThat(CorrelationFilter.current()).isEqualTo("outer-request-id");
        MDC.clear();
    }
}

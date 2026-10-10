package com.broker.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"admin.ui.password=a-long-console-password", "admin.identity.client-id=svc_test", "admin.identity.client-secret=x"})
@AutoConfigureMockMvc
class ConsoleSecurityTest {
    @Autowired MockMvc mvc;

    @Test
    void nothingIsServedWithoutALogin() throws Exception {
        for (String path : new String[] {"/jobs.html", "/queries.html", "/ops-api/queries", "/ops-api/queries/stream", "/ops-api/jobs", "/ops-api/jobs/audit", "/instances", "/applications"}) {
            int s = mvc.perform(get(path).header("Accept", "application/json")).andReturn().getResponse().getStatus();
            assertThat(s).as(path).isIn(401, 302);
        }
        mvc.perform(post("/ops-api/jobs/11111111-1111-4111-8111-111111111111/kill").contentType("application/json").content("{}")).andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 302, 403));
    }

    @Test
    void theRightLoginGetsThePage() throws Exception {
        mvc.perform(get("/jobs.html").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic("admin", "a-long-console-password")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("job-killer password")));
        mvc.perform(get("/jobs.html").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic("admin", "wrong"))).andExpect(status().isUnauthorized());
    }

    @Test
    void theLiveRequestsPageAndItsFeedNeedTheLoginAndTheClearButtonNeedsCsrf() throws Exception {
        var basic = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic("admin", "a-long-console-password");
        mvc.perform(get("/queries.html").with(basic)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Time (IST)")));
        mvc.perform(delete("/ops-api/queries").with(basic)).andExpect(status().isForbidden());                       // no CSRF token
    }

    @Test
    void theConsoleRefusesToStartWithoutAPassword() {
        assertThatThrownBy(() -> SpringApplication.run(AdminServiceApplication.class, "--server.port=0", "--admin.ui.password=short", "--spring.main.banner-mode=off"))
                .hasRootCauseMessage("ADMIN_UI_PASSWORD must be set (at least 12 characters): this console can change log levels and stop jobs.");
    }
}

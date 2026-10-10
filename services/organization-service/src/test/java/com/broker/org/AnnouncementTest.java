package com.broker.org;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Legacy announcements: banners / carousels / notices a broker shows employers, targeted and time-limited. */
class AnnouncementTest extends IntegrationTestBase {
    private String admin() { return bearer("BROKER_ADMIN", brokerA, null); }

    private static String body(String title, String extra) {
        return "{\"kind\":\"BANNER\",\"title\":\"" + title + "\",\"content\":\"Open enrolment closes soon\"" + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    private long create(String title, String extra) throws Exception {
        String r = mvc.perform(json(post("/api/v1/broker/announcements"), admin(), body(title, extra))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return Long.parseLong(r.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    @Test
    void anEmployerSeesOnlyWhatIsLiveAndMeantForIt() throws Exception {
        long a = createEmployer(brokerA, "A"), b = createEmployer(brokerA, "B");
        String today = LocalDate.now().toString(), future = LocalDate.now().plusDays(5).toString(), past = LocalDate.now().minusDays(10).toString();
        create("for everyone", "\"sequence\":2");
        create("only A", "\"allEmployers\":false,\"employerIds\":[" + a + "],\"sequence\":1");
        create("only B", "\"allEmployers\":false,\"employerIds\":[" + b + "]");
        create("not yet", "\"startsOn\":\"" + future + "\"");
        create("expired", "\"startsOn\":\"" + past + "\",\"ttlDays\":3");
        create("last day", "\"startsOn\":\"" + LocalDate.now().minusDays(2) + "\",\"ttlDays\":3");
        long off = create("switched off", "\"active\":false");

        mvc.perform(get("/api/v1/employer/announcements").header("Authorization", bearer("EMPLOYER_ADMIN", brokerA, a)))
                .andExpect(jsonPath("$[*].title", contains("last day", "only A", "for everyone")))      // by sequence (0, 1, 2); B's, future, expired and off are absent
                .andExpect(jsonPath("$[1].employerIds", hasSize(0)));                                    // targeting is not disclosed to employers
        mvc.perform(get("/api/v1/employer/announcements").header("Authorization", bearer("EMPLOYER_ADMIN", brokerA, b)))
                .andExpect(jsonPath("$[*].title", containsInAnyOrder("for everyone", "only B", "last day")));
        mvc.perform(get("/api/v1/employer/announcements").header("Authorization", bearer("EMPLOYER_ADMIN", brokerB, createEmployer(brokerB, "OTHER"))))
                .andExpect(jsonPath("$", hasSize(0)));                                                   // another broker's announcements never reach us

        mvc.perform(get("/api/v1/broker/announcements").header("Authorization", admin())).andExpect(jsonPath("$", hasSize(7)))
                .andExpect(jsonPath("$[?(@.id==" + off + ")].live", contains(false)));
    }

    @Test
    void brokersEditRetireAndCannotTouchEachOthersAnnouncements() throws Exception {
        long e = createEmployer(brokerA, "A");
        long id = create("first", "");
        mvc.perform(json(put("/api/v1/broker/announcements/" + id), admin(), body("changed", "\"allEmployers\":false,\"employerIds\":[" + e + "],\"ttlDays\":7")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title", is("changed"))).andExpect(jsonPath("$.employerIds", contains((int) e))).andExpect(jsonPath("$.endsOn", notNullValue()));
        String other = bearer("BROKER_ADMIN", brokerB, null);
        mvc.perform(json(put("/api/v1/broker/announcements/" + id), other, body("hijack", ""))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/broker/announcements/" + id).header("Authorization", other)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/broker/announcements/" + id).header("Authorization", admin())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/employer/announcements").header("Authorization", bearer("EMPLOYER_ADMIN", brokerA, e))).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/v1/broker/announcements").header("Authorization", other)).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void badInputIsRefused() throws Exception {
        long foreign = createEmployer(brokerB, "F");
        mvc.perform(json(post("/api/v1/broker/announcements"), admin(), body("x", "\"allEmployers\":false,\"employerIds\":[]"))).andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/v1/broker/announcements"), admin(), body("x", "\"allEmployers\":false,\"employerIds\":[" + foreign + "]"))).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/broker/announcements"), admin(), body("x", "\"link\":\"javascript:alert(1)\""))).andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/v1/broker/announcements"), admin(), body("x", "\"kind\":\"POPUP\""))).andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/v1/broker/announcements"), admin(), body("x", "\"ttlDays\":0"))).andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/v1/broker/announcements"), bearer("BROKER_NON_ADMIN", brokerA, null), body("x", ""))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/employer/announcements").header("Authorization", admin())).andExpect(status().isForbidden());                // a broker token is not an employer
    }
}

package com.broker.org;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;

/** Legacy employer_child_companies: a group head with subsidiaries. */
class ParentChildTest extends IntegrationTestBase {
    private String admin() { return bearer("BROKER_ADMIN", brokerA, null); }

    private void setParent(long child, long parent, int status) throws Exception {
        mvc.perform(json(put("/api/v1/broker/employers/" + child + "/parent"), admin(), "{\"parentEmployerId\":" + parent + "}")).andExpect(status().is(status));
    }

    @Test
    void childrenAreLinkedListedAndSeenByTheParentsHrOnly() throws Exception {
        long head = createEmployer(brokerA, "HEAD"), c1 = createEmployer(brokerA, "C1"), c2 = createEmployer(brokerA, "C2");
        setParent(c1, head, 200);
        setParent(c2, head, 200);
        mvc.perform(get("/api/v1/broker/employers/" + c1).header("Authorization", admin())).andExpect(jsonPath("$.parentEmployerId", is((int) head)));
        mvc.perform(get("/api/v1/broker/employers/" + head + "/children").header("Authorization", admin()))
                .andExpect(jsonPath("$", hasSize(2))).andExpect(jsonPath("$[0].employerCode", is("C1")));
        mvc.perform(get("/api/v1/employer/employer/children").header("Authorization", bearer("EMPLOYER_ADMIN", brokerA, head)))
                .andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get("/api/v1/employer/employer/children").header("Authorization", bearer("EMPLOYER_ADMIN", brokerA, c1)))
                .andExpect(jsonPath("$", hasSize(0)));                                           // a child sees no siblings
        mvc.perform(delete("/api/v1/broker/employers/" + c2 + "/parent").header("Authorization", admin())).andExpect(jsonPath("$.parentEmployerId").doesNotExist());
        mvc.perform(get("/api/v1/broker/employers/" + head + "/children").header("Authorization", admin())).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void theHierarchyIsOneLevelOnSameBrokerAndNeverCyclic() throws Exception {
        long head = createEmployer(brokerA, "HEAD"), mid = createEmployer(brokerA, "MID"), leaf = createEmployer(brokerA, "LEAF");
        long foreign = createEmployer(brokerB, "FOREIGN");
        setParent(head, head, 409);                    // itself
        setParent(mid, head, 200);
        setParent(leaf, mid, 409);                     // grandchild
        setParent(head, mid, 409);                     // a parent becoming a child
        setParent(mid, foreign, 404);                  // another broker's employer does not exist for us
        mvc.perform(json(post("/api/v1/broker/employers"), admin(),
                "{\"employerCode\":\"KID\",\"legalName\":\"K\",\"displayName\":\"K\",\"parentEmployerId\":" + head + "}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.parentEmployerId", is((int) head)));
        mvc.perform(json(post("/api/v1/broker/employers"), admin(),
                "{\"employerCode\":\"KID2\",\"legalName\":\"K\",\"displayName\":\"K\",\"parentEmployerId\":" + foreign + "}")).andExpect(status().isNotFound());
        mvc.perform(json(put("/api/v1/broker/employers/" + mid + "/parent"), bearer("BROKER_NON_ADMIN", brokerA, null), "{\"parentEmployerId\":" + head + "}"))
                .andExpect(status().isForbidden());
        mvc.perform(json(put("/api/v1/broker/employers/" + mid + "/parent"), admin(), "{}")).andExpect(status().isBadRequest());
    }

    @Test
    void aParentWithActiveChildrenCannotBeDeactivatedUntilTheyAreDetached() throws Exception {
        long head = createEmployer(brokerA, "HEAD"), kid = createEmployer(brokerA, "KID");
        setParent(kid, head, 200);
        mvc.perform(delete("/api/v1/broker/employers/" + head).header("Authorization", admin())).andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/broker/employers/" + kid + "/parent").header("Authorization", admin())).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/broker/employers/" + head).header("Authorization", admin())).andExpect(status().isNoContent());
        long other = createEmployer(brokerA, "OTHER");
        mvc.perform(delete("/api/v1/broker/employers/" + other).header("Authorization", admin())).andExpect(status().isNoContent());
        setParent(kid, other, 409);                    // an inactive parent
    }
}

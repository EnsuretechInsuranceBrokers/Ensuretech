package com.broker.integration;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class OfflineUploadFlowTest extends IntegrationTestBase {
    private static final String B = "/api/v1/broker/integration/", T = "/api/v1/tpa/integration/";

    /** A roster member that is waiting for its TPA id, endorsed `hoursAgo` hours ago. */
    private static String waiting(long id, String code, String rel, String first, String last, int hoursAgo) {
        String at = Instant.now().minus(hoursAgo, ChronoUnit.HOURS).toString();
        return FakeWorld.member(id, code, rel, first, last).replace("\"tpa_member_id\":null", "\"tpa_member_id\":null,\"endorsement_ref\":\"abcd1234\",\"endorsed_at\":\"" + at + "\"");
    }

    private void policy(long id, long broker, int tpa, String... members) {
        jdbc.sql("INSERT INTO integration.policy_routes(policy_id, broker_id, employer_id, tpa_id) VALUES (:p, :b, 10, :t)").param("p", id).param("b", broker).param("t", (short) tpa).update();
        world.policyRoster.put(id, new java.util.ArrayList<>(List.of(members)));
        world.policyNumbers.put(id, "GMC/" + id);
    }

    private static MockMultipartFile csv(String body) { return new MockMultipartFile("file", "tpa.csv", "text/csv", body.getBytes()); }

    private static final String HEAD = "Employee code,Relationship,TPA member id,TPA member name,E-card URL,Cover start date,Endorsement ref\n";

    private void policyWithThree() {
        policy(50, 1, 3, waiting(1, "E1", "SELF", "Asha", "Rao", 30), waiting(2, "E1", "WIFE", "Meera", "Rao", 30), waiting(3, "E2", "SELF", "Ravi", "K", 2));
    }

    @Test
    void theBoardShowsWhoIsWaitingLongestFirstAndHowUrgent() throws Exception {
        policyWithThree();
        policy(51, 1, 3, waiting(9, "Z1", "SELF", "Zoe", "Z", 5));
        policy(52, 2, 3, waiting(10, "Q1", "SELF", "Other", "Broker", 99));                // another broker
        mvc.perform(get(B + "tpa-pending").header("Authorization", admin(BROKER_A))).andExpect(status().isOk())
                .andExpect(jsonPath("$.policies", hasSize(2))).andExpect(jsonPath("$.policies[0].policyId", is(50)))
                .andExpect(jsonPath("$.policies[0].pendingIds", is(3))).andExpect(jsonPath("$.policies[0].urgency", is("CRITICAL")))
                .andExpect(jsonPath("$.policies[1].urgency", is("HIGH"))).andExpect(jsonPath("$.totalPendingIds", is(4)));
        mvc.perform(get(B + "policies/50/tpa-pending").header("Authorization", admin(BROKER_A))).andExpect(jsonPath("$.members", hasSize(3)))
                .andExpect(jsonPath("$.members[0].urgency", is("CRITICAL"))).andExpect(jsonPath("$.members[2].urgency", is("NORMAL")));
    }

    @Test
    void aPreviewChangesNothingAndAnApplySavesTheGoodLinesAndExplainsTheBadOnes() throws Exception {
        policyWithThree();
        String sheet = HEAD + "E1,Self,T-1,Asha Rao,https://tpa.example/card/1,2030-04-01,abcd1234\n"
                + "E1,Spouse,T-2,Meera Rao,,01/04/2030,abcd1234\n"
                + "E2,Self,T-1,Ravi K,,2030-04-01,abcd1234\n"                           // id reused inside the sheet
                + "E7,Self,T-7,Nobody,,2030-04-01,\n";                                    // not on the policy
        mvc.perform(multipart(B + "policies/50/offline-sheet").file(csv(sheet)).param("mode", "preview").header("Authorization", admin(BROKER_A)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.applied", is(false))).andExpect(jsonPath("$.assigned", is(1))).andExpect(jsonPath("$.rejected", is(3)));
        Assertions.assertThat(world.assignCalls).isEmpty();
        Assertions.assertThat(jdbc.sql("SELECT count(*) FROM integration.offline_uploads").query(Long.class).single()).isZero();

        String res = mvc.perform(multipart(B + "policies/50/offline-sheet").file(csv(sheet)).param("mode", "apply").header("Authorization", admin(BROKER_A)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.applied", is(true))).andExpect(jsonPath("$.uploadId", notNullValue()))
                .andExpect(jsonPath("$.source", is("BROKER_FOR_TPA"))).andExpect(jsonPath("$.longestWaitHours", greaterThan(29.0)))
                .andExpect(jsonPath("$.lines[?(@.line==2)].reasonCode", hasItem("ID_DUPLICATE_IN_SHEET")))
                .andExpect(jsonPath("$.lines[?(@.line==4)].reasonCode", hasItem("ID_DUPLICATE_IN_SHEET")))
                .andExpect(jsonPath("$.lines[?(@.line==5)].reasonCode", hasItem("EMPLOYEE_NOT_ON_POLICY"))).andReturn().getResponse().getContentAsString();
        Assertions.assertThat(world.tpaIdsRecorded).containsEntry(2L, "T-2").hasSize(1);   // T-1 is on two lines, so neither is saved: we cannot tell whose it is
        Assertions.assertThat(world.assignCalls).hasSize(1);
        Assertions.assertThat(jdbc.sql("SELECT count(*) FROM integration.offline_upload_rows WHERE outcome='REJECTED'").query(Long.class).single()).isGreaterThanOrEqualTo(2);
        Assertions.assertThat(outbox("TpaMemberIdsReceived")).isEqualTo(1);
        String id = com.jayway.jsonpath.JsonPath.read(res, "$.uploadId");
        mvc.perform(get(B + "offline-uploads/" + id).param("rejectedOnly", "true").header("Authorization", admin(BROKER_A)))
                .andExpect(jsonPath("$.lines[*].outcome", everyItem(is("REJECTED"))));
        mvc.perform(get(B + "offline-uploads").header("Authorization", admin(BROKER_A))).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void sendingTheSameSheetAgainChangesNothing() throws Exception {
        policyWithThree();
        String sheet = HEAD + "E1,SELF,T-1,Asha Rao,,2030-04-01,abcd1234\nE1,WIFE,T-2,Meera Rao,,2030-04-01,abcd1234\n";
        mvc.perform(multipart(B + "policies/50/offline-sheet").file(csv(sheet)).param("mode", "apply").header("Authorization", admin(BROKER_A))).andExpect(jsonPath("$.assigned", is(2)));
        mvc.perform(multipart(B + "policies/50/offline-sheet").file(csv(sheet)).param("mode", "apply").header("Authorization", admin(BROKER_A)))
                .andExpect(jsonPath("$.assigned", is(0))).andExpect(jsonPath("$.unchanged", is(2)));
        mvc.perform(get(B + "tpa-pending").header("Authorization", admin(BROKER_A))).andExpect(jsonPath("$.policies[0].pendingIds", is(1)));
    }

    @Test
    void aTpaUserUploadsForItsOwnPoliciesOnlyAndIsRecordedAsTheTpa() throws Exception {
        policyWithThree();
        policy(60, 1, 4, waiting(20, "A1", "SELF", "Other", "Tpa", 3));                      // routed to a different TPA
        String tpa = "Bearer " + TestTokens.tpaToken(null, 3L);
        mvc.perform(get(T + "tpa-pending").header("Authorization", tpa)).andExpect(status().isOk()).andExpect(jsonPath("$.policies", hasSize(1)));
        mvc.perform(multipart(T + "policies/50/offline-sheet").file(csv(HEAD + "E1,SELF,T-1,Asha Rao,,2030-04-01,abcd1234\n")).param("mode", "apply").header("Authorization", tpa))
                .andExpect(status().isOk()).andExpect(jsonPath("$.source", is("TPA_PORTAL"))).andExpect(jsonPath("$.assigned", is(1)));
        mvc.perform(multipart(T + "policies/60/offline-sheet").file(csv(HEAD + "A1,SELF,T-9,x,,2030-04-01,\n")).param("mode", "apply").header("Authorization", tpa))
                .andExpect(status().isNotFound());
        mvc.perform(get(T + "policies/60/tpa-pending").header("Authorization", tpa)).andExpect(status().isNotFound());
        // a broker cannot use the TPA door and a TPA user cannot use the broker door
        mvc.perform(get(T + "tpa-pending").header("Authorization", admin(BROKER_A))).andExpect(status().isForbidden());
        mvc.perform(get(B + "tpa-pending").header("Authorization", tpa)).andExpect(status().isForbidden());
    }

    @Test
    void brokersOnlySeeTheirOwnPoliciesAndReadOnlyUsersCannotUpload() throws Exception {
        policyWithThree();
        mvc.perform(get(B + "policies/50/tpa-pending").header("Authorization", admin(BROKER_B))).andExpect(status().isNotFound());
        mvc.perform(multipart(B + "policies/50/offline-sheet").file(csv(HEAD)).param("mode", "apply").header("Authorization", admin(BROKER_B))).andExpect(status().isNotFound());
        mvc.perform(multipart(B + "policies/50/offline-sheet").file(csv(HEAD + "E1,SELF,T-1,,,,\n")).param("mode", "apply").header("Authorization", bearer("BROKER_NON_ADMIN", BROKER_A, null)))
                .andExpect(status().isForbidden());
        mvc.perform(get(B + "policies/50/tpa-pending").header("Authorization", bearer("BROKER_NON_ADMIN", BROKER_A, null))).andExpect(status().isOk());
        mvc.perform(multipart(B + "policies/50/offline-sheet").file(new MockMultipartFile("file", "x.csv", "text/csv", "just,some\ntext,here\n".getBytes())).param("mode", "preview")
                .header("Authorization", admin(BROKER_A))).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void theTemplateIsAnExcelFileOfThePeopleWeAreWaitingOn() throws Exception {
        policyWithThree();
        byte[] x = mvc.perform(get(B + "policies/50/offline-template").header("Authorization", admin(BROKER_A))).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString(".xlsx"))).andReturn().getResponse().getContentAsByteArray();
        try (var wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(x))) {
            Assertions.assertThat(wb.getSheetAt(0).getLastRowNum()).isEqualTo(3);              // header + 3 people waiting
        }
        // the template, filled with ids, is accepted as it stands
        var parsed = com.broker.integration.tpa.OfflineSheet.parse(x, "t.xlsx");
        Assertions.assertThat(parsed.headerLine()).isEqualTo(1);
    }
}

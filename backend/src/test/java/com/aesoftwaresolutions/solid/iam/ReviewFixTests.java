package com.aesoftwaresolutions.solid.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/** Spec 050: the fixes from the second security review, each one a test that failed before it. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ReviewFixTests {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MembershipService memberships;

    ApiClient owner;
    String org;

    @BeforeEach
    void setUp() {
        owner = new ApiClient(rest);
        org = owner.newOrg();
    }

    private UUID userIdOf(String email) {
        return jdbc.queryForObject("select id from iam.user_account where email = ?", UUID.class, email);
    }

    private ApiClient member(String role) {
        ApiClient person = new ApiClient(rest);
        owner.post("/api/v1/orgs/" + org + "/members", Map.of("email", person.email(), "role", role),
                HttpStatus.CREATED);
        return person;
    }

    /**
     * Finding 1: two owners removing each other at the same moment both counted "one other owner" and both
     * succeeded, leaving books nobody could administer — a state no API call could undo.
     */
    @Test
    void twoOwnersCannotRemoveEachOtherAtTheSameMoment() throws Exception {
        ApiClient second = member("owner");
        UUID ownerId = userIdOf(owner.email());
        UUID secondId = userIdOf(second.email());

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Callable<Void>> both = List.of(
                    () -> {
                        memberships.removeMember(UUID.fromString(org), secondId, ownerId, "1.2.3.4");
                        return null;
                    },
                    () -> {
                        memberships.removeMember(UUID.fromString(org), ownerId, secondId, "1.2.3.5");
                        return null;
                    });
            for (Future<Void> result : pool.invokeAll(both)) {
                try {
                    result.get();
                } catch (Exception expectedForOneOfThem) {
                    // One of the two must lose; which one does not matter.
                }
            }
        }

        Integer ownersLeft = jdbc.queryForObject(
                "select count(*) from iam.membership where org_id = ?::uuid and role = 'owner'", Integer.class, org);
        assertThat(ownersLeft).as("an organization must never end up with nobody in charge").isEqualTo(1);
    }

    /** Finding 3: a bookkeeper could switch the entity from cash to accrual, changing what every report means. */
    @Test
    void onlyOwnersAndAdminsChangeEntitySettings() {
        String entity = owner.newEntity(org, "sole_prop");
        ApiClient bookkeeper = member("bookkeeper");

        bookkeeper.patch("/api/v1/orgs/" + org + "/entities/" + entity, Map.of("accountingMethod", "accrual"),
                HttpStatus.FORBIDDEN);
        owner.patch("/api/v1/orgs/" + org + "/entities/" + entity, Map.of("accountingMethod", "accrual"),
                HttpStatus.OK);
    }

    /** Finding 4: the trail said what the setting is now, never what it was before. */
    @Test
    void theEntityAuditSaysWhatChangedFromWhatToWhat() {
        String entity = owner.newEntity(org, "sole_prop");

        owner.patch("/api/v1/orgs/" + org + "/entities/" + entity,
                Map.of("accountingMethod", "accrual", "legalName", "Renamed Ltd"), HttpStatus.OK);

        String audit = owner.get("/api/v1/orgs/" + org + "/audit-events").toString();
        assertThat(audit).contains("entity_updated").contains("cash -> accrual").contains("Renamed Ltd");
    }

    /** Finding 9: the membership grant from an accepted invitation was recorded as a system action. */
    @Test
    void acceptingAnInvitationIsRecordedAgainstThePersonWhoAcceptedIt() {
        String email = "invited-" + UUID.randomUUID() + "@example.test";
        String token = owner.post("/api/v1/orgs/" + org + "/invitations",
                Map.of("email", email, "role", "bookkeeper"), HttpStatus.CREATED).get("token").asText();

        new ApiClient(rest, false).post("/api/v1/auth/accept-invitation",
                Map.of("token", token, "displayName", "Invited", "password", ApiClient.PASSWORD), HttpStatus.OK);

        UUID invitedId = userIdOf(email);
        JsonNode events = owner.get("/api/v1/orgs/" + org + "/audit-events");
        JsonNode added = java.util.stream.StreamSupport.stream(events.spliterator(), false)
                .filter(e -> e.get("action").asText().equals("member_added")).findFirst().orElseThrow();
        assertThat(added.get("actorUserId").asText()).isEqualTo(invitedId.toString());
        assertThat(added.get("actorIp").isNull()).as("and where from").isFalse();
    }
}

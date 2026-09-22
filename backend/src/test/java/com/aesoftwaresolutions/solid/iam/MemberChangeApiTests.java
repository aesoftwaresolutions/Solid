package com.aesoftwaresolutions.solid.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

/** Spec 047. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class MemberChangeApiTests {

    @Autowired
    TestRestTemplate rest;

    ApiClient owner;
    String org;

    @BeforeEach
    void setUp() {
        owner = new ApiClient(rest);
        org = owner.newOrg();
    }

    /** Makes a real second person: a fresh account, added to this organization with the given role. */
    private ApiClient member(String role) {
        ApiClient person = new ApiClient(rest);
        owner.post("/api/v1/orgs/" + org + "/members", Map.of("email", person.email(), "role", role),
                HttpStatus.CREATED);
        return person;
    }

    private String userIdOf(ApiClient person) {
        JsonNode members = owner.get("/api/v1/orgs/" + org + "/members");
        return java.util.stream.StreamSupport.stream(members.spliterator(), false)
                .filter(m -> m.get("email").asText().equals(person.email()))
                .findFirst().orElseThrow().get("userId").asText();
    }

    @Test
    void ac1_anOwnerChangesSomeonesRoleAndTheTrailSaysWhatChanged() {
        ApiClient bookkeeper = member("bookkeeper");

        JsonNode updated = owner.patch("/api/v1/orgs/" + org + "/members/" + userIdOf(bookkeeper),
                Map.of("role", "accountant"), HttpStatus.OK);

        assertThat(updated.get("role").asText()).isEqualTo("accountant");
        String audit = owner.get("/api/v1/orgs/" + org + "/audit-events").toString();
        assertThat(audit).contains("member_role_changed").contains("bookkeeper").contains("accountant");
    }

    @Test
    void ac2_ac7_removingSomeoneEndsTheirAccessHereAndNowhereElse() {
        ApiClient bookkeeper = member("bookkeeper");
        String theirOwnOrg = bookkeeper.newOrg();
        bookkeeper.get("/api/v1/orgs/" + org + "/members", HttpStatus.OK);

        owner.delete("/api/v1/orgs/" + org + "/members/" + userIdOf(bookkeeper), HttpStatus.NO_CONTENT);

        // Gone from these books immediately, with the same 404 any stranger gets.
        bookkeeper.get("/api/v1/orgs/" + org + "/members", HttpStatus.NOT_FOUND);
        // Their own organization is untouched.
        bookkeeper.get("/api/v1/orgs/" + theirOwnOrg + "/members", HttpStatus.OK);
        assertThat(owner.get("/api/v1/orgs/" + org + "/audit-events").toString()).contains("member_removed");
    }

    @Test
    void ac3_theLastOwnerCanBeNeitherDemotedNorRemoved() {
        String ownerId = userIdOf(owner);

        JsonNode demote = owner.patch("/api/v1/orgs/" + org + "/members/" + ownerId,
                Map.of("role", "viewer"), HttpStatus.CONFLICT);
        assertThat(demote.toString()).contains("LAST_OWNER");
        JsonNode remove = owner.delete("/api/v1/orgs/" + org + "/members/" + ownerId, HttpStatus.CONFLICT);
        assertThat(remove.toString()).contains("LAST_OWNER");

        assertThat(owner.get("/api/v1/orgs/" + org + "/members").toString()).contains("owner");
    }

    @Test
    void ac4_anAdminMayNotTouchAnOwnerOrHandOutOwnership() {
        ApiClient admin = member("admin");
        ApiClient bookkeeper = member("bookkeeper");
        String ownerId = userIdOf(owner);

        admin.patch("/api/v1/orgs/" + org + "/members/" + ownerId, Map.of("role", "viewer"), HttpStatus.FORBIDDEN);
        admin.delete("/api/v1/orgs/" + org + "/members/" + ownerId, HttpStatus.FORBIDDEN);
        admin.patch("/api/v1/orgs/" + org + "/members/" + userIdOf(bookkeeper), Map.of("role", "owner"),
                HttpStatus.FORBIDDEN);

        // An admin can still do the ordinary thing.
        admin.patch("/api/v1/orgs/" + org + "/members/" + userIdOf(bookkeeper), Map.of("role", "viewer"),
                HttpStatus.OK);
    }

    @Test
    void ac5_aViewerChangesNobody() {
        ApiClient viewer = member("viewer");
        ApiClient bookkeeper = member("bookkeeper");

        viewer.patch("/api/v1/orgs/" + org + "/members/" + userIdOf(bookkeeper), Map.of("role", "admin"),
                HttpStatus.FORBIDDEN);
        viewer.delete("/api/v1/orgs/" + org + "/members/" + userIdOf(bookkeeper), HttpStatus.FORBIDDEN);
    }

    @Test
    void ac6_youCanLetYourselfOutWhenSomeoneElseOwnsThePlace() {
        ApiClient secondOwner = member("owner");

        secondOwner.delete("/api/v1/orgs/" + org + "/members/" + userIdOf(secondOwner), HttpStatus.NO_CONTENT);

        secondOwner.get("/api/v1/orgs/" + org + "/members", HttpStatus.NOT_FOUND);
        assertThat(owner.get("/api/v1/orgs/" + org + "/members")).hasSize(1);
    }

    @Test
    void ac7_anotherOrganizationsMembersAreOutOfReach() {
        ApiClient outsider = new ApiClient(rest);
        outsider.patch("/api/v1/orgs/" + org + "/members/" + userIdOf(owner), Map.of("role", "viewer"),
                HttpStatus.NOT_FOUND);
        outsider.delete("/api/v1/orgs/" + org + "/members/" + userIdOf(owner), HttpStatus.NOT_FOUND);
        // And a stranger's id is simply not a member here.
        owner.patch("/api/v1/orgs/" + org + "/members/" + UUID.randomUUID(), Map.of("role", "viewer"),
                HttpStatus.NOT_FOUND);
    }
}

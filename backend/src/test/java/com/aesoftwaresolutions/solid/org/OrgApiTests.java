package com.aesoftwaresolutions.solid.org;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class OrgApiTests {

    @Autowired
    TestRestTemplate http;

    private JsonNode post(String path, Object body, HttpStatus expected) {
        ResponseEntity<JsonNode> response = http.postForEntity(path, body, JsonNode.class);
        assertThat(response.getStatusCode()).as(path + " -> " + response.getBody()).isEqualTo(expected);
        return response.getBody();
    }

    private String newOrg(String name) {
        return post("/api/v1/orgs", Map.of("name", name, "kind", "household"), HttpStatus.CREATED).get("id").asText();
    }

    private String newEntity(String orgId, String kind, String name) {
        return post("/api/v1/orgs/" + orgId + "/entities", Map.of("kind", kind, "legalName", name), HttpStatus.CREATED)
                .get("id").asText();
    }

    @Test
    void ac1_ac2_createsEntityWithDefaults() {
        String org = newOrg("Rivera Household");
        JsonNode entity = post("/api/v1/orgs/" + org + "/entities",
                Map.of("kind", "sole_prop", "legalName", "Rivera Design", "homeState", "FL"), HttpStatus.CREATED);

        assertThat(entity.get("orgId").asText()).isEqualTo(org);
        assertThat(entity.get("fiscalYearEnd").asInt()).isEqualTo(12);
        assertThat(entity.get("accountingMethod").asText()).isEqualTo("cash");
        assertThat(entity.get("baseCurrency").asText()).isEqualTo("USD");
        assertThat(entity.get("homeState").asText()).isEqualTo("FL");
        assertThat(UUID.fromString(entity.get("id").asText()).version()).isEqualTo(7);
    }

    @Test
    void ac1_rejectsInvalidKinds() {
        post("/api/v1/orgs", Map.of("name", "X", "kind", "nonprofit"), HttpStatus.BAD_REQUEST);
        String org = newOrg("Kinds");
        JsonNode problem = post("/api/v1/orgs/" + org + "/entities",
                Map.of("kind", "llp", "legalName", "Bad"), HttpStatus.BAD_REQUEST);
        assertThat(problem.get("errors").toString()).contains("kind");
    }

    @Test
    void ac2_validatesFiscalYearEndAndState() {
        String org = newOrg("Validation");
        post("/api/v1/orgs/" + org + "/entities",
                Map.of("kind", "s_corp", "legalName", "Acme", "fiscalYearEnd", 13), HttpStatus.BAD_REQUEST);
        post("/api/v1/orgs/" + org + "/entities",
                Map.of("kind", "s_corp", "legalName", "Acme", "homeState", "fl"), HttpStatus.BAD_REQUEST);
    }

    @Test
    void ac5_ownershipRules() {
        String org = newOrg("Owners");
        String alice = newEntity(org, "individual", "Alice Example");
        String bob = newEntity(org, "individual", "Bob Example");
        String llc = newEntity(org, "partnership", "Example Partners LLC");
        String path = "/api/v1/orgs/" + org + "/ownerships";

        JsonNode first = post(path, Map.of("ownerEntityId", alice, "ownedEntityId", llc, "percent", "60.0000",
                "effectiveFrom", "2026-01-01"), HttpStatus.CREATED);
        assertThat(first.get("percent").asText()).isEqualTo("60.0000");

        // 60 + 50 overlapping = 110% → rejected
        JsonNode conflict = post(path, Map.of("ownerEntityId", bob, "ownedEntityId", llc, "percent", "50",
                "effectiveFrom", "2026-06-01"), HttpStatus.CONFLICT);
        assertThat(conflict.get("code").asText()).isEqualTo("OWNERSHIP_OVER_100");

        // 40 brings it to exactly 100 → allowed
        post(path, Map.of("ownerEntityId", bob, "ownedEntityId", llc, "percent", "40",
                "effectiveFrom", "2026-01-01"), HttpStatus.CREATED);

        // self-ownership and bad percent → 400
        post(path, Map.of("ownerEntityId", llc, "ownedEntityId", llc, "percent", "10",
                "effectiveFrom", "2026-01-01"), HttpStatus.BAD_REQUEST);
        post(path, Map.of("ownerEntityId", alice, "ownedEntityId", bob, "percent", "0",
                "effectiveFrom", "2026-01-01"), HttpStatus.BAD_REQUEST);
        post(path, Map.of("ownerEntityId", alice, "ownedEntityId", bob, "percent", "100.5",
                "effectiveFrom", "2026-01-01"), HttpStatus.BAD_REQUEST);
        post(path, Map.of("ownerEntityId", alice, "ownedEntityId", bob, "percent", "12.34567",
                "effectiveFrom", "2026-01-01"), HttpStatus.BAD_REQUEST);
    }

    @Test
    void ac5_nonOverlappingPeriodsMayEachReach100() {
        String org = newOrg("Periods");
        String alice = newEntity(org, "individual", "Alice Example");
        String bob = newEntity(org, "individual", "Bob Example");
        String biz = newEntity(org, "smllc", "Solo LLC");
        String path = "/api/v1/orgs/" + org + "/ownerships";

        post(path, Map.of("ownerEntityId", alice, "ownedEntityId", biz, "percent", "100",
                "effectiveFrom", "2025-01-01", "effectiveTo", "2026-01-01"), HttpStatus.CREATED);
        post(path, Map.of("ownerEntityId", bob, "ownedEntityId", biz, "percent", "100",
                "effectiveFrom", "2026-01-01"), HttpStatus.CREATED);
    }

    @Test
    void ac6_effectiveToMustBeAfterEffectiveFrom() {
        String org = newOrg("Dates");
        String a = newEntity(org, "individual", "A");
        String b = newEntity(org, "smllc", "B");
        post("/api/v1/orgs/" + org + "/ownerships", Map.of("ownerEntityId", a, "ownedEntityId", b, "percent", "50",
                "effectiveFrom", "2026-05-01", "effectiveTo", "2026-05-01"), HttpStatus.BAD_REQUEST);
    }

    @Test
    void ac5_ac7_entitiesFromAnotherOrgAreInvisible() {
        String orgA = newOrg("Org A");
        String orgB = newOrg("Org B");
        String aliceInA = newEntity(orgA, "individual", "Alice in A");
        String bizInB = newEntity(orgB, "smllc", "Biz in B");

        // Listing B shows only B's entity
        JsonNode listB = http.getForObject("/api/v1/orgs/" + orgB + "/entities", JsonNode.class);
        assertThat(listB).hasSize(1);
        assertThat(listB.get(0).get("id").asText()).isEqualTo(bizInB);

        // Fetching A's entity through org B's URL → 404
        assertThat(http.getForEntity("/api/v1/orgs/" + orgB + "/entities/" + aliceInA, JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Cross-org ownership → 404 (owner not visible in org B)
        post("/api/v1/orgs/" + orgB + "/ownerships", Map.of("ownerEntityId", aliceInA, "ownedEntityId", bizInB,
                "percent", "10", "effectiveFrom", "2026-01-01"), HttpStatus.NOT_FOUND);
    }

    @Test
    void ac7_unknownOrgIs404() {
        assertThat(http.getForEntity("/api/v1/orgs/" + UUID.randomUUID() + "/entities", JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}

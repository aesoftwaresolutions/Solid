package com.aesoftwaresolutions.solid.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Spec 018, AC 3 and 4. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class BackupStatusApiTests {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcClient db;

    @Autowired
    InstanceIdentity identity;

    ApiClient admin;

    @BeforeEach
    void setUp() {
        admin = new ApiClient(rest);
        setInstanceAdmin(admin.email(), true);
    }

    private void setInstanceAdmin(String email, boolean value) {
        db.sql("update iam.user_account set is_instance_admin = ? where email = ?").params(value, email).update();
    }

    @Test
    void ac3_reportsWhatAnOperatorNeedsBeforeAndAfterARestore() {
        String org = admin.newOrg();
        admin.newEntity(org, "sole_prop");

        JsonNode status = admin.get("/api/v1/system/backup-status");

        assertThat(status.get("instanceId").asText()).isEqualTo(identity.instanceId().toString());
        assertThat(status.get("keyFingerprint").asText())
                .hasSize(16)
                .isEqualTo(identity.shortFingerprint())
                .as("the key itself is never returned")
                .isNotEqualTo(identity.fingerprint());
        assertThat(status.get("schemaVersion").asText()).isNotEqualTo("none");
        assertThat(status.get("databaseBytes").asLong()).isPositive();
        assertThat(status.get("tableEstimates").has("gl.journal_entry")).isTrue();
        assertThat(status.get("documents").get("count").asLong()).isNotNegative();
        assertThat(status.get("documentsRoot").asText()).isNotEmpty();
        assertThat(status.get("checkedAt").asText()).isNotEmpty();
    }

    @Test
    void ac4_onlyInstanceAdministratorsMaySeeIt() {
        ApiClient member = new ApiClient(rest);
        setInstanceAdmin(member.email(), false);
        member.get("/api/v1/system/backup-status", HttpStatus.FORBIDDEN);

        ApiClient anonymous = new ApiClient(rest, false);
        anonymous.get("/api/v1/system/backup-status", HttpStatus.UNAUTHORIZED);
    }
}

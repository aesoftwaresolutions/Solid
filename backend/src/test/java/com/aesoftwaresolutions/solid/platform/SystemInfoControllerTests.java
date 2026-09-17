package com.aesoftwaresolutions.solid.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
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
class SystemInfoControllerTests {

    @Autowired
    TestRestTemplate http;

    @Autowired
    Flyway flyway;

    /** Spec 001, AC 3. */
    @Test
    void returnsProductNameVersionAndSchemaVersion() {
        ResponseEntity<SystemInfo> response = http.getForEntity("/api/v1/system/info", SystemInfo.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        SystemInfo body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.name()).isEqualTo("Solid");
        assertThat(body.version()).isNotBlank();
        assertThat(body.databaseSchemaVersion())
                .matches("\\d{12}")
                .isEqualTo(flyway.info().current().getVersion().getVersion());
    }
}

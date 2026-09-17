package com.aesoftwaresolutions.solid;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SolidApplicationTests {

    @Autowired
    JdbcTemplate jdbc;

    /** Spec 001, AC 2: context starts, Flyway runs, all module schemas exist. */
    @Test
    void flywayCreatesAllModuleSchemas() {
        List<String> schemas = jdbc.queryForList(
                "select schema_name from information_schema.schemata", String.class);

        assertThat(schemas).contains(
                "iam", "org", "gl", "bank", "ar_ap", "fa", "pf",
                "tax", "stx", "efile", "doc", "ai", "audit", "sys");
    }
}

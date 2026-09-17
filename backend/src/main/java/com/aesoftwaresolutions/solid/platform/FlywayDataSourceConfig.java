package com.aesoftwaresolutions.solid.platform;

import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway connects as the database owner with a plain connection (no {@code SET ROLE}), because migrations
 * must create tables, roles and policies. The application's pooled connections switch to the restricted
 * {@code solid_app} role instead (see {@code spring.datasource.hikari.connection-init-sql}).
 *
 * <p>We customise Flyway rather than declaring a second DataSource bean, because an extra DataSource bean
 * would make Spring Boot skip creating the main connection pool.
 */
@Configuration(proxyBeanMethods = false)
class FlywayDataSourceConfig {

    @Bean
    FlywayConfigurationCustomizer ownerConnectionForMigrations(JdbcConnectionDetails connection) {
        return configuration -> configuration.dataSource(
                connection.getJdbcUrl(), connection.getUsername(), connection.getPassword());
    }
}

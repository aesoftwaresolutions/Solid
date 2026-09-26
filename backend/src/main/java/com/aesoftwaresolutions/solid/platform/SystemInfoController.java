package com.aesoftwaresolutions.solid.platform;

import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
class SystemInfoController {

    private final ObjectProvider<BuildProperties> buildProperties;
    private final Flyway flyway;
    private final Environment environment;

    SystemInfoController(ObjectProvider<BuildProperties> buildProperties, Flyway flyway, Environment environment) {
        this.buildProperties = buildProperties;
        this.flyway = flyway;
        this.environment = environment;
    }

    @GetMapping("/info")
    SystemInfo info() {
        BuildProperties build = buildProperties.getIfAvailable();
        String version = build != null ? build.getVersion() : "dev";
        MigrationInfo current = flyway.info().current();
        String schemaVersion = current != null ? current.getVersion().getVersion() : "none";
        boolean desktop = environment.getProperty("solid.desktop.enabled", Boolean.class, false)
                || Set.of(environment.getActiveProfiles()).contains("desktop");
        return new SystemInfo("Solid", version, schemaVersion, desktop);
    }
}

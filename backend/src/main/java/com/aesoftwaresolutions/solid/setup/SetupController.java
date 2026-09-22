package com.aesoftwaresolutions.solid.setup;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/entities/{entityId}")
class SetupController {

    private final SetupService setup;

    SetupController(SetupService setup) {
        this.setup = setup;
    }

    @GetMapping("/setup")
    SetupService.Setup setup(@PathVariable UUID orgId, @PathVariable UUID entityId) {
        return setup.setup(orgId, entityId);
    }
}

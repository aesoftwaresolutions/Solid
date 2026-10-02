package com.aesoftwaresolutions.solid.ledger;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/entities/{entityId}/business-lines")
class BusinessLineController {

    record CreateLine(@NotBlank @Size(max = 60) String name) {
    }

    record UpdateLine(@Size(min = 1, max = 60) String name, Boolean archived) {
    }

    private final BusinessLineService lines;

    BusinessLineController(BusinessLineService lines) {
        this.lines = lines;
    }

    @GetMapping
    List<BusinessLineService.BusinessLine> list(@PathVariable UUID orgId, @PathVariable UUID entityId) {
        return lines.list(orgId, entityId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    BusinessLineService.BusinessLine create(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                            @Valid @RequestBody CreateLine body) {
        return lines.create(orgId, entityId, body.name());
    }

    @PatchMapping("/{lineId}")
    BusinessLineService.BusinessLine update(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                            @PathVariable UUID lineId, @Valid @RequestBody UpdateLine body) {
        return lines.update(orgId, entityId, lineId, body.name(), body.archived());
    }
}

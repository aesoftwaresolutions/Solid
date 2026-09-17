package com.aesoftwaresolutions.solid.org;

import com.aesoftwaresolutions.solid.audit.AuditLog;
import com.aesoftwaresolutions.solid.iam.CurrentUser;
import com.aesoftwaresolutions.solid.iam.MembershipService;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// Authentication and org membership are enforced by the iam module (SecurityConfig + OrgAccessInterceptor).
@RestController
@RequestMapping("/api/v1/orgs")
class OrgController {

    private final OrgService orgs;
    private final MembershipService memberships;
    private final AuditLog audit;

    OrgController(OrgService orgs, MembershipService memberships, AuditLog audit) {
        this.orgs = orgs;
        this.memberships = memberships;
        this.audit = audit;
    }

    /** Organizations the current user belongs to. */
    @GetMapping
    List<Organization> mine() {
        return memberships.orgIdsFor(CurrentUser.require().userId()).stream().map(orgs::getOrganization).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @org.springframework.transaction.annotation.Transactional
    Organization create(@Valid @RequestBody OrgRequests.CreateOrganization body) {
        Organization org = orgs.createOrganization(body.name(), body.kind());
        memberships.addOwner(org.id(), CurrentUser.require().userId());
        audit.record(AuditLog.Actor.current(), org.id(), "org_created", "organization", org.id(),
                java.util.Map.of("kind", org.kind()));
        return org;
    }

    @GetMapping("/{orgId}")
    Organization get(@PathVariable UUID orgId) {
        return orgs.getOrganization(orgId);
    }

    @PostMapping("/{orgId}/entities")
    @ResponseStatus(HttpStatus.CREATED)
    LegalEntity createEntity(@PathVariable UUID orgId, @Valid @RequestBody OrgRequests.CreateEntity body) {
        return orgs.createEntity(orgId, body.kind(), body.legalName(), body.fiscalYearEnd(),
                body.accountingMethod(), body.homeState(), body.baseCurrency());
    }

    @GetMapping("/{orgId}/entities")
    List<LegalEntity> listEntities(@PathVariable UUID orgId) {
        return orgs.listEntities(orgId);
    }

    @GetMapping("/{orgId}/entities/{entityId}")
    LegalEntity getEntity(@PathVariable UUID orgId, @PathVariable UUID entityId) {
        return orgs.getEntity(orgId, entityId);
    }

    @PostMapping("/{orgId}/ownerships")
    @ResponseStatus(HttpStatus.CREATED)
    Ownership createOwnership(@PathVariable UUID orgId, @Valid @RequestBody OrgRequests.CreateOwnership body) {
        return orgs.createOwnership(orgId, body.ownerEntityId(), body.ownedEntityId(),
                new BigDecimal(body.percent()), body.effectiveFrom(), body.effectiveTo());
    }

    @GetMapping("/{orgId}/ownerships")
    List<Ownership> listOwnerships(@PathVariable UUID orgId) {
        return orgs.listOwnerships(orgId);
    }
}

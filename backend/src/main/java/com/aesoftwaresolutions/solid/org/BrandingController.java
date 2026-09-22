package com.aesoftwaresolutions.solid.org;

import com.aesoftwaresolutions.solid.common.ForbiddenException;
import com.aesoftwaresolutions.solid.common.NotFoundException;
import com.aesoftwaresolutions.solid.iam.CurrentUser;
import com.aesoftwaresolutions.solid.iam.MembershipService;
import com.aesoftwaresolutions.solid.iam.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** The letterhead (spec 056): any member may read it; only an owner or admin may change it. */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/entities/{entityId}/branding")
class BrandingController {

    record BrandingRequest(@Size(max = 400) String address, @Size(max = 60) String phone,
                           @Size(max = 200) String email, @Size(max = 200) String website,
                           @Size(max = 60) String taxId, @Size(max = 500) String paymentInstructions) {
    }

    private final BrandingService branding;
    private final MembershipService memberships;

    BrandingController(BrandingService branding, MembershipService memberships) {
        this.branding = branding;
        this.memberships = memberships;
    }

    @GetMapping
    Branding get(@PathVariable UUID orgId, @PathVariable UUID entityId) {
        return branding.get(orgId, entityId);
    }

    @PutMapping
    Branding save(@PathVariable UUID orgId, @PathVariable UUID entityId,
                  @Valid @RequestBody BrandingRequest body) {
        requireManager(orgId);
        return branding.save(orgId, entityId, body.address(), body.phone(), body.email(), body.website(),
                body.taxId(), body.paymentInstructions());
    }

    @PutMapping(path = "/logo", consumes = "multipart/form-data")
    Branding saveLogo(@PathVariable UUID orgId, @PathVariable UUID entityId,
                      @RequestPart("file") MultipartFile file) throws IOException {
        requireManager(orgId);
        if (file.getSize() > BrandingService.MAX_LOGO_BYTES) {
            throw new com.aesoftwaresolutions.solid.common.ApiProblemException(400, "LOGO_TOO_LARGE",
                    "A logo must be 1 MB or smaller");
        }
        return branding.saveLogo(orgId, entityId, file.getBytes());
    }

    @DeleteMapping("/logo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteLogo(@PathVariable UUID orgId, @PathVariable UUID entityId) {
        requireManager(orgId);
        branding.deleteLogo(orgId, entityId);
    }

    @GetMapping("/logo")
    ResponseEntity<Resource> logo(@PathVariable UUID orgId, @PathVariable UUID entityId) {
        BrandingService.Logo logo = branding.logo(orgId, entityId)
                .orElseThrow(() -> new NotFoundException("This entity has no logo"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(logo.contentType()))
                .contentLength(logo.bytes().length)
                .body(new ByteArrayResource(logo.bytes()));
    }

    /** Changing what goes out on every document is not a bookkeeping task. */
    private void requireManager(UUID orgId) {
        Role role = memberships.roleFor(orgId, CurrentUser.require().userId())
                .orElseThrow(() -> new NotFoundException("Organization " + orgId + " not found"));
        if (!role.canManageMembers()) {
            throw new ForbiddenException("Only an owner or admin can change the letterhead");
        }
    }
}

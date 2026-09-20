package com.aesoftwaresolutions.solid.migration;

import com.aesoftwaresolutions.solid.migration.ImportModels.ImportResult;
import com.aesoftwaresolutions.solid.migration.ImportModels.Kind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/entities/{entityId}/imports")
class ImportController {

    /** The file's text. It is sent as JSON rather than multipart so the browser can show a preview of it too. */
    record ImportRequest(@NotNull String csv) {
    }

    private final ImportService imports;

    ImportController(ImportService imports) {
        this.imports = imports;
    }

    @PostMapping("/{kind}/preview")
    ImportResult preview(@PathVariable UUID orgId, @PathVariable UUID entityId, @PathVariable Kind kind,
                         @Valid @RequestBody ImportRequest body) {
        return imports.preview(orgId, entityId, kind, body.csv());
    }

    /**
     * Does the import. Returns 422 with the same report — and writes nothing — when any row has a problem,
     * so a caller cannot mistake a refused file for a successful one.
     */
    @PostMapping("/{kind}")
    ResponseEntity<ImportResult> commit(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                        @PathVariable Kind kind, @Valid @RequestBody ImportRequest body) {
        ImportResult result = imports.commit(orgId, entityId, kind, body.csv());
        return ResponseEntity.status(result.committed() ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_ENTITY)
                .body(result);
    }
}

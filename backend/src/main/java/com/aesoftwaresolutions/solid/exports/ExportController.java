package com.aesoftwaresolutions.solid.exports;

import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/entities/{entityId}")
class ExportController {

    private final ExportService exports;

    ExportController(ExportService exports) {
        this.exports = exports;
    }

    @GetMapping("/export.zip")
    ResponseEntity<Resource> export(@PathVariable UUID orgId, @PathVariable UUID entityId) {
        byte[] zip = exports.exportEntity(orgId, entityId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("solid-export-" + entityId + ".zip").build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(zip.length)
                .body(new ByteArrayResource(zip));
    }
}

package com.aesoftwaresolutions.solid.billing;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/entities/{entityId}/customers/{customerId}")
class StatementController {

    private final StatementService statements;

    StatementController(StatementService statements) {
        this.statements = statements;
    }

    @GetMapping("/statement")
    StatementService.Statement statement(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                         @PathVariable UUID customerId,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return statements.statement(orgId, entityId, customerId, from, to);
    }

    @GetMapping("/statement.pdf")
    ResponseEntity<Resource> statementPdf(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                          @PathVariable UUID customerId,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        byte[] pdf = statements.statementPdf(orgId, entityId, customerId, from, to);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("statement-" + customerId + ".pdf").build().toString())
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.length)
                .body(new ByteArrayResource(pdf));
    }
}

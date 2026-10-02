package com.aesoftwaresolutions.solid.ledger;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/entities/{entityId}")
class JournalController {

    private final JournalService journal;

    JournalController(JournalService journal) {
        this.journal = journal;
    }

    @PostMapping("/journal-entries")
    ResponseEntity<JournalEntry> create(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                        @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                        @Valid @RequestBody JournalRequests.CreateEntry body) {
        if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > 100)) {
            throw new IllegalArgumentException("Idempotency-Key must be 1-100 characters");
        }
        List<JournalService.NewLine> lines = body.lines().stream()
                .map(l -> new JournalService.NewLine(l.accountId(), l.amount(), l.memo(), l.businessLineId())).toList();
        JournalService.CreateResult result = journal.create(orgId, entityId, body.entryDate(), body.memo(),
                Boolean.TRUE.equals(body.post()), lines, idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.entry());
    }

    @GetMapping("/journal-entries")
    List<JournalEntry> list(@PathVariable UUID orgId, @PathVariable UUID entityId,
                            @RequestParam(required = false) LocalDate from,
                            @RequestParam(required = false) LocalDate to,
                            @RequestParam(required = false) JournalEntry.Status status) {
        return journal.list(orgId, entityId, from, to, status);
    }

    @GetMapping("/journal-entries/{entryId}")
    JournalEntry get(@PathVariable UUID orgId, @PathVariable UUID entityId, @PathVariable UUID entryId) {
        return journal.get(orgId, entityId, entryId);
    }

    @PostMapping("/journal-entries/{entryId}/post")
    JournalEntry post(@PathVariable UUID orgId, @PathVariable UUID entityId, @PathVariable UUID entryId) {
        return journal.post(orgId, entityId, entryId);
    }

    @PostMapping("/journal-entries/{entryId}/reverse")
    @ResponseStatus(HttpStatus.CREATED)
    JournalEntry reverse(@PathVariable UUID orgId, @PathVariable UUID entityId, @PathVariable UUID entryId,
                         @Valid @RequestBody(required = false) JournalRequests.Reverse body) {
        return journal.reverse(orgId, entityId, entryId, body == null ? null : body.entryDate(),
                body == null ? null : body.memo());
    }

    @DeleteMapping("/journal-entries/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteDraft(@PathVariable UUID orgId, @PathVariable UUID entityId, @PathVariable UUID entryId) {
        journal.deleteDraft(orgId, entityId, entryId);
    }

    @GetMapping("/period-lock")
    Map<String, Object> periodLock(@PathVariable UUID orgId, @PathVariable UUID entityId) {
        return java.util.Collections.singletonMap("lockedThrough", journal.periodLock(orgId, entityId).orElse(null));
    }

    @PutMapping("/period-lock")
    Map<String, Object> setPeriodLock(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                      @Valid @RequestBody JournalRequests.PeriodLock body) {
        return Map.of("lockedThrough", journal.setPeriodLock(orgId, entityId, body.lockedThrough()));
    }

    @GetMapping("/journal/verify")
    JournalService.ChainVerification verify(@PathVariable UUID orgId, @PathVariable UUID entityId) {
        return journal.verifyChain(orgId, entityId);
    }
}

package com.aesoftwaresolutions.solid.ledger;

import com.aesoftwaresolutions.solid.money.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
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

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/entities/{entityId}")
class RecurringController {

    record LineRequest(@NotNull UUID accountId, @NotNull Money amount, @Size(max = 500) String memo) {
    }

    record CreateRecurring(@NotBlank @Size(max = 120) String name, @Size(max = 500) String memo,
                           @NotNull @Pattern(regexp = "monthly|quarterly|annual") String frequency,
                           @NotNull LocalDate startDate, LocalDate endDate,
                           @Min(1) @Max(31) Integer dayOfMonth,
                           @NotEmpty @Size(max = 100) List<@Valid LineRequest> lines) {
    }

    record RunRequest(@NotNull LocalDate through) {
    }

    private final RecurringService recurring;

    RecurringController(RecurringService recurring) {
        this.recurring = recurring;
    }

    @PostMapping("/recurring-entries")
    @ResponseStatus(HttpStatus.CREATED)
    RecurringModels.Recurring create(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                     @Valid @RequestBody CreateRecurring body) {
        return recurring.create(orgId, entityId, body.name(), body.memo(), body.frequency(), body.startDate(),
                body.endDate(), body.dayOfMonth(),
                body.lines().stream().map(l -> new RecurringService.NewLine(l.accountId(), l.amount(), l.memo()))
                        .toList());
    }

    @GetMapping("/recurring-entries")
    List<RecurringModels.Recurring> list(@PathVariable UUID orgId, @PathVariable UUID entityId) {
        return recurring.list(orgId, entityId);
    }

    @GetMapping("/recurring-entries/{recurringId}")
    RecurringModels.Recurring get(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                  @PathVariable UUID recurringId) {
        return recurring.get(orgId, entityId, recurringId);
    }

    @PostMapping("/recurring-entries/{recurringId}/deactivate")
    RecurringModels.Recurring deactivate(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                         @PathVariable UUID recurringId) {
        return recurring.deactivate(orgId, entityId, recurringId);
    }

    @PostMapping("/recurring-entries/run")
    RecurringModels.RunResult run(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                  @Valid @RequestBody RunRequest body) {
        return recurring.run(orgId, entityId, body.through());
    }
}
